package com.medix.agent;

import com.medix.agentscope.AgentScopeRuntimeAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Strangler switch. Legacy remains a configuration-only rollback path. */
@Component
public class AgentRuntimeSelector implements AgentRuntimePort {
    private final String engine;
    private final AgentLoopEngine legacy;
    private final AgentScopeRuntimeAdapter agentScope;

    public AgentRuntimeSelector(@Value("${medix.agent.engine:agentscope}") String engine,
                                AgentLoopEngine legacy, AgentScopeRuntimeAdapter agentScope) {
        this.engine = engine;
        this.legacy = legacy;
        this.agentScope = agentScope;
    }

    @Override public AgentResult run(String agentId, AgentRequest request) {
        return switch (engine) {
            case "legacy" -> legacy.run(agentId, request);
            case "agentscope" -> agentScope.run(agentId, request);
            case "shadow" -> legacy.run(agentId, request); // no second execution: tools may have side effects
            default -> throw new IllegalStateException("Unknown medix.agent.engine: " + engine);
        };
    }
}
