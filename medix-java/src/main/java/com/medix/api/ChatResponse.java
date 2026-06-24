package com.medix.api;

import com.medix.agent.AgentResult;
import com.medix.memory.ConversationSummary;
import com.medix.swarm.RouteMode;
import java.util.List;
import java.util.Map;

public record ChatResponse(
        String sessionId,
        RouteMode routeMode,
        String primaryAgent,
        List<String> participatingAgents,
        String answer,
        long latencyMs,
        List<AgentResult> agentResults,
        List<ConversationSummary> similarCases,
        Map<String, String> sharedContext
) {
}
