package com.medix.swarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.medix.agent.AgentLoopEngine;
import com.medix.agent.AgentDelegationRequest;
import com.medix.agent.AgentRequest;
import com.medix.agent.AgentResult;
import com.medix.agent.ConsultationAgent;
import com.medix.agent.DiagnosticAgent;
import com.medix.agent.LeadAgent;
import com.medix.agent.MedicalAgent;
import com.medix.agent.ResearchAgent;
import com.medix.harness.OutputRepairService;
import com.medix.memory.MessageWindowReducer;
import com.medix.memory.ShortTermMemory;
import com.medix.skill.AssessRiskSkill;
import com.medix.skill.SkillRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmCoordinatorTest {
    @Test
    void processesComplexQuestionWithMultipleAgents() {
        SkillRegistry registry = new SkillRegistry(List.of(new AssessRiskSkill()));
        AgentLoopEngine loop = new AgentLoopEngine(registry, new ShortTermMemory(new MessageWindowReducer()), new OutputRepairService(), 5, 3);
        SwarmCoordinator coordinator = new SwarmCoordinator(
                new SwarmRouter(),
                new ConsultationAgent(loop),
                new DiagnosticAgent(loop),
                new ResearchAgent(loop),
                new LeadAgent()
        );

        String answer = coordinator.process(new AgentRequest("胸痛 呼吸困难 高血压 指南", "s1", Map.of()));

        assertThat(answer).contains("高危信号", "120");
        assertThat(answer).contains("免责声明");
    }

    @Test
    void routesSingleLeadSubtaskToAssignedWorker() {
        LeadAgent leadAgent = new LeadAgent((agentId, prompt, skillMetadata) -> """
                {
                  "subtasks": [
                    {
                      "description": "评估胸痛风险",
                      "assigned_agent": "diagnostic_agent"
                    }
                  ]
                }
                """);
        SharedContextStore sharedContextStore = new SharedContextStore();
        SwarmCoordinator coordinator = new SwarmCoordinator(
                new SwarmRouter(),
                new CapturingConsultationAgent("consultation_agent"),
                new CapturingDiagnosticAgent("diagnostic_agent"),
                new CapturingResearchAgent("research_agent"),
                leadAgent,
                sharedContextStore
        );

        SwarmResponse response = coordinator.processDetailed(new AgentRequest("最近经常头晕怎么办", "single-subtask", Map.of()));

        assertThat(response.decision().mode()).isEqualTo(RouteMode.SINGLE_AGENT);
        assertThat(response.decision().primaryAgent()).isEqualTo("diagnostic_agent");
        assertThat(response.agentResults()).extracting(AgentResult::agentId).containsExactly("diagnostic_agent");
        assertThat(response.answer()).contains("证据摘要", "综合建议")
                .doesNotContain("diagnostic_agent handled");
        assertThat(response.sharedContext()).containsEntry("subtask.1.assignedAgent", "diagnostic_agent");
        assertThat(response.sharedContext()).containsEntry("subtask.1.status", "completed");
    }

    @Test
    void routesMultipleLeadSubtasksThroughSwarmAndSharedContext() {
        LeadAgent leadAgent = new LeadAgent((agentId, prompt, skillMetadata) -> """
                {
                  "subtasks": [
                    {
                      "description": "提供高血压生活方式建议",
                      "assigned_agent": "consultation_agent"
                    },
                    {
                      "description": "检索高血压指南证据",
                      "assigned_agent": "research_agent"
                    }
                  ]
                }
                """);
        SharedContextStore sharedContextStore = new SharedContextStore();
        SwarmCoordinator coordinator = new SwarmCoordinator(
                new SwarmRouter(),
                new CapturingConsultationAgent("consultation_agent"),
                new CapturingDiagnosticAgent("diagnostic_agent"),
                new CapturingResearchAgent("research_agent"),
                leadAgent,
                sharedContextStore
        );

        SwarmResponse response = coordinator.processDetailed(new AgentRequest("高血压怎么管理，需要指南吗？", "multi-subtask", Map.of()));

        assertThat(response.decision().mode()).isEqualTo(RouteMode.SWARM);
        assertThat(response.decision().requiredAgents()).containsExactly("consultation_agent", "research_agent");
        assertThat(response.agentResults()).extracting(AgentResult::agentId)
                .containsExactlyInAnyOrder("consultation_agent", "research_agent");
        assertThat(response.answer()).contains("证据摘要", "综合建议")
                .doesNotContain("consultation_agent handled", "research_agent handled");
        assertThat(response.sharedContext()).containsEntry("response.synthesizerInvocations", "1");
        assertThat(response.sharedContext()).containsEntry("subtask.1.status", "completed");
        assertThat(response.sharedContext()).containsEntry("subtask.2.status", "completed");
        assertThat(response.sharedContext()).containsEntry("contribution.1.agent", "consultation_agent");
        assertThat(response.sharedContext()).containsEntry("contribution.2.agent", "research_agent");
    }

    @Test
    void executesDelegatedResearchSubtaskInsteadOfFailingSwarm() {
        LeadAgent leadAgent = new LeadAgent((agentId, prompt, skillMetadata) -> """
                {"subtasks":[{"description":"assess chest pain risk","assigned_agent":"diagnostic_agent"}]}
                """);
        SharedContextStore sharedContextStore = new SharedContextStore();
        SwarmCoordinator coordinator = new SwarmCoordinator(
                new SwarmRouter(),
                new CapturingConsultationAgent("consultation_agent"),
                new DelegatingDiagnosticAgent(),
                new CapturingResearchAgent("research_agent"),
                leadAgent,
                sharedContextStore
        );

        SwarmResponse response = coordinator.processDetailed(new AgentRequest(
                "chest pain and breathing difficulty needs guideline evidence",
                "delegated-swarm",
                Map.of()
        ));

        assertThat(response.answer()).contains("证据摘要").doesNotContain("research_agent handled");
        assertThat(response.agentResults()).extracting(AgentResult::agentId).contains("research_agent");
        assertThat(response.sharedContext()).containsEntry("subtask.1.status", "delegated");
        assertThat(response.sharedContext()).containsValue("research_agent");
    }

    @Test
    void returnsPartialSwarmAnswerWhenOneWorkerFails() {
        LeadAgent leadAgent = new LeadAgent((agentId, prompt, skillMetadata) -> """
                {"subtasks":[
                  {"description":"provide lifestyle advice","assigned_agent":"consultation_agent"},
                  {"description":"perform deep research","assigned_agent":"research_agent"}
                ]}
                """);
        SharedContextStore sharedContextStore = new SharedContextStore();
        SwarmCoordinator coordinator = new SwarmCoordinator(
                new SwarmRouter(),
                new CapturingConsultationAgent("consultation_agent"),
                new CapturingDiagnosticAgent("diagnostic_agent"),
                new FailingResearchAgent(),
                leadAgent,
                sharedContextStore
        );

        SwarmResponse response = coordinator.processDetailed(new AgentRequest("hypertension management and evidence", "partial-failure", Map.of()));

        assertThat(response.answer()).contains("证据摘要", "综合建议")
                .doesNotContain("consultation_agent handled", "research_agent 暂时无法完成");
        assertThat(response.sharedContext()).containsEntry("subtask.2.status", "failed");
    }

    private static class CapturingConsultationAgent extends ConsultationAgent {
        private final String agentId;

        CapturingConsultationAgent(String agentId) {
            super(null);
            this.agentId = agentId;
        }

        @Override
        public String agentId() {
            return agentId;
        }

        @Override
        public AgentResult answer(AgentRequest request) {
            return result(agentId, request);
        }
    }

    private static class CapturingDiagnosticAgent extends DiagnosticAgent {
        private final String agentId;

        CapturingDiagnosticAgent(String agentId) {
            super(null);
            this.agentId = agentId;
        }

        @Override
        public String agentId() {
            return agentId;
        }

        @Override
        public AgentResult answer(AgentRequest request) {
            return result(agentId, request);
        }
    }

    private static class CapturingResearchAgent extends ResearchAgent {
        private final String agentId;

        CapturingResearchAgent(String agentId) {
            super(null);
            this.agentId = agentId;
        }

        @Override
        public String agentId() {
            return agentId;
        }

        @Override
        public AgentResult answer(AgentRequest request) {
            return result(agentId, request);
        }
    }

    private static class DelegatingDiagnosticAgent extends DiagnosticAgent {
        DelegatingDiagnosticAgent() {
            super(null);
        }

        @Override
        public AgentResult answer(AgentRequest request) {
            throw new AgentDelegationRequest("diagnostic_agent", "research_agent", "perform deep research");
        }
    }

    private static class FailingResearchAgent extends ResearchAgent {
        FailingResearchAgent() {
            super(null);
        }

        @Override
        public AgentResult answer(AgentRequest request) {
            throw new IllegalStateException("provider timeout");
        }
    }

    private static AgentResult result(String agentId, AgentRequest request) {
        return new AgentResult(agentId, agentId + " handled: " + request.question(), 1, List.of());
    }
}
