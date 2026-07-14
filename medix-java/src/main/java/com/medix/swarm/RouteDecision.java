package com.medix.swarm;

import java.util.List;
import java.util.Map;

public record RouteDecision(
        RouteMode mode,
        String primaryAgent,
        List<String> requiredAgents,
        String reason,
        boolean requiresLeadAgent,
        Map<String, Double> probabilities
) {
    public RouteDecision(RouteMode mode, String primaryAgent, List<String> requiredAgents, String reason) {
        this(mode, primaryAgent, requiredAgents, reason, "lead_agent".equals(primaryAgent), Map.of());
    }

    public RouteDecision {
        requiredAgents = List.copyOf(requiredAgents);
        probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
    }
}
