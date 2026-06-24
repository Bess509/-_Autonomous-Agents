package com.medix.agent;

public interface MedicalAgent {
    String agentId();

    AgentResult answer(AgentRequest request);
}
