package com.medix.nlu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    @Test
    void classifiesValidHttp200ResponseBodyAsText() throws Exception {
        HttpServer server = serverReturning("""
                {"message":{"content":"{\\"probabilities\\":{\\"HEALTH_CONSULTATION\\":0.1,\\"SYMPTOM_ANALYSIS\\":0.9,\\"RISK_ASSESSMENT\\":0.8,\\"GUIDELINE_SEARCH\\":0.2,\\"DISEASE_CODE\\":0.05,\\"LIFESTYLE_ADVICE\\":0.1}}"}}
                """);
        try {
            OllamaNluClassifier httpClassifier = classifierFor(server);

            NluResult result = httpClassifier.classify("胸痛两小时危险吗");

            assertThat(result.probability(IntentLabel.SYMPTOM_ANALYSIS)).isEqualTo(0.9);
            assertThat(result.probabilities()).hasSize(6);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidOuterHttp200ResponseBody() throws Exception {
        HttpServer server = serverReturning("not-json");
        try {
            OllamaNluClassifier httpClassifier = classifierFor(server);

            assertThatThrownBy(() -> httpClassifier.classify("测试文本"))
                    .isInstanceOf(NluClassificationException.class);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer serverReturning(String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private OllamaNluClassifier classifierFor(HttpServer server) {
        return new OllamaNluClassifier(
                RestClient.builder(),
                new NluProperties(
                        true,
                        "http://localhost:" + server.getAddress().getPort(),
                        "test",
                        Duration.ofSeconds(2),
                        0.7,
                        0.55,
                        0.1,
                        0.3));
    }
}
