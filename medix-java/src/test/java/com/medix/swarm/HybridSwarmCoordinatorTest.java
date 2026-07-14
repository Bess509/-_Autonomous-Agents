package com.medix.swarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medix.agent.AgentRequest;
import com.medix.agent.AgentResult;
import com.medix.agent.ConsultationAgent;
import com.medix.agent.DiagnosticAgent;
import com.medix.agent.LeadAgent;
import com.medix.agent.ResearchAgent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HybridSwarmCoordinatorTest {
    @Test
    void directRouteBypassesLeadAgent() {
        SwarmRouter router = mock(SwarmRouter.class);
        when(router.route("睡眠建议")).thenReturn(new RouteDecision(RouteMode.SINGLE_AGENT, "consultation_agent",
                List.of("consultation_agent"), "nlu_high_confidence_single", false, Map.of("HEALTH_CONSULTATION", 0.91)));
        LeadAgent lead = mock(LeadAgent.class);
        ConsultationAgent consultation = mock(ConsultationAgent.class);
        when(consultation.agentId()).thenReturn("consultation_agent");
        when(consultation.answer(any())).thenReturn(new AgentResult("consultation_agent", "direct answer", 1, List.of()));
        DiagnosticAgent diagnostic = mock(DiagnosticAgent.class);
        when(diagnostic.agentId()).thenReturn("diagnostic_agent");
        ResearchAgent research = mock(ResearchAgent.class);
        when(research.agentId()).thenReturn("research_agent");
        SwarmCoordinator coordinator = new SwarmCoordinator(router, consultation, diagnostic, research, lead, new SharedContextStore());

        SwarmResponse response = coordinator.processDetailed(new AgentRequest("睡眠建议", "direct", Map.of()));

        assertThat(response.answer()).isEqualTo("direct answer");
        assertThat(response.sharedContext()).containsEntry("lead_agent.status", "bypassed")
                .containsEntry("nlu.probability.HEALTH_CONSULTATION", "0.91");
        verify(lead, never()).assessAndDecompose(any(), any());
    }

    @Test
    void fallbackInvokesLeadAgent() {
        SwarmRouter router = mock(SwarmRouter.class);
        when(router.route("含糊问题")).thenReturn(new RouteDecision(RouteMode.SWARM, "lead_agent",
                List.of("consultation_agent"), "nlu_unavailable", true, Map.of()));
        LeadAgent lead = mock(LeadAgent.class);
        when(lead.assessAndDecompose(any(), any())).thenReturn(List.of(new SwarmSubtask("1", "clarified", "consultation_agent")));
        ConsultationAgent consultation = mock(ConsultationAgent.class);
        when(consultation.agentId()).thenReturn("consultation_agent");
        when(consultation.answer(any())).thenReturn(new AgentResult("consultation_agent", "fallback answer", 1, List.of()));
        DiagnosticAgent diagnostic = mock(DiagnosticAgent.class);
        when(diagnostic.agentId()).thenReturn("diagnostic_agent");
        ResearchAgent research = mock(ResearchAgent.class);
        when(research.agentId()).thenReturn("research_agent");
        SwarmCoordinator coordinator = new SwarmCoordinator(router, consultation, diagnostic, research, lead, new SharedContextStore());

        SwarmResponse response = coordinator.processDetailed(new AgentRequest("含糊问题", "fallback", Map.of()));

        assertThat(response.answer()).isEqualTo("fallback answer");
        assertThat(response.sharedContext()).containsEntry("nlu.status", "unavailable");
        verify(lead).assessAndDecompose(any(), any());
    }

    @Test
    void emergencyRouteBypassesLeadAndRunsBothSafetyWorkers() {
        SwarmRouter router = mock(SwarmRouter.class);
        when(router.route("突发胸痛和呼吸困难")).thenReturn(new RouteDecision(RouteMode.SWARM, "diagnostic_agent",
                List.of("diagnostic_agent", "consultation_agent"), "emergency_rule", false, Map.of()));
        LeadAgent lead = mock(LeadAgent.class);
        ConsultationAgent consultation = mock(ConsultationAgent.class);
        when(consultation.agentId()).thenReturn("consultation_agent");
        when(consultation.answer(any())).thenReturn(new AgentResult("consultation_agent", "safety advice", 1, List.of()));
        DiagnosticAgent diagnostic = mock(DiagnosticAgent.class);
        when(diagnostic.agentId()).thenReturn("diagnostic_agent");
        when(diagnostic.answer(any())).thenReturn(new AgentResult("diagnostic_agent", "risk assessment", 1, List.of()));
        ResearchAgent research = mock(ResearchAgent.class);
        when(research.agentId()).thenReturn("research_agent");
        SwarmCoordinator coordinator = new SwarmCoordinator(router, consultation, diagnostic, research, lead, new SharedContextStore());

        SwarmResponse response = coordinator.processDetailed(new AgentRequest("突发胸痛和呼吸困难", "emergency", Map.of()));

        assertThat(response.agentResults()).extracting(AgentResult::agentId)
                .containsExactlyInAnyOrder("diagnostic_agent", "consultation_agent");
        assertThat(response.sharedContext()).containsEntry("nlu.status", "skipped_emergency")
                .containsEntry("lead_agent.status", "bypassed");
        verify(lead, never()).assessAndDecompose(any(), any());
    }
}
