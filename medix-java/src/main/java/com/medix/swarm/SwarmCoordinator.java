package com.medix.swarm;

import com.medix.agent.AgentRequest;
import com.medix.agent.AgentResult;
import com.medix.agent.ConsultationAgent;
import com.medix.agent.DiagnosticAgent;
import com.medix.agent.LeadAgent;
import com.medix.agent.MedicalAgent;
import com.medix.agent.ResearchAgent;
import java.util.ArrayList;
import java.util.List;
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
        RouteDecision decision = router.route(request.question());
        sharedContextStore.put(request.sessionId(), "route.mode", decision.mode().name());
        sharedContextStore.put(request.sessionId(), "route.reason", decision.reason());
        if (decision.mode() == RouteMode.SINGLE_AGENT) {
            AgentResult result = consultationAgent.answer(request);
            sharedContextStore.put(request.sessionId(), result.agentId() + ".status", "completed");
            return new SwarmResponse(decision, result.answer(), List.of(result), sharedContextStore.entries(request.sessionId()));
        }
        List<CompletableFuture<AgentResult>> futures = List.of(
                CompletableFuture.supplyAsync(() -> runAgent(request, consultationAgent)),
                CompletableFuture.supplyAsync(() -> runAgent(request, diagnosticAgent)),
                CompletableFuture.supplyAsync(() -> runAgent(request, researchAgent))
        );
        List<AgentResult> results = new ArrayList<>();
        for (CompletableFuture<AgentResult> future : futures) {
            results.add(future.join());
        }
        String answer = leadAgent.synthesize(request.question(), results);
        sharedContextStore.put(request.sessionId(), "lead_agent.status", "synthesized");
        return new SwarmResponse(decision, answer, results, sharedContextStore.entries(request.sessionId()));
    }

    private AgentResult runAgent(AgentRequest request, MedicalAgent agent) {
        sharedContextStore.put(request.sessionId(), agent.agentId() + ".status", "running");
        AgentResult result = agent.answer(request);
        sharedContextStore.put(request.sessionId(), agent.agentId() + ".status", "completed");
        sharedContextStore.put(request.sessionId(), agent.agentId() + ".skills", String.join(",", result.skillCalls()));
        return result;
    }
}
