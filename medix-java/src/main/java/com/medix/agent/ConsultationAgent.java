package com.medix.agent;

import org.springframework.stereotype.Component;

@Component
public class ConsultationAgent implements MedicalAgent {
    private final AgentRuntimePort runtime;

    public ConsultationAgent(@org.springframework.beans.factory.annotation.Qualifier("agentRuntimeSelector") AgentRuntimePort runtime) { this.runtime = runtime; }

    @Override
    public String agentId() {
        return "consultation_agent";
    }

    @Override
    public AgentResult answer(AgentRequest request) {
        return runtime.run(agentId(), request);
    }
}
