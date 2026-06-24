package com.medix.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medix.swarm.SwarmSubtask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LeadAgent {
    private static final Map<String, String> WORKER_CAPABILITIES = Map.of(
            "consultation_agent", "健康咨询、常见病科普、生活方式建议、初步风险提示",
            "diagnostic_agent", "复杂症状分析、风险分层、鉴别诊断推理",
            "research_agent", "临床指南、诊疗规范、医学证据检索"
    );

    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;

    public LeadAgent() {
        this(new FakeModelGateway(), new ObjectMapper());
    }

    @Autowired
    public LeadAgent(ModelGateway modelGateway) {
        this(modelGateway, new ObjectMapper());
    }

    public LeadAgent(ModelGateway modelGateway, ObjectMapper objectMapper) {
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
    }

    public List<SwarmSubtask> assessAndDecompose(String question, Map<String, Object> context) {
        String prompt = buildDecompositionPrompt(question, context);
        String response = modelGateway.complete("lead_agent", prompt, WORKER_CAPABILITIES);
        try {
            List<SwarmSubtask> subtasks = parseSubtasks(response);
            if (!subtasks.isEmpty()) {
                return subtasks;
            }
        } catch (RuntimeException ignored) {
            // Fall back to a single safe consultation task when the model response is unusable.
        }
        return List.of(fallbackSubtask(question));
    }

    public String synthesize(String question, List<AgentResult> results) {
        String body = results.stream()
                .map(result -> "【" + result.agentId() + "】\n" + result.answer())
                .collect(Collectors.joining("\n\n"));
        return "综合问题：" + question + "\n\n" + body;
    }

    private String buildDecompositionPrompt(String question, Map<String, Object> context) {
        return """
                你是医疗 Swarm 的 LeadAgent，只负责把用户问题拆成尽量少的独立子任务。
                可用 Worker Agent 能力：
                - consultation_agent：健康咨询、常见病科普、生活方式建议、初步风险提示
                - diagnostic_agent：复杂症状分析、风险分层、鉴别诊断推理
                - research_agent：临床指南、诊疗规范、医学证据检索

                分配原则：
                1. 能由 1 个 Agent 完成就只分配 1 个。
                2. 多个症状、高危信号或需要指南证据时，拆成可并行执行的独立子任务。
                3. 每个子任务必须包含 description 和 assigned_agent。
                4. 只返回 JSON，不要返回解释。

                输出格式：
                {"subtasks":[{"description":"...","assigned_agent":"consultation_agent"}]}

                用户问题：%s

                上下文：%s
                """.formatted(question, context == null ? Map.of() : context);
    }

    private List<SwarmSubtask> parseSubtasks(String response) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(response));
            JsonNode subtasksNode = root.path("subtasks");
            if (!subtasksNode.isArray()) {
                return List.of();
            }
            List<SwarmSubtask> subtasks = new ArrayList<>();
            int index = 1;
            for (JsonNode node : subtasksNode) {
                String description = node.path("description").asText("").trim();
                String assignedAgent = node.path("assigned_agent").asText("").trim();
                if (description.isBlank() || !WORKER_CAPABILITIES.containsKey(assignedAgent)) {
                    continue;
                }
                subtasks.add(new SwarmSubtask(String.valueOf(index++), description, assignedAgent));
            }
            return subtasks;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse LeadAgent decomposition", exception);
        }
    }

    private String extractJson(String response) {
        String content = response == null ? "" : response.trim();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("No JSON object found");
        }
        return content.substring(start, end + 1);
    }

    private SwarmSubtask fallbackSubtask(String question) {
        String description = "回答用户问题并提供安全的健康建议：" + (question == null ? "" : question);
        return new SwarmSubtask("1", description, "consultation_agent");
    }
}
