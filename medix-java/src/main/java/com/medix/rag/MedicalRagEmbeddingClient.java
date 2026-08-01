package com.medix.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HTTP client for the local Ollama bge-m3 embedding endpoint. */
@Component
public class MedicalRagEmbeddingClient {
    public static final int BGE_DIMENSIONS = 1024;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String model;
    private final int dimensions;
    private final Duration timeout;

    @Autowired
    public MedicalRagEmbeddingClient(
            @Value("${medix.rag.embedding.base-url:http://localhost:11434}") String baseUrl,
            @Value("${medix.rag.embedding.model:bge-m3}") String model,
            @Value("${medix.rag.embedding.dimensions:1024}") int dimensions,
            @Value("${medix.rag.embedding.timeout:15s}") Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).build(), new ObjectMapper(), baseUrl, model, dimensions, timeout);
    }

    MedicalRagEmbeddingClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl, String model, int dimensions, Duration timeout) {
        if (dimensions != BGE_DIMENSIONS) throw new IllegalArgumentException("Medical RAG BGE dimensions must be 1024");
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.dimensions = dimensions;
        this.timeout = timeout;
    }

    public double[] embedDocument(String text) { return embed(text); }
    public double[] embedQuery(String text) { return embed(text); }
    public List<double[]> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        try {
            List<String> normalized = texts.stream().map(text -> text == null ? "" : text).toList();
            String body = objectMapper.writeValueAsString(Map.of("model", model, "input", normalized));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/embed")).timeout(timeout)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw new IllegalStateException("Embedding service returned HTTP " + response.statusCode());
            JsonNode vectors = objectMapper.readTree(response.body()).path("embeddings");
            if (!vectors.isArray() || vectors.size() != normalized.size()) throw new IllegalStateException("Embedding response count does not match input count");
            return vectors.valueStream().map(this::readVector).toList();
        } catch (Exception exception) {
            throw new IllegalStateException("BGE embedding request failed", exception);
        }
    }

    private double[] embed(String text) {
        return embedDocuments(List.of(text)).get(0);
    }

    private double[] readVector(JsonNode vector) {
        if (!vector.isArray() || vector.size() != dimensions) throw new IllegalStateException("Embedding vector length must be " + dimensions);
        double[] result = new double[dimensions];
        for (int index = 0; index < dimensions; index++) result[index] = vector.get(index).asDouble();
        return result;
    }
}
