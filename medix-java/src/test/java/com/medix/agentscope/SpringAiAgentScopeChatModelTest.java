package com.medix.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

class SpringAiAgentScopeChatModelTest {
    @Test
    void mapsTextToolCallFinishReasonAndUsage() {
        ChatModel delegate = delegate();
        AssistantMessage output = AssistantMessage.builder()
                .content("checking evidence")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "search_knowledge", "{\"query\":\"hypertension\"}")))
                .build();
        Generation generation = new Generation(output,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build());
        org.springframework.ai.chat.model.ChatResponse springResponse =
                new org.springframework.ai.chat.model.ChatResponse(List.of(generation),
                        ChatResponseMetadata.builder().id("response-1")
                                .usage(new DefaultUsage(11, 7)).build());
        when(delegate.call(any(Prompt.class))).thenReturn(springResponse);

        SpringAiAgentScopeChatModel adapter = new SpringAiAgentScopeChatModel(delegate, new ObjectMapper());
        ToolSchema schema = ToolSchema.builder().name("search_knowledge").description("Search evidence")
                .parameters(Map.of("type", "object", "properties", Map.of(
                        "query", Map.of("type", "string")))).build();

        ChatResponse mapped = adapter.stream(List.of(new UserMessage("hypertension")), List.of(schema),
                GenerateOptions.builder().build()).blockLast();

        assertThat(mapped).isNotNull();
        assertThat(mapped.getId()).isEqualTo("response-1");
        assertThat(mapped.getFinishReason()).isEqualTo("tool_calls");
        assertThat(mapped.getContent()).filteredOn(TextBlock.class::isInstance)
                .extracting(block -> ((TextBlock) block).getText()).containsExactly("checking evidence");
        ToolUseBlock tool = mapped.getContent().stream().filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast).findFirst().orElseThrow();
        assertThat(tool.getId()).isEqualTo("call-1");
        assertThat(tool.getName()).isEqualTo("search_knowledge");
        assertThat(tool.getContent()).isEqualTo("{\"query\":\"hypertension\"}");
        assertThat(mapped.getUsage().getInputTokens()).isEqualTo(11);
        assertThat(mapped.getUsage().getOutputTokens()).isEqualTo(7);
    }

    @Test
    void propagatesProviderFailure() {
        ChatModel delegate = delegate();
        when(delegate.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider down"));
        SpringAiAgentScopeChatModel adapter = new SpringAiAgentScopeChatModel(delegate, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.stream(
                List.of(new UserMessage("hello")), List.of(), GenerateOptions.builder().build()).blockLast())
                .hasMessage("LLM_PROVIDER_ILLEGALSTATEEXCEPTION_UNKNOWN_UNCLASSIFIED");
    }

    @Test
    void classifiesProviderFailuresWithoutLeakingProviderBody() {
        Map<String, String> cases = Map.of(
                "429 rate limit upstream-detail", "LLM_PROVIDER_RUNTIMEEXCEPTION_UNKNOWN_RATE_LIMIT",
                "request timed out upstream-detail", "LLM_PROVIDER_RUNTIMEEXCEPTION_UNKNOWN_TIMEOUT",
                "400 invalid tool schema upstream-detail", "LLM_PROVIDER_RUNTIMEEXCEPTION_UNKNOWN_HTTP_BAD_REQUEST_TOOL",
                "500 upstream-detail", "LLM_PROVIDER_RUNTIMEEXCEPTION_UNKNOWN_HTTP_SERVER_ERROR");
        cases.forEach((providerFailure, expectedCode) -> {
            ChatModel delegate = delegate();
            when(delegate.call(any(Prompt.class))).thenThrow(new RuntimeException(providerFailure));
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    new SpringAiAgentScopeChatModel(delegate, new ObjectMapper()).stream(
                            List.of(new UserMessage("hello")), List.of(),
                            GenerateOptions.builder().build()).blockLast())
                    .hasMessage(expectedCode)
                    .hasMessageNotContaining("upstream-detail");
        });
    }

    @Test
    void rejectsUnknownToolWithoutExecutingIt() {
        ChatModel delegate = delegate();
        AssistantMessage output = AssistantMessage.builder().toolCalls(List.of(
                new AssistantMessage.ToolCall("call-2", "function", "delete_patient", "{}"))).build();
        when(delegate.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(output, ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
        SpringAiAgentScopeChatModel adapter = new SpringAiAgentScopeChatModel(delegate, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.stream(List.of(new UserMessage("hello")),
                List.of(ToolSchema.builder().name("search_knowledge").description("safe")
                        .parameters(Map.of("type", "object", "properties", Map.of())).build()),
                GenerateOptions.builder().build()).blockLast())
                .hasMessage("LLM_TOOL_NOT_ALLOWED");
    }

    @Test
    void rejectsMalformedToolArgumentsAndEmptyResponse() {
        ChatModel malformed = delegate();
        AssistantMessage output = AssistantMessage.builder().toolCalls(List.of(
                new AssistantMessage.ToolCall("call-3", "function", "search_knowledge", "not-json"))).build();
        when(malformed.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(output, ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
        ToolSchema schema = ToolSchema.builder().name("search_knowledge").description("safe")
                .parameters(Map.of("type", "object", "properties", Map.of())).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SpringAiAgentScopeChatModel(malformed, new ObjectMapper())
                .stream(List.of(new UserMessage("hello")), List.of(schema), GenerateOptions.builder().build()).blockLast())
                .hasMessage("LLM_INVALID_TOOL_CALL");

        ChatModel empty = delegate();
        when(empty.call(any(Prompt.class))).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SpringAiAgentScopeChatModel(empty, new ObjectMapper())
                .stream(List.of(new UserMessage("hello")), List.of(), GenerateOptions.builder().build()).blockLast())
                .hasMessage("LLM_INVALID_RESPONSE");
    }

    @Test
    void rejectsProviderToolCallWithoutIdInsteadOfInventingCorrelation() {
        ChatModel delegate = delegate();
        AssistantMessage output = AssistantMessage.builder().toolCalls(List.of(
                new AssistantMessage.ToolCall("", "function", "search_knowledge", "{}"))).build();
        when(delegate.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(output, ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
        ToolSchema schema = ToolSchema.builder().name("search_knowledge").description("safe")
                .parameters(Map.of("type", "object", "properties", Map.of())).build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new SpringAiAgentScopeChatModel(delegate, new ObjectMapper()).stream(
                        List.of(new UserMessage("hello")), List.of(schema),
                        GenerateOptions.builder().build()).blockLast())
                .hasMessage("LLM_INVALID_TOOL_CALL_ID");
    }

    @Test
    void inheritsProviderOptionsAndFailsClosedOnModelDrift() {
        ChatModel delegate = mock(ChatModel.class);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:1")
                .apiKey("local-runtime-value")
                .model("deepseek-v4-flash")
                .temperature(0.23)
                .extraBody(Map.of("provider_marker", "preserved"))
                .build();
        when(delegate.getDefaultOptions()).thenReturn(defaults);
        AssistantMessage output = AssistantMessage.builder().content("ok").build();
        when(delegate.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(output, ChatGenerationMetadata.builder().finishReason("stop").build()))));

        ToolSchema schema = ToolSchema.builder().name("search_knowledge").description("safe")
                .parameters(Map.of("type", "object", "properties", Map.of())).build();
        new SpringAiAgentScopeChatModel(delegate, new ObjectMapper(), "deepseek-v4-flash").stream(
                List.of(new UserMessage("hello")), List.of(schema), GenerateOptions.builder().build()).blockLast();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate).call(prompt.capture());
        OpenAiChatOptions sent = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(sent.getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(sent.getBaseUrl()).isEqualTo("http://127.0.0.1:1");
        assertThat(sent.getTemperature()).isNull();
        assertThat(sent.getExtraBody()).containsEntry("provider_marker", "preserved")
                .containsEntry("thinking", Map.of("type", "disabled"));

        when(delegate.getDefaultOptions()).thenReturn(defaults.mutate().model("unexpected-model").build());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new SpringAiAgentScopeChatModel(delegate, new ObjectMapper(), "deepseek-v4-flash").stream(
                        List.of(new UserMessage("hello")), List.of(schema),
                        GenerateOptions.builder().build()).blockLast())
                .hasMessage("LLM_MODEL_MISMATCH");
    }

    private ChatModel delegate() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:1")
                .apiKey("runtime-test-value")
                .model("deepseek-v4-flash")
                .build());
        return delegate;
    }
}
