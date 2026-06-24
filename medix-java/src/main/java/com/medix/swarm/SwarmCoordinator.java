package com.medix.swarm;

import com.medix.agent.AgentRequest;
import com.medix.agent.AgentResult;
import com.medix.agent.ConsultationAgent;
import com.medix.agent.DiagnosticAgent;
import com.medix.agent.LeadAgent;
import com.medix.agent.ResearchAgent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

@Component
public class SwarmCoordinator {
    private final SwarmRouter router;
    private final ConsultationAgent consultationAgent;
    private final DiagnosticAgent diagnosticAgent;
    private final ResearchAgent researchAgent;
    private final LeadAgent leadAgent;

    public SwarmCoordinator(
            SwarmRouter router,
            ConsultationAgent consultationAgent,
            DiagnosticAgent diagnosticAgent,
            ResearchAgent researchAgent,
            LeadAgent leadAgent
    ) {
        this.router = router;
        this.consultationAgent = consultationAgent;
        this.diagnosticAgent = diagnosticAgent;
        this.researchAgent = researchAgent;
        this.leadAgent = leadAgent;
    }

    public String process(AgentRequest request) {
        RouteDecision decision = router.route(request.question());
        if (decision.mode() == RouteMode.SINGLE_AGENT) {
            return consultationAgent.answer(request).answer();
        }
        List<CompletableFuture<AgentResult>> futures = List.of(
                CompletableFuture.supplyAsync(() -> consultationAgent.answer(request)),
                CompletableFuture.supplyAsync(() -> diagnosticAgent.answer(request)),
                CompletableFuture.supplyAsync(() -> researchAgent.answer(request))
        );
        List<AgentResult> results = new ArrayList<>();
        for (CompletableFuture<AgentResult> future : futures) {
            results.add(future.join());
        }
        return leadAgent.synthesize(request.question(), results);
    }
}
