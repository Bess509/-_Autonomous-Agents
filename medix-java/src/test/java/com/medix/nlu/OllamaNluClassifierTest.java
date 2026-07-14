package com.medix.nlu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OllamaNluClassifierTest {
    private final OllamaNluClassifier classifier = new OllamaNluClassifier(
            RestClient.builder(),
            new NluProperties(true, "http://localhost:11434", "test", Duration.ofSeconds(1), 0.7, 0.55, 0.1, 0.3));

    @Test
    void parsesExactlySixProbabilities() {
        NluResult result = classifier.parse("""
                {"probabilities":{"HEALTH_CONSULTATION":0.1,"SYMPTOM_ANALYSIS":0.9,"RISK_ASSESSMENT":0.8,"GUIDELINE_SEARCH":0.2,"DISEASE_CODE":0.05,"LIFESTYLE_ADVICE":0.1}}
                """);

        assertThat(result.probability(IntentLabel.SYMPTOM_ANALYSIS)).isEqualTo(0.9);
        assertThat(result.probabilities()).hasSize(6);
    }

    @Test
    void rejectsMissingOrUnknownLabels() {
        assertThatThrownBy(() -> classifier.parse("""
                {"probabilities":{"HEALTH_CONSULTATION":0.1}}
                """)).isInstanceOf(NluClassificationException.class);
    }

    @Test
    void rejectsOutOfRangeProbability() {
        assertThatThrownBy(() -> classifier.parse("""
                {"probabilities":{"HEALTH_CONSULTATION":0.1,"SYMPTOM_ANALYSIS":1.1,"RISK_ASSESSMENT":0.8,"GUIDELINE_SEARCH":0.2,"DISEASE_CODE":0.05,"LIFESTYLE_ADVICE":0.1}}
                """)).isInstanceOf(NluClassificationException.class);
    }
}
