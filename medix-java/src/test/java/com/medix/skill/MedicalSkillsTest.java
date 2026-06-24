package com.medix.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
}
