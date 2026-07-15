package com.medix.agent;

import org.springframework.stereotype.Component;

@Component
public class ResearchAgent implements MedicalAgent {
    private final AgentRuntimePort runtime;

    public ResearchAgent(@org.springframework.beans.factory.annotation.Qualifier("agentRuntimeSelector") AgentRuntimePort runtime) { this.runtime = runtime; }

    @Override
    public String agentId() {
        return "research_agent";
    }

    @Override
    public AgentResult answer(AgentRequest request) {
        return runtime.run(agentId(), request);
    }
}
