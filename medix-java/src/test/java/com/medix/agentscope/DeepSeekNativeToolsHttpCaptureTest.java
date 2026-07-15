package com.medix.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.medix.agent.AgentRequest;
import com.medix.agent.AgentResult;
import com.medix.harness.HarnessValidator;
import com.medix.harness.OutputRepairService;
import com.medix.nlu.EmergencyRiskDetector;
import com.medix.nlu.IntentLabel;
import com.medix.nlu.NluClassificationException;
import com.medix.nlu.NluProperties;
import com.medix.nlu.NluResult;
import com.medix.permission.PermissionService;
import com.medix.security.AppPrincipal;
import com.medix.skill.MedicalSkill;
import com.medix.skill.SkillRegistry;
import com.medix.skill.SkillRequest;
import com.medix.skill.SkillResult;
import com.medix.swarm.RouteDecision;
import com.medix.swarm.SwarmRouter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

class DeepSeekNativeToolsHttpCaptureTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> rawRequests = new ArrayList<>();
    private final List<String> providerResponseToolCallIds = new ArrayList<>();
    private final AtomicInteger callbackCalls = new AtomicInteger();
    private HttpServer server;
    private ChatModel delegate;
    private String baseUrl;

    @BeforeEach
    void startCaptureServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::respond);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        delegate = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey("local-runtime-value")
                .model("deepseek-v4-flash")
                .build()).build();
    }

    @AfterEach
    void stopCaptureServer() {
        server.stop(0);
    }

    @Test
    void capturesBaselineOverrideAndFixedSingleSevenAndSecondRoundRequests() {
        delegate.call(new Prompt("ordinary request"));

        OpenAiChatOptions brokenRequestOptions = OpenAiChatOptions.builder()
                .toolCallbacks(List.of(callback("search_knowledge")))
                .build();
        delegate.call(new Prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("baseline tools")),
                brokenRequestOptions));

        SpringAiAgentScopeChatModel adapter = new SpringAiAgentScopeChatModel(
                delegate, json, "deepseek-v4-flash");
        ToolSchema single = schema("search_knowledge", true);
        adapter.stream(List.of(new UserMessage("single tool")), List.of(single),
                GenerateOptions.builder().build()).blockLast();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MedicalAgentScopeTools("capture-agent", "capture-session", Map.of(),
                mock(SkillRegistry.class), mock(PermissionService.class)));
        List<ToolSchema> seven = toolkit.getToolSchemas();
        adapter.stream(List.of(new UserMessage("seven tools")), seven,
                GenerateOptions.builder().build()).blockLast();

        Msg assistant = Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder()
                .id("provider-call-1").name("search_knowledge")
                .content("{\"query\":\"captured\"}").build()).build();
        Msg tool = Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.builder()
                .id("provider-call-1").name("search_knowledge")
                .output(TextBlock.builder().text("reviewed evidence").build()).build()).build();
        adapter.stream(List.of(new UserMessage("original question"), assistant, tool), List.of(single),
                GenerateOptions.builder().build()).blockLast();

        ChatModel liveDefaultsDelegate = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl).apiKey("local-runtime-value").model("deepseek-v4-flash")
                .temperature(0.7).build()).build();
        SpringAiAgentScopeChatModel liveDefaultsAdapter = new SpringAiAgentScopeChatModel(
                liveDefaultsDelegate, json, "deepseek-v4-flash");
        Set<String> consultationNames = Set.of("assess_risk", "recommend_lifestyle", "search_knowledge");
        List<ToolSchema> consultationTools = seven.stream()
                .filter(toolSchema -> consultationNames.contains(toolSchema.getName())).toList();
        String systemPrompt = "You are MediX, a cautious medical information assistant. "
                + "For identity or capability questions, introduce yourself directly without calling a tool. "
                + "For medical questions, use only registered tools when evidence is needed. "
                + "After any tool result, answer the original question using that evidence in one final response. "
                + "Never expose hidden reasoning, diagnose with certainty, invent evidence, or prescribe treatment.";
        liveDefaultsAdapter.stream(List.of(new io.agentscope.core.message.SystemMessage(systemPrompt),
                        new UserMessage("Explain a common health topic after using an appropriate evidence tool.")),
                consultationTools, GenerateOptions.builder().build()).blockLast();

        assertThat(requests).hasSize(6);
        assertThat(requests.get(0).path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(requests.get(0).has("tools")).isFalse();
        assertThat(requests.get(1).path("model").asText()).isEqualTo("gpt-5-mini");
        assertThat(requests.get(1).path("tools")).hasSize(1);

        for (int index : List.of(2, 3, 4)) assertFixedToolRequest(requests.get(index));
        assertThat(requests.get(2).path("tools")).hasSize(1);
        assertThat(requests.get(3).path("tools")).hasSize(7);
        assertThat(requests.get(4).path("messages").toString())
                .contains("\"role\":\"assistant\"", "\"role\":\"tool\"", "provider-call-1", "reviewed evidence");
        assertThat(callbackCalls).hasValue(0);
        JsonNode liveDefaultsRequest = requests.get(5);
        assertThat(liveDefaultsRequest.path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(liveDefaultsRequest.has("temperature")).isFalse();
        assertThat(liveDefaultsRequest.path("tools")).hasSize(3);
        assertThat(liveDefaultsRequest.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(liveDefaultsRequest.path("thinking").path("type").asText()).isEqualTo("disabled");
        for (int index : List.of(2, 3, 4, 5)) {
            assertThat(countField(rawRequests.get(index), "tools")).isEqualTo(1);
        }
    }

    @Test
    void capturesAssociatedProviderToolkitPermissionAndEvidenceTrace() {
        AtomicInteger toolkitCalls = new AtomicInteger();
        MedicalSkill skill = new MedicalSkill() {
            public String name() { return "search_knowledge"; }
            public String description() { return "Search reviewed medical knowledge"; }
            public SkillResult invoke(SkillRequest request) {
                toolkitCalls.incrementAndGet();
                return SkillResult.success(name(), "reviewed evidence", Map.of(
                        "source", "local-reviewed-fixture", "query", request.query()));
            }
        };
        PermissionService permissions = mock(PermissionService.class);
        AppPrincipal principal = new AppPrincipal(java.util.UUID.randomUUID(), "capture", "Capture", Set.of("USER"));
        when(permissions.canExecute(principal, "consultation_agent", "search_knowledge", "trace-run"))
                .thenReturn(new PermissionService.Decision(true, "EXPLICIT_GRANT", principal.id().toString(),
                        "consultation_agent", "search_knowledge"));
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(delegate);
        AgentScopeRuntimeAdapter runtime = new AgentScopeRuntimeAdapter(
                new SkillRegistry(List.of(skill)), new HarnessValidator(), new OutputRepairService(), permissions,
                models, true, "deepseek-v4-flash", 3, java.time.Duration.ofSeconds(5));

        AgentResult result = runtime.run("consultation_agent", new AgentRequest(
                "Use reviewed evidence for my question", "trace-session", Map.of(
                "runId", "trace-run", "security.principal", principal,
                "permission.capabilities", Map.of("consultation_agent", Set.of("search_knowledge")))));

        assertThat(result.skillCalls()).containsExactly("search_knowledge");
        assertThat(result.answer()).contains("captured final answer");
        assertThat(toolkitCalls).hasValue(1);
        verify(permissions, times(1)).canExecute(
                principal, "consultation_agent", "search_knowledge", "trace-run");
        assertThat(callbackCalls).hasValue(0);
        assertThat(requests).hasSize(2);
        assertFixedToolRequest(requests.get(0));
        assertFixedToolRequest(requests.get(1));
        assertThat(providerResponseToolCallIds).containsExactly("provider-call-1");
        assertThat(requests.get(1).path("messages").toString()).contains(
                "provider-call-1", "reviewed evidence", "Use reviewed evidence for my question");
        assertThat(countField(rawRequests.get(0), "tools")).isEqualTo(1);
        assertThat(countField(rawRequests.get(1), "tools")).isEqualTo(1);
    }

    @Test
    void nluRunsOnceForRoutingAndFailureNeverBecomesAnswerContent() {
        AtomicInteger normalCalls = new AtomicInteger();
        NluProperties enabled = new NluProperties(true, "http://localhost:11434", "routing-only-model",
                Duration.ofSeconds(1), 0.70, 0.55, 0.10, 0.30);
        SwarmRouter normalRouter = new SwarmRouter(question -> {
            normalCalls.incrementAndGet();
            return new NluResult(Map.of(IntentLabel.HEALTH_CONSULTATION, 0.95,
                    IntentLabel.SYMPTOM_ANALYSIS, 0.01,
                    IntentLabel.RISK_ASSESSMENT, 0.01,
                    IntentLabel.GUIDELINE_SEARCH, 0.01,
                    IntentLabel.DISEASE_CODE, 0.01,
                    IntentLabel.LIFESTYLE_ADVICE, 0.01));
        }, new EmergencyRiskDetector(), enabled);

        RouteDecision normal = normalRouter.route("如何保持规律作息？");

        assertThat(normalCalls).hasValue(1);
        assertThat(normal.primaryAgent()).isEqualTo("consultation_agent");
        assertThat(normal.reason()).isEqualTo("nlu_high_confidence_single");

        AtomicInteger failedCalls = new AtomicInteger();
        SwarmRouter failedRouter = new SwarmRouter(question -> {
            failedCalls.incrementAndGet();
            throw new NluClassificationException("ollama-output-must-not-be-an-answer");
        }, new EmergencyRiskDetector(), enabled);

        RouteDecision failed = failedRouter.route("如何保持规律作息？");

        assertThat(failedCalls).hasValue(1);
        assertThat(failed.primaryAgent()).isEqualTo("lead_agent");
        assertThat(failed.reason()).isEqualTo("nlu_unavailable");
        assertThat(failed.toString()).doesNotContain("ollama-output-must-not-be-an-answer");
    }

    private void assertFixedToolRequest(JsonNode request) {
        assertThat(request.path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(request.has("temperature")).isFalse();
        assertThat(request.has("tool_choice")).isFalse();
        assertThat(request.has("parallel_tool_calls")).isFalse();
        assertThat(request.has("stream")).isFalse();
        request.path("tools").forEach(tool -> {
            JsonNode function = tool.path("function");
            assertThat(function.path("parameters").path("type").asText()).isEqualTo("object");
            assertThat(function.path("parameters").has("strict")).as("request=%s", request).isFalse();
            assertThat(function.path("parameters").path("required").get(0).asText()).isEqualTo("query");
        });
    }

    private ToolSchema schema(String name, boolean includeMisplacedStrict) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of("query", Map.of(
                "type", "string", "description", "medical query")));
        parameters.put("required", List.of("query"));
        if (includeMisplacedStrict) parameters.put("strict", true);
        return ToolSchema.builder().name(name).description("Safe medical tool").parameters(parameters).build();
    }

    private ToolCallback callback(String name) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return new DefaultToolDefinition(name, "baseline tool",
                        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}");
            }

            @Override
            public String call(String input) {
                callbackCalls.incrementAndGet();
                throw new AssertionError("Spring AI must not execute AgentScope tools");
            }
        };
    }

    private void respond(HttpExchange exchange) throws IOException {
        String rawRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        rawRequests.add(rawRequest);
        JsonNode request = json.readTree(rawRequest);
        requests.add(request);
        boolean hasToolResult = false;
        for (JsonNode message : request.path("messages")) {
            if ("tool".equals(message.path("role").asText())) hasToolResult = true;
        }
        String content;
        String finishReason;
        if (request.has("tools") && !hasToolResult) {
            String toolName = request.path("tools").get(0).path("function").path("name").asText();
            providerResponseToolCallIds.add("provider-call-1");
            content = "\"checking evidence\",\"tool_calls\":[{\"id\":\"provider-call-1\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"" + toolName
                    + "\",\"arguments\":\"{\\\"query\\\":\\\"captured\\\"}\"}}]";
            finishReason = "stop";
        } else {
            content = "\"captured final answer\"";
            finishReason = "stop";
        }
        String body = "{\"id\":\"chatcmpl-local\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"message\":{"
                + "\"role\":\"assistant\",\"content\":" + content + "},\"finish_reason\":\""
                + finishReason + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private int countField(String raw, String field) {
        return raw.split("\\\"" + field + "\\\"", -1).length - 1;
    }
}
