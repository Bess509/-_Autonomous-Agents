package com.medix.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medix")
public record MedixProperties(Agent agent, Features features, Services services) {
    public record Agent(int maxIterations, int maxSkillCalls, Duration singleAgentTimeout, Duration swarmTimeout) {
    }

    public record Features(boolean liveLlm, boolean redis, boolean minio, boolean reranker) {
    }

    public record Services(String rerankerUrl, String minioEndpoint, String minioAccessKey, String minioSecretKey) {
    }
}
