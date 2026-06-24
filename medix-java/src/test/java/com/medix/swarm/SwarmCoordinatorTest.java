package com.medix.swarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.medix.agent.AgentLoopEngine;
import com.medix.agent.AgentRequest;
import com.medix.agent.ConsultationAgent;
import com.medix.agent.DiagnosticAgent;
import com.medix.agent.LeadAgent;
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

        assertThat(answer).contains("综合问题");
        assertThat(answer).contains("免责声明");
    }
}
