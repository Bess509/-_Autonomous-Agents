package com.medix.skill;

import java.util.Map;

public record SkillRequest(String query, String sessionId, Map<String, Object> context) {
}
