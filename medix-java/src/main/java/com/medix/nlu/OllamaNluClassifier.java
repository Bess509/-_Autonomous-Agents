package com.medix.nlu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OllamaNluClassifier implements NluClassifier {
    private static final String SYSTEM_PROMPT = """
            You are a deterministic Chinese medical NLU classifier. Return JSON only, with exactly this shape:
            {"probabilities":{"HEALTH_CONSULTATION":0.0,"SYMPTOM_ANALYSIS":0.0,"RISK_ASSESSMENT":0.0,"GUIDELINE_SEARCH":0.0,"DISEASE_CODE":0.0,"LIFESTYLE_ADVICE":0.0}}
            Every value must be a number from 0 to 1. Labels are independent multi-label probabilities. No markdown or explanation.
            """;

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final NluProperties properties;

    public OllamaNluClassifier(RestClient.Builder builder, NluProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.timeout());
        this.client = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
        this.objectMapper = new ObjectMapper();
        this.properties = properties;
    }

    @Override
    public NluResult classify(String text) {
        if (!properties.enabled()) {
            throw new NluClassificationException("NLU classifier is disabled");
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", properties.model(),
                    "stream", false,
                    "format", "json",
                    "options", Map.of("temperature", 0),
                    "messages", new Object[]{
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", text == null ? "" : text)
                    }
            );
            JsonNode response = client.post().uri("/api/chat").body(body).retrieve().body(JsonNode.class);
            if (response == null || !response.path("message").path("content").isTextual()) {
                throw new NluClassificationException("Ollama response has no message.content");
            }
            return parse(response.path("message").path("content").textValue());
        } catch (NluClassificationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new NluClassificationException("Ollama NLU request failed", exception);
        }
    }

    NluResult parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (!root.isObject() || root.size() != 1 || !root.has("probabilities")) {
                throw new NluClassificationException("NLU JSON must contain only probabilities");
            }
            JsonNode values = root.get("probabilities");
            Set<String> expected = Set.of(IntentLabel.values()).stream().map(Enum::name).collect(Collectors.toSet());
            if (!values.isObject() || values.size() != expected.size()) {
                throw new NluClassificationException("NLU JSON must contain exactly six labels");
            }
            EnumMap<IntentLabel, Double> probabilities = new EnumMap<>(IntentLabel.class);
            Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!expected.contains(field.getKey()) || !field.getValue().isNumber()) {
                    throw new NluClassificationException("Unknown label or non-numeric probability: " + field.getKey());
                }
                double probability = field.getValue().doubleValue();
                if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
                    throw new NluClassificationException("Probability out of range: " + field.getKey());
                }
                probabilities.put(IntentLabel.valueOf(field.getKey()), probability);
            }
            return new NluResult(probabilities);
        } catch (NluClassificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new NluClassificationException("Invalid NLU JSON", exception);
        }
    }
}
