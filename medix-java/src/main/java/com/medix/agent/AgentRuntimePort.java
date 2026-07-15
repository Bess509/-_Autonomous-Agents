package com.medix.agent;

public interface AgentRuntimePort {
    AgentResult run(String agentId, AgentRequest request);
}
