package com.medix.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.medix.swarm.SwarmSubtask;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LeadAgentTest {
    @Test
    void parsesLlmJsonIntoAssignedSubtasks() {
        LeadAgent leadAgent = new LeadAgent((agentId, prompt, skillMetadata) -> """
                {
                  "subtasks": [
                    {
                      "description": "评估胸痛和呼吸困难的风险等级",
                      "assigned_agent": "diagnostic_agent"
                    },
                    {
                      "description": "检索高血压相关临床指南证据",
                      "assigned_agent": "research_agent"
                    }
                  ]
                }
                """);

        List<SwarmSubtask> subtasks = leadAgent.assessAndDecompose(
                "胸痛、呼吸困难，并想了解高血压指南",
                Map.of("age", 52)
        );

        assertThat(subtasks).hasSize(2);
        assertThat(subtasks).extracting(SwarmSubtask::assignedAgent)
                .containsExactly("diagnostic_agent", "research_agent");
        assertThat(subtasks).extracting(SwarmSubtask::description)
                .containsExactly("评估胸痛和呼吸困难的风险等级", "检索高血压相关临床指南证据");
    }

    @Test
    void fallsBackToConsultationSubtaskWhenLlmOutputCannotBeParsed() {
        LeadAgent leadAgent = new LeadAgent((agentId, prompt, skillMetadata) -> "not json");

        List<SwarmSubtask> subtasks = leadAgent.assessAndDecompose("多喝水有什么好处？", Map.of());

        assertThat(subtasks).hasSize(1);
        assertThat(subtasks.getFirst().assignedAgent()).isEqualTo("consultation_agent");
        assertThat(subtasks.getFirst().description()).contains("回答用户问题");
    }
}
