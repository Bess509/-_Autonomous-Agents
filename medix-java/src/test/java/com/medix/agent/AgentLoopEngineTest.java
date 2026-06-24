package com.medix.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.medix.harness.OutputRepairService;
import com.medix.memory.MessageWindowReducer;
import com.medix.memory.ShortTermMemory;
import com.medix.skill.AssessRiskSkill;
import com.medix.skill.MedicalSkill;
import com.medix.skill.SkillRegistry;
import com.medix.skill.SkillRequest;
import com.medix.skill.SkillResult;
import java.util.ArrayList;
import java.util.Arrays;
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
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.skillCalls()).containsExactly("assess_risk");
    }

    @Test
    void reactLoopContinuesAfterObservationUntilFinalAnswer() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new StaticSkill("search_knowledge", "检索医学知识", "知识库观察：高血压需要低盐管理。"),
                new StaticSkill("recommend_lifestyle", "推荐生活方式", "生活方式观察：低盐饮食和规律运动。")
        ));
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        ScriptedModelGateway modelGateway = new ScriptedModelGateway(
                "CALL_SKILL:search_knowledge",
                "CALL_SKILL:recommend_lifestyle",
                "FINAL:已结合知识库和生活方式建议。"
        );
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 5, 3);

        AgentResult result = loop.run("consultation_agent", new AgentRequest("我有高血压，应该怎么办", "s-react", Map.of()));

        assertThat(result.skillCalls()).containsExactly("search_knowledge", "recommend_lifestyle");
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(modelGateway.prompts()).hasSize(3);
        assertThat(modelGateway.prompts().get(1)).contains("知识库观察：高血压需要低盐管理。");
        assertThat(modelGateway.prompts().get(2)).contains("生活方式观察：低盐饮食和规律运动。");
        assertThat(result.answer()).contains("已结合知识库和生活方式建议。");
        assertThat(result.answer()).contains("免责声明");
    }

    @Test
    void reactLoopFallsBackWhenSkillCallLimitIsReached() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new StaticSkill("search_knowledge", "检索医学知识", "知识库观察：高血压需要低盐管理。")
        ));
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        ScriptedModelGateway modelGateway = new ScriptedModelGateway(
                "CALL_SKILL:search_knowledge",
                "CALL_SKILL:search_knowledge"
        );
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 5, 1);

        AgentResult result = loop.run("consultation_agent", new AgentRequest("高血压生活方式建议", "s-skill-limit", Map.of()));

        assertThat(result.skillCalls()).containsExactly("search_knowledge");
        assertThat(result.answer()).contains("已达到工具调用上限");
        assertThat(result.answer()).contains("知识库观察：高血压需要低盐管理。");
        assertThat(result.answer()).contains("免责声明");
    }

    @Test
    void reactLoopFallsBackWhenIterationLimitIsReached() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new StaticSkill("search_knowledge", "检索医学知识", "知识库观察：高血压需要低盐管理。")
        ));
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        ScriptedModelGateway modelGateway = new ScriptedModelGateway(
                "CALL_SKILL:search_knowledge",
                "CALL_SKILL:search_knowledge"
        );
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 2, 5);

        AgentResult result = loop.run("research_agent", new AgentRequest("高血压指南证据", "s-iteration-limit", Map.of()));

        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.answer()).contains("已达到最大推理轮次");
        assertThat(result.answer()).contains("知识库观察：高血压需要低盐管理。");
        assertThat(result.answer()).contains("免责声明");
    }

    private static class ScriptedModelGateway implements ModelGateway {
        private final List<String> decisions;
        private final List<String> prompts = new ArrayList<>();
        private int callIndex;

        ScriptedModelGateway(String... decisions) {
            this.decisions = Arrays.asList(decisions);
        }

        @Override
        public String complete(String agentId, String userPrompt, Map<String, String> skillMetadata) {
            prompts.add(userPrompt);
            if (callIndex < decisions.size()) {
                return decisions.get(callIndex++);
            }
            return decisions.get(decisions.size() - 1);
        }

        List<String> prompts() {
            return prompts;
        }
    }

    private record StaticSkill(String name, String description, String content) implements MedicalSkill {
        @Override
        public SkillResult invoke(SkillRequest request) {
            return SkillResult.success(name, content, Map.of("source", "test"));
        }
    }
}
