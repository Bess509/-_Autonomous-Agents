package com.medix.agent;

import java.util.Map;

public record AgentRequest(String question, String sessionId, Map<String, Object> context) {
}
