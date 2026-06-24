package com.medix.swarm;

import java.util.List;

public record RouteDecision(RouteMode mode, String primaryAgent, List<String> requiredAgents, String reason) {
}
