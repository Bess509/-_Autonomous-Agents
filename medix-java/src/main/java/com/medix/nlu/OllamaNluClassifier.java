package com.medix.nlu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class OllamaNluClassifier implements NluClassifier {
    private static final Logger log = LoggerFactory.getLogger(OllamaNluClassifier.class);
    private static final String SYSTEM_PROMPT = """
            你是确定性的中文医疗意图路由器。分别判断用户是否明确需要以下能力：
            HEALTH_CONSULTATION=一般健康科普或身份询问；
            SYMPTOM_ANALYSIS=解释用户正在经历的具体症状；
            RISK_ASSESSMENT=用户询问是否危险、是否需要急诊；
            GUIDELINE_SEARCH=用户明确要求指南、证据、研究或规范；
            DISEASE_CODE=用户明确要求 ICD 或疾病编码；
            LIFESTYLE_ADVICE=用户要求饮食、睡眠、作息或运动建议。

            最相关标签应为 0.80 到 1.00。除非用户明确同时提出另一需求，否则其他标签不得高于 0.20。
            不要因为问题属于医学就提高 GUIDELINE_SEARCH，也不要因为描述生活状态就提高 SYMPTOM_ANALYSIS。
            示例：你是谁 => HEALTH_CONSULTATION 0.95；我最近睡不好想改善作息 => LIFESTYLE_ADVICE 0.95；
            胸痛两小时危险吗 => RISK_ASSESSMENT 0.98、SYMPTOM_ANALYSIS 0.80；查高血压最新指南 => GUIDELINE_SEARCH 0.95。
            只按响应 schema 返回，不要输出 Markdown 或解释。
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
                    "think", false,
                    "format", responseFormat(),
                    "options", Map.of("temperature", 0),
                    "messages", new Object[]{
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", text == null ? "" : text)
                    }
            );
            String responseBody = CompletableFuture.supplyAsync(() ->
                    client.post().uri("/api/chat").body(body).retrieve().body(String.class))
                    .get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            JsonNode response;
            try {
                response = objectMapper.readTree(responseBody);
            } catch (Exception exception) {
                throw new NluClassificationException("Invalid Ollama response JSON", exception);
            }
            if (response == null || !response.path("message").path("content").isTextual()) {
                throw new NluClassificationException("Ollama response has no message.content");
            }
            NluResult result = parse(response.path("message").path("content").textValue());
            log.info("[NLU] model={} probabilities={}", properties.model(), result.probabilities());
            return result;
        } catch (NluClassificationException exception) {
            throw exception;
        } catch (java.util.concurrent.TimeoutException exception) {
            log.warn("[FALLBACK] component=NLU reason=ollama_timeout timeout={}", properties.timeout());
            throw new NluClassificationException("Ollama NLU request timed out after " + properties.timeout(), exception);
        } catch (Exception exception) {
            log.warn("[FALLBACK] component=NLU reason=ollama_failure type={}", exception.getClass().getSimpleName());
            throw new NluClassificationException("Ollama NLU request failed", exception);
        }
    }

    NluResult parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (!root.isObject()) throw new NluClassificationException("NLU JSON must be an object");
            JsonNode values = root.has("probabilities") ? root.get("probabilities") : root;
            if (!values.isObject()) throw new NluClassificationException("NLU probabilities must be an object");
            EnumMap<IntentLabel, Double> probabilities = new EnumMap<>(IntentLabel.class);
            for (IntentLabel label : IntentLabel.values()) {
                JsonNode value = values.get(label.name());
                if (value == null || !value.isNumber()) continue;
                double probability = value.doubleValue();
                if (Double.isFinite(probability) && probability >= 0 && probability <= 1) {
                    probabilities.put(label, probability);
                }
            }
            if (probabilities.isEmpty()) throw new NluClassificationException("NLU response contains no valid intent probability");
            for (IntentLabel label : IntentLabel.values()) probabilities.putIfAbsent(label, 0.0);
            return new NluResult(probabilities);
        } catch (NluClassificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new NluClassificationException("Invalid NLU JSON", exception);
        }
    }

    private Map<String, Object> responseFormat() {
        Map<String, Object> labelProperties = new LinkedHashMap<>();
        for (IntentLabel label : IntentLabel.values()) {
            labelProperties.put(label.name(), Map.of("type", "number", "minimum", 0, "maximum", 1));
        }
        Map<String, Object> probabilities = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", Set.of(IntentLabel.values()).stream().map(Enum::name).toList(),
                "properties", labelProperties
        );
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", new String[]{"probabilities"},
                "properties", Map.of("probabilities", probabilities)
        );
    }
}
