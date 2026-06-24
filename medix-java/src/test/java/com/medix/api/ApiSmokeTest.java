package com.medix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.ai.openai.api-key=test",
        "medix.features.redis=false",
        "medix.features.reranker=false",
        "medix.features.minio=false"
})
class ApiSmokeTest {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void chatEndpointReturnsSwarmAnswer() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "sessionId": "api-test",
                          "question": "胸痛 呼吸困难 高血压 指南",
                          "context": {"age": 52}
                        }
                        """))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"routeMode\":\"SWARM\"");
        assertThat(response.body()).contains("\"primaryAgent\":\"lead_agent\"");
        assertThat(response.body()).contains("\"answer\"");
    }

    @Test
    void skillsEndpointExposesProgressiveDisclosure() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/skills")).GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("progressiveDisclosure");
        assertThat(response.body()).contains("search_knowledge");
        assertThat(response.body()).contains("assess_risk");
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
