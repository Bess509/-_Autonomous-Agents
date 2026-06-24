package com.medix.memory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medix.memory")
public record MemoryProperties(Duration redisContextTtl, Entropy entropy) {
    public MemoryProperties {
        if (redisContextTtl == null) {
            redisContextTtl = Duration.ofHours(2);
        }
        if (entropy == null) {
            entropy = Entropy.defaults();
        }
    }

    public MemoryProperties() {
        this(Duration.ofHours(2), Entropy.defaults());
    }

    public record Entropy(
            boolean enabled,
            int recentMessageLimit,
            int mediumMessageThreshold,
            int highMessageThreshold,
            double duplicateRateThreshold,
            int averageLengthThreshold
    ) {
        public Entropy {
            if (recentMessageLimit <= 0) {
                recentMessageLimit = 10;
            }
            if (mediumMessageThreshold <= 0) {
                mediumMessageThreshold = 20;
            }
            if (highMessageThreshold <= 0) {
                highMessageThreshold = 50;
            }
            if (duplicateRateThreshold <= 0.0) {
                duplicateRateThreshold = 0.2;
            }
            if (averageLengthThreshold <= 0) {
                averageLengthThreshold = 1000;
            }
        }

        public static Entropy defaults() {
            return new Entropy(true, 10, 20, 50, 0.2, 1000);
        }
    }
}
