package com.medix.nlu;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medix.nlu")
public record NluProperties(
        boolean enabled,
        String baseUrl,
        String model,
        Duration timeout,
        double confidenceThreshold,
        double labelThreshold,
        double ambiguityMargin,
        double riskThreshold
) {
    public NluProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl;
        model = model == null || model.isBlank() ? "qwen2.5:1.5b" : model;
        timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        confidenceThreshold = validProbability(confidenceThreshold) ? confidenceThreshold : 0.70;
        labelThreshold = validProbability(labelThreshold) ? labelThreshold : 0.55;
        ambiguityMargin = validProbability(ambiguityMargin) ? ambiguityMargin : 0.10;
        riskThreshold = validProbability(riskThreshold) ? riskThreshold : 0.30;
    }

    private static boolean validProbability(double value) {
        return value > 0.0 && value <= 1.0;
    }

    public static NluProperties disabled() {
        return new NluProperties(false, "http://localhost:11434", "qwen2.5:1.5b",
                Duration.ofSeconds(3), 0.70, 0.55, 0.10, 0.30);
    }
}
