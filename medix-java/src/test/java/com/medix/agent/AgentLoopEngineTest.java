package com.medix.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        AgentResult result = loop.run("consultation_agent", new AgentRequest("chest pain and breathing difficulty", "s1", Map.of()));

        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.skillCalls()).containsExactly("assess_risk");
        assertThat(result.answer()).doesNotContain("免责声明");
    }

    @Test
    void reactLoopContinuesAfterObservationUntilFinalAnswer() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new StaticSkill("search_knowledge", "knowledge", "knowledge observation"),
                new StaticSkill("recommend_lifestyle", "lifestyle", "lifestyle observation")
        ));
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        ScriptedModelGateway modelGateway = new ScriptedModelGateway(
                "CALL_SKILL:search_knowledge",
                "CALL_SKILL:recommend_lifestyle",
                "FINAL:combined answer"
        );
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 5, 3);

        AgentResult result = loop.run("consultation_agent", new AgentRequest("hypertension lifestyle advice", "s-react", Map.of()));

        assertThat(result.skillCalls()).containsExactly("search_knowledge", "recommend_lifestyle");
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(modelGateway.prompts()).hasSize(3);
        assertThat(modelGateway.prompts().get(1)).contains("knowledge observation");
        assertThat(modelGateway.prompts().get(2)).contains("lifestyle observation");
        assertThat(result.answer()).contains("combined answer");
        assertThat(result.answer()).doesNotContain("免责声明");
    }

    @Test
    void reactLoopFallsBackWhenSkillCallLimitIsReached() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new StaticSkill("search_knowledge", "knowledge", "knowledge observation")
        ));
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        ScriptedModelGateway modelGateway = new ScriptedModelGateway(
                "CALL_SKILL:search_knowledge",
                "CALL_SKILL:search_knowledge"
        );
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 5, 1);

        AgentResult result = loop.run("consultation_agent", new AgentRequest("lifestyle advice", "s-skill-limit", Map.of()));

        assertThat(result.skillCalls()).containsExactly("search_knowledge");
        assertThat(result.answer()).contains("knowledge observation");
        assertThat(result.answer()).doesNotContain("免责声明");
    }

    @Test
    void reactLoopFallsBackWhenIterationLimitIsReached() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new StaticSkill("clinical_guideline", "guideline", "guideline observation")
        ));
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        ScriptedModelGateway modelGateway = new ScriptedModelGateway(
                "CALL_SKILL:clinical_guideline",
                "CALL_SKILL:clinical_guideline"
        );
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 2, 5);

        AgentResult result = loop.run("research_agent", new AgentRequest("guideline evidence", "s-iteration-limit", Map.of()));

        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.answer()).contains("guideline observation");
        assertThat(result.answer()).doesNotContain("免责声明");
    }

    @Test
    void onlyExposesAllowedSkillsForDiagnosticAgent() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new StaticSkill("assess_risk", "risk", "risk result"),
                new StaticSkill("analyze_symptoms", "symptoms", "symptom result"),
                new StaticSkill("disease_code", "icd", "icd result"),
                new StaticSkill("deep_research", "research", "research result")
        ));
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        CapturingMetadataModelGateway modelGateway = new CapturingMetadataModelGateway("FINAL:done");
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 5, 3);

        loop.run("diagnostic_agent", new AgentRequest("chest pain and breathing difficulty", "visibility", Map.of()));

        assertThat(modelGateway.lastMetadata().keySet())
                .containsExactlyInAnyOrder("assess_risk", "analyze_symptoms", "disease_code");
    }

    @Test
    void parsesExplicitAgentDelegationRequest() {
        SkillRegistry registry = new SkillRegistry(List.of());
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        ScriptedModelGateway modelGateway = new ScriptedModelGateway(
                "DELEGATE_AGENT:research_agent:review chest pain guideline evidence"
        );
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 5, 3);

        assertThatThrownBy(() -> loop.run("diagnostic_agent", new AgentRequest("needs guideline evidence", "delegate", Map.of())))
                .isInstanceOf(AgentDelegationRequest.class)
                .satisfies(error -> {
                    AgentDelegationRequest request = (AgentDelegationRequest) error;
                    assertThat(request.sourceAgent()).isEqualTo("diagnostic_agent");
                    assertThat(request.targetAgent()).isEqualTo("research_agent");
                    assertThat(request.task()).contains("chest pain");
                });
    }

    @Test
    void mapsResearchSkillViolationToResearchDelegation() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new StaticSkill("deep_research", "research", "research result")
        ));
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        ScriptedModelGateway modelGateway = new ScriptedModelGateway("CALL_SKILL:deep_research");
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 5, 3);

        assertThatThrownBy(() -> loop.run("diagnostic_agent", new AgentRequest("needs deep research", "delegate-skill", Map.of())))
                .isInstanceOf(AgentDelegationRequest.class)
                .satisfies(error -> {
                    AgentDelegationRequest request = (AgentDelegationRequest) error;
                    assertThat(request.sourceAgent()).isEqualTo("diagnostic_agent");
                    assertThat(request.targetAgent()).isEqualTo("research_agent");
                    assertThat(request.task()).contains("deep_research");
                });
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

    private static class CapturingMetadataModelGateway extends ScriptedModelGateway {
        private Map<String, String> lastMetadata = Map.of();

        CapturingMetadataModelGateway(String... decisions) {
            super(decisions);
        }

        @Override
        public String complete(String agentId, String userPrompt, Map<String, String> skillMetadata) {
            lastMetadata = Map.copyOf(skillMetadata);
            return super.complete(agentId, userPrompt, skillMetadata);
        }

        Map<String, String> lastMetadata() {
            return lastMetadata;
        }
    }

    private record StaticSkill(String name, String description, String content) implements MedicalSkill {
        @Override
        public SkillResult invoke(SkillRequest request) {
            return SkillResult.success(name, content, Map.of("source", "test"));
        }
    }
}
