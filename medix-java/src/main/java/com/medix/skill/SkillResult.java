package com.medix.skill;

import java.util.Map;

public record SkillResult(boolean success, String skillName, String content, Map<String, Object> metadata) {
    public static SkillResult success(String skillName, String content, Map<String, Object> metadata) {
        return new SkillResult(true, skillName, content, metadata);
    }

    public static SkillResult failure(String skillName, String message) {
        return new SkillResult(false, skillName, message, Map.of());
    }
}
