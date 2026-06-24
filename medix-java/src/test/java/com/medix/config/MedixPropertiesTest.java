package com.medix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MedixPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues(
                    "medix.agent.max-iterations=7",
                    "medix.agent.max-skill-calls=4",
                    "medix.agent.single-agent-timeout=11s",
                    "medix.agent.swarm-timeout=25s",
                    "medix.features.live-llm=false",
                    "medix.features.redis=false",
                    "medix.features.minio=false",
                    "medix.features.reranker=true",
                    "medix.services.reranker-url=http://localhost:8081/rerank",
                    "medix.services.minio-endpoint=http://localhost:9000",
                    "medix.services.minio-access-key=minioadmin",
                    "medix.services.minio-secret-key=minioadmin123"
            );

    @Test
    void bindsMedixProperties() {
        runner.run(context -> {
            MedixProperties properties = context.getBean(MedixProperties.class);

            assertThat(properties.agent().maxIterations()).isEqualTo(7);
            assertThat(properties.agent().maxSkillCalls()).isEqualTo(4);
            assertThat(properties.agent().singleAgentTimeout()).isEqualTo(Duration.ofSeconds(11));
            assertThat(properties.agent().swarmTimeout()).isEqualTo(Duration.ofSeconds(25));
            assertThat(properties.features().redis()).isFalse();
            assertThat(properties.services().rerankerUrl()).isEqualTo("http://localhost:8081/rerank");
        });
    }

    @EnableConfigurationProperties(MedixProperties.class)
    static class Config {
    }
}
