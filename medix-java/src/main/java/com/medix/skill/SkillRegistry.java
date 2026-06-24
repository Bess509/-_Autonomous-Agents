package com.medix.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SkillRegistry {
    private final Map<String, MedicalSkill> skills;

    public SkillRegistry(List<MedicalSkill> skills) {
        this.skills = new LinkedHashMap<>();
        for (MedicalSkill skill : skills) {
            this.skills.put(skill.name(), skill);
        }
    }

    public Map<String, String> metadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        skills.forEach((name, skill) -> metadata.put(name, skill.description()));
        return metadata;
    }

    public SkillResult invoke(String name, SkillRequest request) {
        MedicalSkill skill = skills.get(name);
        if (skill == null) {
            return SkillResult.failure(name, "Skill not found: " + name);
        }
        return skill.invoke(request);
    }
}
