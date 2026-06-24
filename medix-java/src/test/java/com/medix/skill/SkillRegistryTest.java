package com.medix.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillRegistryTest {
    @Test
    void registersAndInvokesSkillByName() {
        MedicalSkill skill = new MedicalSkill() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "Echo skill";
            }

            @Override
            public SkillResult invoke(SkillRequest request) {
                return SkillResult.success(name(), "echo:" + request.query(), Map.of("source", "test"));
            }
        };

        SkillRegistry registry = new SkillRegistry(List.of(skill));
        SkillResult result = registry.invoke("echo", new SkillRequest("hello", "s1", Map.of()));

        assertThat(registry.metadata()).containsEntry("echo", "Echo skill");
        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("echo:hello");
        assertThat(result.metadata()).containsEntry("source", "test");
    }

    @Test
    void returnsFailureForUnknownSkill() {
        SkillRegistry registry = new SkillRegistry(List.of());
        SkillResult result = registry.invoke("missing", new SkillRequest("hello", "s1", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("Skill not found");
    }
}
