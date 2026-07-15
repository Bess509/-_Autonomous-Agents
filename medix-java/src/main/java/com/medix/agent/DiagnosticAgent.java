package com.medix.agent;

import org.springframework.stereotype.Component;

@Component
public class DiagnosticAgent implements MedicalAgent {
    private final AgentRuntimePort runtime;

    public DiagnosticAgent(@org.springframework.beans.factory.annotation.Qualifier("agentRuntimeSelector") AgentRuntimePort runtime) { this.runtime = runtime; }

    @Override
    public String agentId() {
        return "diagnostic_agent";
    }

    @Override
    public AgentResult answer(AgentRequest request) {
        return runtime.run(agentId(), request);
    }
}
