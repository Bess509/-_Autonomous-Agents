package com.medix.agent;

import java.util.List;

public record AgentResult(String agentId, String answer, int iterations, List<String> skillCalls) {
}
