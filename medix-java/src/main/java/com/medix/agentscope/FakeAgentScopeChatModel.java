package com.medix.agentscope;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;

/** Offline model for CI and local startup; still exercises AgentScope's real ReAct/tool loop. */
public final class FakeAgentScopeChatModel extends ChatModelBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Override public String getModelName() { return "medix-offline"; }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        boolean hasResult = messages.stream().anyMatch(msg -> msg.hasContentBlocks(ToolResultBlock.class));
        String latestText = messages.isEmpty() ? "" : messages.getLast().getTextContent();
        String conversation = messages.stream().map(Msg::getTextContent).reduce("", (left, right) -> left + "\n" + right);
        ContentBlock block;
        String finish;
        if (!hasResult && isIdentityQuestion(latestText)) {
            block = TextBlock.builder().text(
                    "我是 MediX 医疗助手，可以提供健康科普、症状风险提示和医学资料检索。"
                            + "我不会替代医生作出诊断或开具处方；如出现紧急症状，请立即联系 120 或前往急诊。"
            ).build();
            finish = "stop";
        } else if (hasResult) {
            String evidence = messages.stream().flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(result -> result.getOutput().stream()).filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).map(TextBlock::getText).reduce("", (a, b) -> a + b);
            ToolSchema risk = findTool(tools, "assess_risk");
            if (conversation.contains("头痛") && risk != null && !evidence.contains("riskLevel")) {
                block = toolUse(risk, originalQuestion(messages));
                finish = "tool_calls";
            } else {
                block = TextBlock.builder().text("内部证据贡献：" + readableEvidence(evidence)).build();
                finish = "stop";
            }
        } else if (tools != null && !tools.isEmpty()) {
            ToolSchema selected = selectTool(tools, conversation);
            block = toolUse(selected, originalQuestion(messages));
            finish = "tool_calls";
        } else {
            block = TextBlock.builder().text("当前 Agent 没有可执行的已授权工具。").build();
            finish = "stop";
        }
        return Flux.just(ChatResponse.builder().id(UUID.randomUUID().toString())
                .content(List.of(block)).finishReason(finish).build());
    }

    private ToolSchema selectTool(List<ToolSchema> tools, String text) {
        String normalized = text == null ? "" : text.toLowerCase();
        if ((normalized.contains("icd") || normalized.contains("疾病编码") || normalized.contains("疾病代码"))
                && findTool(tools, "disease_code") != null) return findTool(tools, "disease_code");
        if (normalized.contains("头痛") && findTool(tools, "analyze_symptoms") != null) {
            return findTool(tools, "analyze_symptoms");
        }
        if (findTool(tools, "search_knowledge") != null) return findTool(tools, "search_knowledge");
        return tools.getFirst();
    }

    private ToolSchema findTool(List<ToolSchema> tools, String name) {
        if (tools == null) return null;
        return tools.stream().filter(tool -> name.equals(tool.getName())).findFirst().orElse(null);
    }

    private ContentBlock toolUse(ToolSchema tool, String query) {
        Map<String, Object> input = Map.of("query", query == null ? "" : query);
        try {
            return new ToolUseBlock(UUID.randomUUID().toString(), tool.getName(), input,
                    JSON.writeValueAsString(input), Map.of());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String originalQuestion(List<Msg> messages) {
        return messages.stream().map(Msg::getTextContent).filter(text -> text != null && !text.isBlank())
                .filter(text -> !text.contains("ToolResultBlock")).findFirst().orElse("");
    }

    private boolean isIdentityQuestion(String value) {
        if (value == null) return false;
        String normalized = value.replaceAll("[\\s，。！？,.!?]", "").toLowerCase();
        return normalized.contains("你是谁") || normalized.contains("你是什么")
                || normalized.contains("介绍一下你自己") || normalized.contains("whoareyou");
    }

    private String readableEvidence(String evidence) {
        try {
            String content = JSON.readTree(evidence).path("content").asText();
            return content.isBlank() ? evidence : content;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return evidence;
        }
    }
}
