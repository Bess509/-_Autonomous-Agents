package com.medix.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medix.swarm.SwarmSubtask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class LeadAgent {
    private static final Logger log = LoggerFactory.getLogger(LeadAgent.class);
    private static final Map<String, String> WORKER_CAPABILITIES = Map.of(
            "consultation_agent", "健康咨询、常见病科普、生活方式建议、初步风险提示",
            "diagnostic_agent", "复杂症状分析、风险分层、鉴别诊断参考",
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
        String response = modelGateway.complete("lead_agent", buildDecompositionPrompt(question, context), WORKER_CAPABILITIES);
        try {
            List<SwarmSubtask> subtasks = parseSubtasks(response);
            if (!subtasks.isEmpty()) return subtasks;
        } catch (RuntimeException ignored) {
            // A deterministic safe fallback keeps the original user facts intact.
            log.warn("[FALLBACK] component=LEAD_AGENT reason=decomposition_parse_or_model_failure");
        }
        return List.of(fallbackSubtask(question));
    }

    public String synthesize(String question, List<AgentResult> results) {
        if (modelGateway.live()) {
            try {
                String answer = modelGateway.complete("lead_synthesizer", buildSynthesisPrompt(question, results), Map.of());
                if (answer != null && !answer.isBlank()) return answer.trim();
            } catch (RuntimeException ignored) {
                // Never expose provider details; retain completed evidence in the deterministic fallback below.
                log.warn("[FALLBACK] component=LEAD_AGENT reason=synthesis_model_failure");
            }
        }
        String value = question == null ? "" : question;
        if (value.contains("头痛")) {
            return synthesizeHeadache(value);
        }
        if (value.contains("胸痛") || value.toLowerCase().contains("chest pain")
                || value.contains("呼吸困难") || value.toLowerCase().contains("difficulty breathing")) {
            return "【证据摘要】风险核对提示当前描述含高危信号。\n\n"
                    + "【综合建议】请立即就医或拨打 120，不要等待在线回复；补充症状开始时间和伴随表现只能用于急诊沟通，不能延误救治。";
        }
        String ragEvidence = successfulKnowledgeEvidence(results);
        if (!ragEvidence.isBlank()) {
            return composeOfflineRagAnswer(value, ragEvidence);
        }
        long completed = results == null ? 0 : results.stream().filter(result -> result.answer() != null).count();
        return "【证据摘要】已完成 " + completed + " 项授权医疗能力的内部核对，原始工具输出不会直接作为答复展示。\n\n"
                + "【综合建议】目前信息仍不足以作出诊断。请补充症状开始时间、变化趋势、伴随表现和既往病史；"
                + "在没有紧急信号时可先记录变化，并根据持续或加重情况到正规医疗机构评估。";
    }

    /** Offline synthesis path: preserve reviewed RAG evidence instead of replacing it with a generic fallback. */
    private String successfulKnowledgeEvidence(List<AgentResult> results) {
        if (results == null) return "";
        return results.stream()
                .filter(result -> result != null && result.skillCalls() != null
                        && result.skillCalls().contains("search_knowledge"))
                .map(AgentResult::answer)
                .filter(answer -> answer != null && !answer.isBlank())
                .map(answer -> answer.replaceFirst("^\\s*内部证据贡献[\\uff1a:]\\s*", ""))
                .filter(answer -> !answer.isBlank())
                .findFirst().orElse("");
    }

    private String composeOfflineRagAnswer(String question, String evidence) {
        String reference = primaryKnowledgeAnswer(evidence);
        if (reference.length() > 1200) reference = reference.substring(0, 1200).trim() + "…";
        reference = reference.replaceAll("[。；，、\\s]+$", "");
        String prevention = "请结合自身年龄、症状持续时间、严重程度、既往疾病、过敏史和正在使用的药物谨慎判断，避免自行使用处方药或偏方。";
        String care = question != null && question.contains("新生儿") && question.contains("黄疸")
                ? "如黄染明显加重，或宝宝出现嗜睡、吃奶差、发热等情况，应尽快带宝宝到儿科或新生儿科评估。"
                : "如症状持续、加重或出现明显不适，请及时到正规医疗机构评估。";
        return "[[RAG_SINGLE_PARAGRAPH]]根据高置信度医学知识库，" + reference + "。" + prevention + care;
    }

    private String primaryKnowledgeAnswer(String evidence) {
        String source = evidence.replaceFirst("^\\s*知识库摘要[\\uff1a:]\\s*", "").trim();
        if (source.startsWith("-")) {
            int titleEnd = source.indexOf(":");
            if (titleEnd >= 0) source = source.substring(titleEnd + 1).trim();
            int nextHit = source.indexOf("\n-");
            if (nextHit >= 0) source = source.substring(0, nextHit).trim();
        }
        return source.replaceAll("\\s+", " ").trim();
    }

    private String buildSynthesisPrompt(String question, List<AgentResult> results) {
        StringBuilder evidence = new StringBuilder();
        if (results != null) {
            for (AgentResult result : results) {
                if (result == null || result.answer() == null || result.answer().isBlank()) continue;
                evidence.append("- ").append(result.agentId()).append(": ")
                        .append(result.answer().replaceAll("(?i)authorization\\s*:\\s*bearer\\s+\\S+", "<redacted>"))
                        .append('\n');
            }
        }
        return "原始用户问题：\n" + (question == null ? "" : question)
                + "\n\n已授权 Worker/工具证据：\n" + (evidence.isEmpty() ? "- 无可用证据\n" : evidence)
                + "\n要求：仅依据以上内容生成一份最终回答；保留必要红旗提示，不复述内部流程。";
    }

    private String synthesizeHeadache(String question) {
        boolean urgent = question.contains("突发剧烈头痛") || question.contains("最严重的头痛")
                || ((question.contains("剧烈头痛") || question.contains("突发头痛"))
                    && (question.contains("一侧无力") || question.contains("肢体无力")
                        || question.contains("偏瘫") || question.contains("意识异常") || question.contains("意识不清")));
        String escalation = urgent
                ? "你描述了明确的头痛红旗，需要立刻按紧急情况处理。"
                : "如出现突发最严重头痛、肢体无力或麻木、意识异常，应立即急诊；若头痛持续加重或久不缓解，也应及时就医。";
        return "【证据摘要】你正在经历头痛，但仅凭目前信息不能判断原因，需要先完成症状与风险核对。\n\n"
                + "【综合建议】请记录开始时间、持续时间和强度变化，并观察是否伴有发热、反复呕吐、视力变化、"
                + "肢体无力或麻木、意识变化。若没有上述红旗，可先休息、补充水分，并减少强光和屏幕刺激。"
                + escalation + "这些建议不构成诊断，也不提供处方或个体化用药方案。";
    }

    private String buildDecompositionPrompt(String question, Map<String, Object> context) {
        return """
                你是医疗 Swarm 的 LeadAgent，只负责把用户问题拆成尽可能少的独立子任务。
                可用 Worker Agent：
                - consultation_agent：健康咨询、常见病科普、生活方式建议、初步风险提示
                - diagnostic_agent：复杂症状分析、风险分层、鉴别诊断参考
                - research_agent：临床指南、诊疗规范、医学证据检索

                分配原则：
                1. 能由一个 Agent 完成就只分配一个。
                2. 多个症状、高危信号或需要指南证据时，可拆成并行的独立子任务。
                3. 每个 description 必须保留完成任务所需的原始用户事实，不得改写患者事实。
                4. 只返回 JSON，不要返回解释。

                输出格式：
                {"subtasks":[{"description":"...","assigned_agent":"consultation_agent"}]}

                用户问题：%s

                上下文：%s
                """.formatted(question == null ? "" : question, context == null ? Map.of() : context);
    }

    private List<SwarmSubtask> parseSubtasks(String response) {
        try {
            JsonNode subtasksNode = objectMapper.readTree(extractJson(response)).path("subtasks");
            if (!subtasksNode.isArray()) return List.of();
            List<SwarmSubtask> subtasks = new ArrayList<>();
            int index = 1;
            for (JsonNode node : subtasksNode) {
                String description = node.path("description").asText("").trim();
                String assignedAgent = node.path("assigned_agent").asText("").trim();
                if (!description.isBlank() && WORKER_CAPABILITIES.containsKey(assignedAgent)) {
                    subtasks.add(new SwarmSubtask(String.valueOf(index++), description, assignedAgent));
                }
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
        if (start < 0 || end < start) throw new IllegalArgumentException("No JSON object found");
        return content.substring(start, end + 1);
    }

    private SwarmSubtask fallbackSubtask(String question) {
        return new SwarmSubtask("1", "回答用户问题并提供安全的健康建议：" + (question == null ? "" : question),
                "consultation_agent");
    }
}
