package com.medix.agui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Provider-native SSE client that preserves DeepSeek reasoning_content deltas. */
@Service
public class DeepSeekStreamingService {
    public record Delta(String reasoning, String content) {}

    private final boolean live;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public DeepSeekStreamingService(
            @Value("${medix.features.live-llm:false}") boolean live,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:deepseek-v4-flash}") String model) {
        this(live, apiKey, baseUrl, model, new ObjectMapper());
    }

    public DeepSeekStreamingService(boolean live, String apiKey, String baseUrl, String model, ObjectMapper mapper) {
        this.live = live;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean enabled() {
        return live && apiKey != null && !apiKey.isBlank();
    }

    public void stream(String question, String draft, Consumer<Delta> consumer) throws IOException, InterruptedException {
        if (!enabled()) throw new IllegalStateException("DEEPSEEK_STREAM_NOT_CONFIGURED");
        Map<String, Object> body = Map.of(
                "model", model, "stream", true, "max_tokens", 8192,
                "thinking", Map.of("type", "enabled"),
                "messages", List.of(
                        Map.of("role", "system", "content", "你是 MediX 医疗信息助手的最终答复整理器。基于已完成的多 Agent 草稿生成清晰、谨慎的中文答复；保留证据、不夸大结论、不诊断或开处方，并保留急症提示与免责声明。"),
                        Map.of("role", "user", "content", "用户问题：\n" + question + "\n\n多 Agent 草稿：\n" + draft)));
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() / 100 != 2) {
            try (Stream<String> lines = response.body()) { lines.limit(1).forEach(ignored -> {}); }
            throw new IllegalStateException("DEEPSEEK_HTTP_" + response.statusCode());
        }
        try (Stream<String> lines = response.body()) {
            lines.filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).trim())
                    .takeWhile(data -> !"[DONE]".equals(data))
                    .forEach(data -> consume(data, consumer));
        }
    }

    private URI endpoint() {
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(root + "/chat/completions");
    }

    private void consume(String data, Consumer<Delta> consumer) {
        if (data.isBlank()) return;
        try {
            JsonNode delta = mapper.readTree(data).path("choices").path(0).path("delta");
            String reasoning = delta.path("reasoning_content").isTextual() ? delta.path("reasoning_content").asText() : "";
            String content = delta.path("content").isTextual() ? delta.path("content").asText() : "";
            if (!reasoning.isEmpty() || !content.isEmpty()) consumer.accept(new Delta(reasoning, content));
        } catch (IOException malformed) {
            throw new IllegalStateException("DEEPSEEK_INVALID_STREAM", malformed);
        }
    }
}
