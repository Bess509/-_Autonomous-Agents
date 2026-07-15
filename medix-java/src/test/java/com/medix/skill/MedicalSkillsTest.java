package com.medix.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MedicalSkillsTest {
    private final SkillRequest request = new SkillRequest("胸痛 呼吸困难 高血压", "session-1", Map.of());

    @Test
    void allSevenSkillsReturnUsefulContent() {
        List<MedicalSkill> skills = List.of(
                new SearchKnowledgeSkill(),
                new AssessRiskSkill(),
                new AnalyzeSymptomsSkill(),
                new RecommendLifestyleSkill(),
                new Icd10CodeSkill(),
                new ClinicalGuidelineSkill(),
                new DeepResearchSkill()
        );

        assertThat(skills).hasSize(7);
        for (MedicalSkill skill : skills) {
            SkillResult result = skill.invoke(request);
            assertThat(result.success()).as(skill.name()).isTrue();
            assertThat(result.content()).as(skill.name()).isNotBlank();
            assertThat(result.skillName()).isEqualTo(skill.name());
        }
    }

    @Test
    void riskSkillEscalatesChestPainAndBreathingDifficulty() {
        SkillResult result = new AssessRiskSkill().invoke(request);

        assertThat(result.content()).contains("高危");
        assertThat(result.content()).contains("立即就医");
        assertThat(result.metadata()).containsEntry("riskLevel", "HIGH");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "没有剧烈头痛，只是有点轻微不适",
            "否认胸痛，目前没有呼吸困难",
            "未出现昏厥，只是轻微头晕"
    })
    void riskSkillDoesNotEscalateLocallyNegatedSignals(String query) {
        SkillResult result = new AssessRiskSkill().invoke(new SkillRequest(query, "negated", Map.of()));

        assertThat(result.content()).contains("中低风险").doesNotContain("立即就医", "120");
        assertThat(result.metadata()).containsEntry("riskLevel", "LOW_OR_MEDIUM");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "突发剧烈头痛",
            "没有胸痛，但现在呼吸困难",
            "没有剧烈头痛，不过现在是最严重的头痛"
    })
    void riskSkillEscalatesAffirmedSignalsAfterEarlierNegation(String query) {
        SkillResult result = new AssessRiskSkill().invoke(new SkillRequest(query, "affirmed", Map.of()));

        assertThat(result.content()).contains("高危", "立即就医", "120");
        assertThat(result.metadata()).containsEntry("riskLevel", "HIGH");
    }
}
