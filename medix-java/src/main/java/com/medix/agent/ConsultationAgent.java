package com.medix.agent;

import org.springframework.stereotype.Component;

@Component
public class ConsultationAgent implements MedicalAgent {
    private final AgentLoopEngine loop;

    public ConsultationAgent(AgentLoopEngine loop) {
        this.loop = loop;
    }

    @Override
    public String agentId() {
        return "consultation_agent";
    }

    @Override
    public AgentResult answer(AgentRequest request) {
        return loop.run(agentId(), request);
    }
}
