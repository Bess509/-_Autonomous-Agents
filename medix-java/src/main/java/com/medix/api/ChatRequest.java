package com.medix.api;

import java.util.Map;

public record ChatRequest(String sessionId, String question, Map<String, Object> context) {
}
