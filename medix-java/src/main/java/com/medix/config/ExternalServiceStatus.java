package com.medix.config;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ExternalServiceStatus {
    private final MedixProperties properties;

    public ExternalServiceStatus(MedixProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "redisConfigured", properties.features().redis(),
                "minioConfigured", properties.features().minio(),
                "rerankerConfigured", properties.features().reranker(),
                "liveLlmConfigured", properties.features().liveLlm()
        );
    }
}
