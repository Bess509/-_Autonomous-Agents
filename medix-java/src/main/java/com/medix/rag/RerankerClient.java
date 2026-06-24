package com.medix.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medix.config.MedixProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RerankerClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String rerankerUrl;

    public RerankerClient() {
        this(false, "", new ObjectMapper());
    }

    @Autowired
    public RerankerClient(MedixProperties properties) {
        this(properties.features().reranker(), properties.services().rerankerUrl(), new ObjectMapper());
    }

    RerankerClient(boolean enabled, String rerankerUrl, ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.rerankerUrl = rerankerUrl;
    }

    public List<KnowledgeSnippet> rerank(String query, List<KnowledgeSnippet> snippets) {
        if (!enabled || rerankerUrl == null || rerankerUrl.isBlank() || snippets.isEmpty()) {
            return snippets;
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "documents", snippets.stream().map(KnowledgeSnippet::content).toList()
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(rerankerUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return snippets;
            }
            return applyScores(snippets, objectMapper.readTree(response.body()));
        } catch (Exception ignored) {
            return snippets;
        }
    }

    private List<KnowledgeSnippet> applyScores(List<KnowledgeSnippet> snippets, JsonNode root) {
        List<Double> scores = readScores(root);
        if (scores.isEmpty()) {
            return snippets;
        }
        List<KnowledgeSnippet> reranked = new ArrayList<>();
        for (int i = 0; i < snippets.size(); i++) {
            double score = i < scores.size() ? scores.get(i) : snippets.get(i).score();
            reranked.add(snippets.get(i).withScore(score));
        }
        reranked.sort(Comparator.comparingDouble(KnowledgeSnippet::score).reversed());
        return reranked;
    }

    private List<Double> readScores(JsonNode root) {
        if (root.has("scores") && root.get("scores").isArray()) {
            return toScores(root.get("scores"));
        }
        if (root.has("results") && root.get("results").isArray()) {
            List<Double> scores = new ArrayList<>();
            root.get("results").forEach(result -> scores.add(Optional.ofNullable(result.get("score"))
                    .map(JsonNode::asDouble)
                    .orElse(0.0)));
            return scores;
        }
        return List.of();
    }

    private List<Double> toScores(JsonNode values) {
        List<Double> scores = new ArrayList<>();
        values.forEach(value -> scores.add(value.asDouble()));
        return scores;
    }
}
