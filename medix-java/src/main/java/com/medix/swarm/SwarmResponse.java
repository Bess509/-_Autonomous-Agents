package com.medix.swarm;

import com.medix.agent.AgentResult;
import java.util.List;
import java.util.Map;

public record SwarmResponse(
        RouteDecision decision,
        String answer,
        List<AgentResult> agentResults,
        Map<String, String> sharedContext
) {
}
