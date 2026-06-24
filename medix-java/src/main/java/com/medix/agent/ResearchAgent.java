package com.medix.agent;

import org.springframework.stereotype.Component;

@Component
public class ResearchAgent implements MedicalAgent {
    private final AgentLoopEngine loop;

    public ResearchAgent(AgentLoopEngine loop) {
        this.loop = loop;
    }

    @Override
    public String agentId() {
        return "research_agent";
    }

    @Override
    public AgentResult answer(AgentRequest request) {
        return loop.run(agentId(), request);
    }
}
