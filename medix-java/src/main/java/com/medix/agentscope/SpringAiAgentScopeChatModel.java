package com.medix.agentscope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Spring AI is the sole provider boundary; AgentScope owns the ReAct/tool execution loop. */
public final class SpringAiAgentScopeChatModel extends ChatModelBase {
    private static final Logger log = LoggerFactory.getLogger(SpringAiAgentScopeChatModel.class);
    private final ChatModel delegate;
    private final ObjectMapper json;
    private final String expectedModel;

    public SpringAiAgentScopeChatModel(ChatModel delegate, ObjectMapper json) {
        this(delegate, json, configuredModel(delegate));
    }

    public SpringAiAgentScopeChatModel(ChatModel delegate, ObjectMapper json, String expectedModel) {
        this.delegate = delegate;
        this.json = json;
        this.expectedModel = requireModel(expectedModel);
        requireCompatibleDefaults(delegate, this.expectedModel);
    }

    @Override
    public String getModelName() { return "spring-ai"; }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        List<ToolSchema> publishedTools = tools == null ? List.of() : tools;
        Set<String> allowedTools = publishedTools.stream().map(ToolSchema::getName).collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Message> springMessages = messages.stream().map(this::toSpringMessage).toList();
        OpenAiChatOptions defaults = requireCompatibleDefaults(delegate, expectedModel);
        Map<String, Object> extraBody = new LinkedHashMap<>();
        if (defaults.getExtraBody() != null) extraBody.putAll(defaults.getExtraBody());
        // Tool-loop calls stay non-thinking because reasoning_content must be replayed across tool turns.
        // The AG-UI final synthesis uses DeepSeekStreamingService and exposes thinking safely and losslessly.
        extraBody.put("thinking", Map.of("type", "disabled"));
        // Spring AI 2.0.0 injects parameters.strict=true while converting callbacks and writes a second
        // top-level tools field when extraBody.tools is also present. Send only the normalized provider schemas;
        // AgentScope Toolkit remains the sole tool executor and this adapter parses provider tool calls directly.
        extraBody.put("tools", providerTools(publishedTools));
        OpenAiChatOptions chatOptions = defaults.mutate()
                .toolCallbacks(List.of())
                .extraBody(extraBody)
                .build();
        if (!expectedModel.equals(chatOptions.getModel())) throw new IllegalStateException("LLM_MODEL_MISMATCH");
        Prompt prompt = new Prompt(springMessages, chatOptions);
        String requestShape = requestShape(springMessages, publishedTools, chatOptions);
        // AgentScope consumes a Flux, but MediX does not expose provider token streaming. A single non-streaming
        // OpenAI-compatible response avoids providers that accept tools only on chat completions without SSE.
        return Mono.fromSupplier(() -> delegate.call(prompt)).flux()
                .onErrorMap(failure -> {
                    log.warn("LLM_REQUEST_SHAPE {}", requestShape);
                    return sanitizeProviderFailure(failure);
                })
                .switchIfEmpty(Flux.error(new IllegalStateException("LLM_INVALID_RESPONSE")))
                .map(response -> toAgentScopeResponse(response, allowedTools));
    }

    private Message toSpringMessage(Msg message) {
        if (message.getRole() == io.agentscope.core.message.MsgRole.SYSTEM) return new SystemMessage(message.getTextContent());
        if (message.getRole() == io.agentscope.core.message.MsgRole.USER) return new UserMessage(message.getTextContent());
        if (message.getRole() == io.agentscope.core.message.MsgRole.TOOL) {
            List<ToolResponseMessage.ToolResponse> responses = message.getContentBlocks(ToolResultBlock.class).stream()
                    .map(block -> {
                        String id = requireToolCallId(block.getId());
                        log.info("LLM_TOOL_CORRELATION phase=tool_result id_sha256={} tool={}",
                                sha256(id), block.getName());
                        return new ToolResponseMessage.ToolResponse(id, block.getName(), text(block.getOutput()));
                    })
                    .toList();
            return ToolResponseMessage.builder().responses(responses).build();
        }
        List<AssistantMessage.ToolCall> calls = message.getContentBlocks(ToolUseBlock.class).stream()
                .map(block -> new AssistantMessage.ToolCall(requireToolCallId(block.getId()), "function",
                        block.getName(), arguments(block)))
                .toList();
        return AssistantMessage.builder().content(message.getTextContent()).toolCalls(calls).build();
    }

    private ChatResponse toAgentScopeResponse(org.springframework.ai.chat.model.ChatResponse response, Set<String> allowedTools) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("LLM_INVALID_RESPONSE");
        }
        Generation generation = response.getResult();
        AssistantMessage output = generation.getOutput();
        List<ContentBlock> blocks = new ArrayList<>();
        if (output.getText() != null && !output.getText().isBlank()) blocks.add(TextBlock.builder().text(output.getText()).build());
        Set<String> seenCalls = new HashSet<>();
        for (AssistantMessage.ToolCall call : output.getToolCalls()) {
            validateToolCall(call, allowedTools);
            String id = requireToolCallId(call.id());
            String key = call.name() + "\u0000" + call.arguments();
            if (!seenCalls.add(key)) {
                log.warn("[DEDUP] component=MODEL_TOOL_CALL tool={} reason=duplicate_calls_in_provider_response", call.name());
                continue;
            }
            log.info("LLM_TOOL_CORRELATION phase=provider_call id_sha256={} tool={}", sha256(id), call.name());
            blocks.add(ToolUseBlock.builder().id(id)
                    .name(call.name()).content(call.arguments()).build());
        }
        if (blocks.isEmpty()) throw new IllegalStateException("LLM_INVALID_RESPONSE");
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
        return ChatResponse.builder()
                .id(response.getMetadata().getId())
                .content(blocks)
                .finishReason(generation.getMetadata().getFinishReason())
                .usage(ChatUsage.builder()
                        .inputTokens(value(usage.getPromptTokens()))
                        .outputTokens(value(usage.getCompletionTokens())).build())
                .build();
    }

    private void validateToolCall(AssistantMessage.ToolCall call, Set<String> allowedTools) {
        if (call == null || call.name() == null || !allowedTools.contains(call.name())) {
            throw new SecurityException("LLM_TOOL_NOT_ALLOWED");
        }
        try {
            if (call.arguments() == null || !json.readTree(call.arguments()).isObject()) {
                throw new IllegalStateException("LLM_INVALID_TOOL_CALL");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("LLM_INVALID_TOOL_CALL");
        }
    }

    private Throwable sanitizeProviderFailure(Throwable failure) {
        if (failure instanceof SecurityException
                || failure instanceof IllegalStateException state && state.getMessage() != null
                && state.getMessage().startsWith("LLM_")) return failure;
        return new IllegalStateException(providerDiagnosticCode(failure) + "_" + providerCategory(failure));
    }

    private String providerDiagnosticCode(Throwable failure) {
        String type = failure.getClass().getSimpleName().replaceAll("[^A-Za-z]", "_").toUpperCase();
        String frame = java.util.Arrays.stream(failure.getStackTrace())
                .filter(value -> value.getClassName().startsWith("org.springframework.ai.openai")
                        || value.getClassName().startsWith("com.openai"))
                .findFirst().map(StackTraceElement::getMethodName).orElse("unknown")
                .replaceAll("[^A-Za-z]", "_").toUpperCase();
        return "LLM_PROVIDER_" + type + "_" + frame;
    }

    private String providerCategory(Throwable failure) {
        String value = (failure.getClass().getSimpleName() + " " + String.valueOf(failure.getMessage()))
                .toLowerCase();
        if (value.contains("429") || value.contains("rate limit")) return "RATE_LIMIT";
        if (value.contains("timeout") || value.contains("timed out")) return "TIMEOUT";
        if (value.contains("400") && value.contains("tool")) return "HTTP_BAD_REQUEST_TOOL";
        if (value.contains("400")) return "HTTP_BAD_REQUEST";
        if (value.matches(".*\\b5\\d\\d\\b.*")) return "HTTP_SERVER_ERROR";
        return "UNCLASSIFIED";
    }

    private String requestShape(List<Message> messages, List<ToolSchema> tools, OpenAiChatOptions options) {
        String roles = messages.stream().map(message -> message.getMessageType().name()).collect(
                java.util.stream.Collectors.joining("_"));
        String names = tools.stream().map(ToolSchema::getName).sorted().collect(
                java.util.stream.Collectors.joining("_"));
        String schemaKeys = tools.stream().flatMap(tool -> tool.getParameters().keySet().stream())
                .filter(key -> !"strict".equals(key)).distinct().sorted().collect(
                        java.util.stream.Collectors.joining("_"));
        String fields = tools.isEmpty() ? "MESSAGES_MODEL_THINKING" : "MESSAGES_MODEL_TOOLS_THINKING";
        String canonical = "FIELDS=" + fields + ";ROLES=" + roles + ";MESSAGE_COUNT=" + messages.size()
                + ";MODEL=" + options.getModel() + ";TOOL_COUNT=" + tools.size() + ";TOOLS=" + names
                + ";SCHEMA_KEYS=" + schemaKeys + ";TEMPERATURE=" + present(options.getTemperature())
                + ";TOOL_CHOICE=" + present(options.getToolChoice())
                + ";PARALLEL_TOOL_CALLS=" + present(options.getParallelToolCalls())
                + ";STREAM_OPTIONS=" + present(options.getStreamOptions());
        return canonical + ";SHAPE_SHA256=" + sha256(canonical);
    }

    private String present(Object value) {
        return value == null ? "ABSENT" : "PRESENT";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("LLM_DIAGNOSTIC_HASH_UNAVAILABLE");
        }
    }

    private List<Map<String, Object>> providerTools(List<ToolSchema> schemas) {
        return schemas.stream().map(schema -> {
            validateToolName(schema.getName());
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", schema.getName());
            function.put("description", schema.getDescription());
            function.put("parameters", normalizeParameters(schema.getParameters()));
            return Map.<String, Object>of("type", "function", "function", function);
        }).toList();
    }

    private Map<String, Object> normalizeParameters(Map<String, Object> source) {
        if (source == null || !"object".equals(source.get("type"))) {
            throw new IllegalArgumentException("LLM_INVALID_TOOL_SCHEMA");
        }
        Set<String> supported = Set.of("type", "properties", "required", "strict");
        if (source.keySet().stream().anyMatch(key -> !supported.contains(key))) {
            throw new IllegalArgumentException("LLM_INVALID_TOOL_SCHEMA");
        }
        if (!(source.get("properties") instanceof Map<?, ?> properties)) {
            throw new IllegalArgumentException("LLM_INVALID_TOOL_SCHEMA");
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", "object");
        normalized.put("properties", new LinkedHashMap<>(properties));
        if (source.containsKey("required")) {
            if (!(source.get("required") instanceof List<?> required)
                    || required.stream().anyMatch(value -> !(value instanceof String))) {
                throw new IllegalArgumentException("LLM_INVALID_TOOL_SCHEMA");
            }
            normalized.put("required", List.copyOf(required));
        }
        return normalized;
    }

    private void validateToolName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("LLM_INVALID_TOOL_SCHEMA");
        }
    }

    private String requireToolCallId(String id) {
        if (id == null || id.isBlank()) throw new IllegalStateException("LLM_INVALID_TOOL_CALL_ID");
        return id;
    }

    private static String configuredModel(ChatModel delegate) {
        return requireCompatibleDefaults(delegate, null).getModel();
    }

    private static OpenAiChatOptions requireCompatibleDefaults(ChatModel delegate, String expectedModel) {
        Objects.requireNonNull(delegate, "delegate");
        if (!(delegate.getDefaultOptions() instanceof OpenAiChatOptions defaults)) {
            throw new IllegalStateException("LLM_PROVIDER_OPTIONS_INVALID");
        }
        String configured = requireModel(defaults.getModel());
        if (expectedModel != null && !configured.equals(expectedModel)) {
            throw new IllegalStateException("LLM_MODEL_MISMATCH");
        }
        return defaults;
    }

    private static String requireModel(String model) {
        if (model == null || model.isBlank()) throw new IllegalStateException("LLM_MODEL_NOT_CONFIGURED");
        return model;
    }

    private String arguments(ToolUseBlock block) {
        return block.getContent() == null || block.getContent().isBlank() ? write(block.getInput()) : block.getContent();
    }

    private String text(List<ContentBlock> blocks) {
        return blocks.stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText).reduce("", (a, b) -> a + b);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Invalid tool schema", exception); }
    }

    private int value(Integer number) { return number == null ? 0 : number; }
}
