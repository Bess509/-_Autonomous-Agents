package com.medix.swarm;

import com.medix.agent.AgentRequest;
import com.medix.agent.AgentResult;
import com.medix.agent.AgentDelegationRequest;
import com.medix.agent.ConsultationAgent;
import com.medix.agent.DiagnosticAgent;
import com.medix.agent.LeadAgent;
import com.medix.agent.MedicalAgent;
import com.medix.agent.ResearchAgent;
import com.medix.harness.OutputRepairService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SwarmCoordinator {
    private final SwarmRouter router;
    private final ConsultationAgent consultationAgent;
    private final DiagnosticAgent diagnosticAgent;
    private final ResearchAgent researchAgent;
    private final LeadAgent leadAgent;
    private final SharedContextStore sharedContextStore;
    private final OutputRepairService outputSafety;

    @Autowired
    public SwarmCoordinator(
            SwarmRouter router,
            ConsultationAgent consultationAgent,
            DiagnosticAgent diagnosticAgent,
            ResearchAgent researchAgent,
            LeadAgent leadAgent,
            SharedContextStore sharedContextStore,
            OutputRepairService outputSafety
    ) {
        this.router = router;
        this.consultationAgent = consultationAgent;
        this.diagnosticAgent = diagnosticAgent;
        this.researchAgent = researchAgent;
        this.leadAgent = leadAgent;
        this.sharedContextStore = sharedContextStore;
        this.outputSafety = outputSafety;
    }

    public SwarmCoordinator(
            SwarmRouter router,
            ConsultationAgent consultationAgent,
            DiagnosticAgent diagnosticAgent,
            ResearchAgent researchAgent,
            LeadAgent leadAgent,
            SharedContextStore sharedContextStore
    ) {
        this(router, consultationAgent, diagnosticAgent, researchAgent, leadAgent, sharedContextStore,
                new OutputRepairService());
    }

    public SwarmCoordinator(
            SwarmRouter router,
            ConsultationAgent consultationAgent,
            DiagnosticAgent diagnosticAgent,
            ResearchAgent researchAgent,
            LeadAgent leadAgent
    ) {
        this(router, consultationAgent, diagnosticAgent, researchAgent, leadAgent, new SharedContextStore(),
                new OutputRepairService());
    }

    public String process(AgentRequest request) {
        return processDetailed(request).answer();
    }

    public SwarmResponse processDetailed(AgentRequest request) {
        return processDetailed(request, Set.of("consultation_agent", "diagnostic_agent", "research_agent"));
    }

    public SwarmResponse processDetailed(AgentRequest request, Set<String> allowedAgents) {
        Map<String, Object> authorizedContext = new LinkedHashMap<>(request.context());
        authorizedContext.put("security.allowedAgents", Set.copyOf(allowedAgents));
        request = new AgentRequest(request.question(), request.sessionId(), authorizedContext);
        final AgentRequest executionRequest = request;
        RouteDecision initialDecision = router.route(request.question());
        recordRoute(request.sessionId(), initialDecision);
        if ("emergency_rule".equals(initialDecision.reason()) && !allowedAgents.contains("diagnostic_agent")) {
            sharedContextStore.put(request.sessionId(), "emergency.status", "fixed_safety_response");
            String safeAnswer = outputSafety.repair(
                    "检测到可能危及生命的急症信号。即使当前账户没有诊断 Agent 权限，也请立即拨打 120 "
                            + "或前往急诊，不要等待在线回复，也不要自行驾车。"
            , request.question());
            return new SwarmResponse(initialDecision, safeAnswer, List.of(),
                    sharedContextStore.entries(request.sessionId()));
        }
        List<SwarmSubtask> subtasks;
        RouteDecision decision;
        if (initialDecision.requiresLeadAgent()) {
            sharedContextStore.put(request.sessionId(), "lead_agent.status", "decomposing");
            subtasks = leadAgent.assessAndDecompose(request.question(), request.context());
            if (subtasks.isEmpty()) {
                subtasks = List.of(new SwarmSubtask("1", "回答用户问题并提供安全的健康建议：" + request.question(), "consultation_agent"));
            }
            decision = routeFromSubtasks(subtasks);
            sharedContextStore.put(request.sessionId(), "route.finalReason", decision.reason());
        } else {
            subtasks = directSubtasks(request.question(), initialDecision.requiredAgents());
            decision = initialDecision;
            sharedContextStore.put(request.sessionId(), "lead_agent.status", "bypassed");
        }
        recordSubtasks(request.sessionId(), subtasks);
        sharedContextStore.put(request.sessionId(), "route.mode", decision.mode().name());
        if (decision.mode() == RouteMode.SINGLE_AGENT) {
            SwarmSubtask subtask = subtasks.getFirst();
            requireAllowed(subtask.assignedAgent(), allowedAgents);
            AgentResult result = runSubtask(request, subtask);
            String answer = synthesize(request.question(), List.of(result));
            sharedContextStore.put(request.sessionId(), "response.synthesizerInvocations", "1");
            return new SwarmResponse(decision, outputSafety.repair(answer, request.question()), List.of(result),
                    sharedContextStore.entries(request.sessionId()));
        }
        subtasks.forEach(subtask -> requireAllowed(subtask.assignedAgent(), allowedAgents));
        List<CompletableFuture<AgentResult>> futures = subtasks.stream()
                .map(subtask -> CompletableFuture.supplyAsync(() -> runSubtask(executionRequest, subtask)))
                .toList();
        List<AgentResult> results = new ArrayList<>();
        for (CompletableFuture<AgentResult> future : futures) {
            results.add(future.join());
        }
        String answer = synthesize(request.question(), results);
        sharedContextStore.put(request.sessionId(), "response.synthesizerInvocations", "1");
        sharedContextStore.put(request.sessionId(), "lead_agent.status", "synthesized");
        return new SwarmResponse(decision, outputSafety.repair(answer, request.question()), results,
                sharedContextStore.entries(request.sessionId()));
    }

    private String synthesize(String question, List<AgentResult> results) {
        String answer = leadAgent.synthesize(question, results);
        return answer == null || answer.isBlank() ? new LeadAgent().synthesize(question, results) : answer;
    }

    private List<SwarmSubtask> directSubtasks(String question, List<String> agents) {
        List<String> effectiveAgents = agents.isEmpty() ? List.of("consultation_agent") : agents;
        return java.util.stream.IntStream.range(0, effectiveAgents.size())
                .mapToObj(index -> new SwarmSubtask(String.valueOf(index + 1), question, effectiveAgents.get(index)))
                .toList();
    }

    private void recordRoute(String sessionId, RouteDecision decision) {
        sharedContextStore.put(sessionId, "route.reason", decision.reason());
        sharedContextStore.put(sessionId, "route.requiresLeadAgent", String.valueOf(decision.requiresLeadAgent()));
        String nluStatus = switch (decision.reason()) {
            case "nlu_unavailable" -> "unavailable";
            case "nlu_disabled" -> "disabled";
            case "emergency_rule" -> "skipped_emergency";
            default -> "completed";
        };
        sharedContextStore.put(sessionId, "nlu.status", nluStatus);
        decision.probabilities().forEach((label, probability) ->
                sharedContextStore.put(sessionId, "nlu.probability." + label, String.valueOf(probability)));
    }

    private AgentResult runSubtask(AgentRequest request, SwarmSubtask subtask) {
        SwarmSubtask assignedSubtask = subtaskFromSharedContext(request.sessionId(), subtask);
        MedicalAgent agent = agentById(assignedSubtask.assignedAgent());
        if (agent == null) {
            throw new SecurityException("UNKNOWN_AGENT");
        }
        sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".status", "running");
        AgentRequest subtaskRequest = new AgentRequest(
                assignedSubtask.description(),
                request.sessionId(),
                subtaskContext(request, assignedSubtask)
        );
        try {
            AgentResult result = runAgent(subtaskRequest, agent);
            sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".status", "completed");
            sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".result", result.answer());
            sharedContextStore.put(request.sessionId(), "contribution." + assignedSubtask.id() + ".agent", result.agentId());
            sharedContextStore.put(request.sessionId(), "contribution." + assignedSubtask.id() + ".answer", result.answer());
            return result;
        } catch (AgentDelegationRequest delegation) {
            sharedContextStore.put(request.sessionId(), agent.agentId() + ".status", "delegated");
            sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".status", "delegated");
            sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".delegatedTo", delegation.targetAgent());
            sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".delegatedTask", delegation.task());
            return runDelegatedSubtask(request, assignedSubtask, delegation);
        } catch (SecurityException denied) {
            throw denied;
        } catch (RuntimeException exception) {
            sharedContextStore.put(request.sessionId(), agent.agentId() + ".status", "failed");
            sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".status", "failed");
            sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".error", safeErrorCode(exception));
            AgentResult result = degradedResult(agent.agentId());
            sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".result", result.answer());
            sharedContextStore.put(request.sessionId(), "contribution." + assignedSubtask.id() + ".agent", result.agentId());
            sharedContextStore.put(request.sessionId(), "contribution." + assignedSubtask.id() + ".answer", result.answer());
            return result;
        }
    }

    private AgentResult runAgent(AgentRequest request, MedicalAgent agent) {
        sharedContextStore.put(request.sessionId(), agent.agentId() + ".status", "running");
        AgentResult result = agent.answer(request);
        sharedContextStore.put(request.sessionId(), agent.agentId() + ".status", "completed");
        sharedContextStore.put(request.sessionId(), agent.agentId() + ".skills", String.join(",", result.skillCalls()));
        return result;
    }

    private RouteDecision routeFromSubtasks(List<SwarmSubtask> subtasks) {
        List<String> requiredAgents = subtasks.stream()
                .map(SwarmSubtask::assignedAgent)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (subtasks.size() == 1) {
            String primaryAgent = requiredAgents.isEmpty() ? "consultation_agent" : requiredAgents.getFirst();
            return new RouteDecision(
                    RouteMode.SINGLE_AGENT,
                    primaryAgent,
                    List.of(primaryAgent),
                    "lead_agent_single_subtask"
            );
        }
        return new RouteDecision(
                RouteMode.SWARM,
                "lead_agent",
                requiredAgents,
                "lead_agent_multi_subtask"
        );
    }

    private AgentResult runDelegatedSubtask(
            AgentRequest request,
            SwarmSubtask sourceSubtask,
            AgentDelegationRequest delegation
    ) {
        Object allowed = request.context().get("security.allowedAgents");
        if (!(allowed instanceof Set<?> set) || !set.contains(delegation.targetAgent())) {
            throw new SecurityException("USER_AGENT_GRANT_MISSING");
        }
        MedicalAgent targetAgent = agentById(delegation.targetAgent());
        if (targetAgent == null || delegation.targetAgent().equals(sourceSubtask.assignedAgent())) {
            return degradedResult(delegation.sourceAgent());
        }
        if (targetAgentAlreadyAssigned(request.sessionId(), delegation.targetAgent())) {
            return new AgentResult(
                    delegation.sourceAgent(),
                    "已将超出当前能力边界的部分交由 " + delegation.targetAgent() + " 处理。",
                    1,
                    List.of()
            );
        }
        SwarmSubtask delegatedSubtask = new SwarmSubtask(
                sourceSubtask.id() + "-delegated",
                delegation.task(),
                delegation.targetAgent()
        );
        sharedContextStore.put(request.sessionId(), "subtask." + delegatedSubtask.id() + ".description", delegatedSubtask.description());
        sharedContextStore.put(request.sessionId(), "subtask." + delegatedSubtask.id() + ".assignedAgent", delegatedSubtask.assignedAgent());
        sharedContextStore.put(request.sessionId(), "subtask." + delegatedSubtask.id() + ".delegatedFrom", sourceSubtask.id());
        sharedContextStore.put(request.sessionId(), "subtask." + delegatedSubtask.id() + ".status", "pending");
        return runSubtask(request, delegatedSubtask);
    }

    private boolean targetAgentAlreadyAssigned(String sessionId, String targetAgent) {
        return sharedContextStore.entries(sessionId).entrySet().stream()
                .anyMatch(entry -> entry.getKey().endsWith(".assignedAgent") && targetAgent.equals(entry.getValue()));
    }

    private AgentResult degradedResult(String agentId) {
        return new AgentResult(
                agentId,
                agentId + " 暂时无法完成该子任务，系统将基于其他可用信息继续给出安全建议。",
                0,
                List.of()
        );
    }

    private void recordSubtasks(String sessionId, List<SwarmSubtask> subtasks) {
        sharedContextStore.put(sessionId, "subtasks.count", String.valueOf(subtasks.size()));
        for (SwarmSubtask subtask : subtasks) {
            sharedContextStore.put(sessionId, "subtask." + subtask.id() + ".description", subtask.description());
            sharedContextStore.put(sessionId, "subtask." + subtask.id() + ".assignedAgent", subtask.assignedAgent());
            sharedContextStore.put(sessionId, "subtask." + subtask.id() + ".status", "pending");
        }
    }

    private Map<String, Object> subtaskContext(AgentRequest request, SwarmSubtask subtask) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (request.context() != null) {
            context.putAll(request.context());
        }
        context.put("originalQuestion", request.question());
        context.put("subtaskId", subtask.id());
        context.put("assignedAgent", subtask.assignedAgent());
        return context;
    }

    private SwarmSubtask subtaskFromSharedContext(String sessionId, SwarmSubtask fallback) {
        Map<String, String> entries = sharedContextStore.entries(sessionId);
        String prefix = "subtask." + fallback.id() + ".";
        return new SwarmSubtask(
                fallback.id(),
                entries.getOrDefault(prefix + "description", fallback.description()),
                entries.getOrDefault(prefix + "assignedAgent", fallback.assignedAgent())
        );
    }

    private MedicalAgent agentById(String agentId) {
        if (consultationAgent.agentId().equals(agentId)) {
            return consultationAgent;
        }
        if (diagnosticAgent.agentId().equals(agentId)) {
            return diagnosticAgent;
        }
        if (researchAgent.agentId().equals(agentId)) {
            return researchAgent;
        }
        return null;
    }

    private String safeErrorCode(RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null && message.matches("LLM_[A-Z_]+")) return message;
        Throwable cause = exception.getCause();
        if (cause != null && cause.getMessage() != null && cause.getMessage().matches("LLM_[A-Z_]+")) {
            return cause.getMessage();
        }
        return exception.getClass().getSimpleName();
    }

    private void requireAllowed(String agentId, Set<String> allowedAgents) {
        if (agentById(agentId) == null) {
            throw new SecurityException("UNKNOWN_AGENT");
        }
        if (!allowedAgents.contains(agentId)) {
            throw new SecurityException("NO_AUTHORIZED_AGENT");
        }
    }
}
