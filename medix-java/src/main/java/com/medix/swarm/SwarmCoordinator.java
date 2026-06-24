package com.medix.swarm;

import com.medix.agent.AgentRequest;
import com.medix.agent.AgentResult;
import com.medix.agent.ConsultationAgent;
import com.medix.agent.DiagnosticAgent;
import com.medix.agent.LeadAgent;
import com.medix.agent.MedicalAgent;
import com.medix.agent.ResearchAgent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @Autowired
    public SwarmCoordinator(
            SwarmRouter router,
            ConsultationAgent consultationAgent,
            DiagnosticAgent diagnosticAgent,
            ResearchAgent researchAgent,
            LeadAgent leadAgent,
            SharedContextStore sharedContextStore
    ) {
        this.router = router;
        this.consultationAgent = consultationAgent;
        this.diagnosticAgent = diagnosticAgent;
        this.researchAgent = researchAgent;
        this.leadAgent = leadAgent;
        this.sharedContextStore = sharedContextStore;
    }

    public SwarmCoordinator(
            SwarmRouter router,
            ConsultationAgent consultationAgent,
            DiagnosticAgent diagnosticAgent,
            ResearchAgent researchAgent,
            LeadAgent leadAgent
    ) {
        this(router, consultationAgent, diagnosticAgent, researchAgent, leadAgent, new SharedContextStore());
    }

    public String process(AgentRequest request) {
        return processDetailed(request).answer();
    }

    public SwarmResponse processDetailed(AgentRequest request) {
        List<SwarmSubtask> subtasks = leadAgent.assessAndDecompose(request.question(), request.context());
        if (subtasks.isEmpty()) {
            subtasks = List.of(new SwarmSubtask("1", "回答用户问题并提供安全的健康建议：" + request.question(), "consultation_agent"));
        }
        recordSubtasks(request.sessionId(), subtasks);
        RouteDecision decision = routeFromSubtasks(subtasks);
        sharedContextStore.put(request.sessionId(), "route.mode", decision.mode().name());
        sharedContextStore.put(request.sessionId(), "route.reason", decision.reason());
        if (decision.mode() == RouteMode.SINGLE_AGENT) {
            SwarmSubtask subtask = subtasks.getFirst();
            AgentResult result = runSubtask(request, subtask);
            return new SwarmResponse(decision, result.answer(), List.of(result), sharedContextStore.entries(request.sessionId()));
        }
        List<CompletableFuture<AgentResult>> futures = subtasks.stream()
                .map(subtask -> CompletableFuture.supplyAsync(() -> runSubtask(request, subtask)))
                .toList();
        List<AgentResult> results = new ArrayList<>();
        for (CompletableFuture<AgentResult> future : futures) {
            results.add(future.join());
        }
        String answer = leadAgent.synthesize(request.question(), results);
        sharedContextStore.put(request.sessionId(), "lead_agent.status", "synthesized");
        return new SwarmResponse(decision, answer, results, sharedContextStore.entries(request.sessionId()));
    }

    private AgentResult runSubtask(AgentRequest request, SwarmSubtask subtask) {
        SwarmSubtask assignedSubtask = subtaskFromSharedContext(request.sessionId(), subtask);
        MedicalAgent agent = agentById(assignedSubtask.assignedAgent());
        if (agent == null) {
            agent = consultationAgent;
        }
        sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".status", "running");
        AgentRequest subtaskRequest = new AgentRequest(
                assignedSubtask.description(),
                request.sessionId(),
                subtaskContext(request, assignedSubtask)
        );
        AgentResult result = runAgent(subtaskRequest, agent);
        sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".status", "completed");
        sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".result", result.answer());
        sharedContextStore.put(request.sessionId(), "contribution." + assignedSubtask.id() + ".agent", result.agentId());
        sharedContextStore.put(request.sessionId(), "contribution." + assignedSubtask.id() + ".answer", result.answer());
        return result;
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
}
