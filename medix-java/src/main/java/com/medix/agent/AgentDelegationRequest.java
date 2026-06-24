package com.medix.agent;

public class AgentDelegationRequest extends RuntimeException {
    private final String sourceAgent;
    private final String targetAgent;
    private final String task;

    public AgentDelegationRequest(String sourceAgent, String targetAgent, String task) {
        super("Agent " + sourceAgent + " delegated to " + targetAgent + ": " + task);
        this.sourceAgent = sourceAgent;
        this.targetAgent = targetAgent;
        this.task = task;
    }

    public String sourceAgent() {
        return sourceAgent;
    }

    public String targetAgent() {
        return targetAgent;
    }

    public String task() {
        return task;
    }
}
