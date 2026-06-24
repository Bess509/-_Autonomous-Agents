package com.medix.agent;

import org.springframework.stereotype.Component;

@Component
public class DiagnosticAgent implements MedicalAgent {
    private final AgentLoopEngine loop;

    public DiagnosticAgent(AgentLoopEngine loop) {
        this.loop = loop;
    }

    @Override
    public String agentId() {
        return "diagnostic_agent";
    }

    @Override
    public AgentResult answer(AgentRequest request) {
        return loop.run(agentId(), request);
    }
}
