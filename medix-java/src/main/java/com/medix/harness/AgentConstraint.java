package com.medix.harness;

import java.util.List;

public record AgentConstraint(String agentId, List<String> allowedSkills, List<String> forbiddenActions) {
}
