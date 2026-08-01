package com.medix.agui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites an anaphoric follow-up into a standalone retrieval query before NLU routing.
 * This component deliberately uses the local Ollama model; worker agents keep their
 * independently configured DeepSeek model channel.
 */
@Service
public class ConversationQueryPreprocessor {
    private static final Logger log = LoggerFactory.getLogger(ConversationQueryPreprocessor.class);
    public record Result(String retrievalQuery, String conversationSummary, boolean rewritten) {}

    private static final String SYSTEM_PROMPT = """
            你是医疗会话检索查询改写器。根据最近对话，将当前问题改写为一条独立、完整、忠实的中文检索问题。
            只补足代词、省略的已知主题和实体；不得凭空添加疾病、症状、药物、年龄或结论。
            若当前问题已经完整，原样返回。retrieval_query 必须是从输入中得到的实际完整问题，绝不可输出
            example、placeholder、query、... 等占位词，也不可照抄本说明。仅返回包含 retrieval_query 字段的 JSON。
            """;

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String model;
    private final Duration timeout;

    public ConversationQueryPreprocessor(RestClient.Builder builder,
                                         @Value("${medix.nlu.base-url:http://localhost:11434}") String baseUrl,
                                         @Value("${medix.nlu.model:qwen3.5:9b}") String model,
                                         @Value("${medix.nlu.timeout:20s}") Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(timeout);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.model = model;
        this.timeout = timeout;
    }

    public Result rewrite(String currentQuestion, List<RunAgentInput.Message> recentMessages) {
        String original = currentQuestion == null ? "" : currentQuestion.trim();
        List<RunAgentInput.Message> history = recentMessages == null ? List.of() : recentMessages;
        String summary = summarize(history, original);
        if (history.stream().filter(message -> "user".equals(message.role())).count() < 2 || !requiresRewrite(original)) {
            log.info("[QUERY_REWRITE] skipped reason={} historyUsers={} original={}",
                    requiresRewrite(original) ? "insufficient_history" : "standalone_question", history.size(), compact(original));
            return new Result(original, summary, false);
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model, "stream", false, "think", false, "format", "json",
                    "options", Map.of("temperature", 0.2, "top_p", 0.9),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", summary)));
            String raw = CompletableFuture.supplyAsync(() ->
                    client.post().uri("/api/chat").body(body).retrieve().body(String.class))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            JsonNode node = mapper.readTree(raw).path("message").path("content");
            String rewritten = mapper.readTree(node.asText("{}")).path("retrieval_query").asText("").trim();
            if (rewritten.isBlank() || rewritten.length() > 4000) return new Result(original, summary, false);
            log.info("[QUERY_REWRITE] model={} original={} rewritten={} changed={}", model, compact(original), compact(rewritten), !rewritten.equals(original));
            return new Result(rewritten, summary, !rewritten.equals(original));
        } catch (Exception ignored) {
            String fallback = deterministicFallback(original, history);
            String reason = ignored instanceof java.util.concurrent.TimeoutException ? "ollama_timeout" : "ollama_failure";
            log.warn("[FALLBACK] component=QUERY_REWRITE reason={} type={} original={} rewritten={}",
                    reason, ignored.getClass().getSimpleName(), compact(original), compact(fallback));
            return new Result(fallback, summary, !fallback.equals(original));
        }
    }

    private String summarize(List<RunAgentInput.Message> history, String current) {
        List<String> lines = new ArrayList<>();
        for (RunAgentInput.Message message : history) {
            if (message == null || message.content() == null || message.content().isBlank()) continue;
            lines.add(("assistant".equals(message.role()) ? "助手" : "用户") + "：" + message.content().trim());
        }
        if (history.isEmpty() || history.getLast() == null || !"user".equals(history.getLast().role())
                || !current.equals(history.getLast().content() == null ? "" : history.getLast().content().trim())) {
            lines.add("用户：" + current);
        }
        return String.join("\n", lines);
    }

    private String deterministicFallback(String original, List<RunAgentInput.Message> history) {
        if (!original.matches(".*(这个|它|上述|有推荐|怎么办|用药|药物).*")) return original;
        for (int index = history.size() - 1; index >= 0; index--) {
            RunAgentInput.Message message = history.get(index);
            if (message != null && "user".equals(message.role()) && message.content() != null
                    && !message.content().isBlank() && !message.content().equals(original)) {
                return "针对用户此前咨询的“" + message.content().trim() + "”，" + original;
            }
        }
        return original;
    }

    private boolean requiresRewrite(String question) {
        if (question == null || question.isBlank()) return false;
        String normalized = question.replaceAll("\\s+", "");
        return normalized.length() <= 8 || normalized.matches(".*(这个|它|上述|前面|有推荐|推荐药物|用药|药物|怎么办|怎么处理).*" );
    }

    private String compact(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "…";
    }
}
