package com.medix.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.medix.harness.OutputRepairService;
import com.medix.memory.MessageWindowReducer;
import com.medix.memory.ShortTermMemory;
import com.medix.skill.AssessRiskSkill;
import com.medix.skill.SkillRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentLoopEngineTest {
    @Test
    void boundedLoopCallsRiskSkillAndRepairsOutput() {
        SkillRegistry registry = new SkillRegistry(List.of(new AssessRiskSkill()));
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), 5, 3);

        AgentResult result = loop.run("consultation_agent", new AgentRequest("胸痛和呼吸困难怎么办", "s1", Map.of()));

        assertThat(result.answer()).contains("高危");
        assertThat(result.answer()).contains("免责声明");
        assertThat(result.iterations()).isLessThanOrEqualTo(5);
        assertThat(result.skillCalls()).contains("assess_risk");
    }
}
