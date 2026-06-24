package com.medix.agent;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "medix.features", name = "live-llm", havingValue = "false", matchIfMissing = true)
public class FakeModelGateway implements ModelGateway {
    @Override
    public String complete(String agentId, String userPrompt, Map<String, String> skillMetadata) {
        if ("lead_agent".equals(agentId)) {
            return decomposeForLeadAgent(userPrompt);
        }
        if (hasObservation(userPrompt)) {
            if (userPrompt.contains("风险等级：高危")) {
                return "FINAL:风险等级：高危。已结合工具观察，建议立即就医或拨打 120。";
            }
            if (userPrompt.contains("生活方式") || userPrompt.contains("饮食")) {
                return "FINAL:已结合工具观察，建议优先关注低盐均衡饮食、规律作息、适量运动和持续监测。";
            }
            if (userPrompt.contains("指南摘要")) {
                return "FINAL:已结合指南观察，建议优先识别高危信号，并到正规医疗机构进一步评估。";
            }
            return "FINAL:已结合工具观察形成阶段性健康建议。";
        }
        if ("consultation_agent".equals(agentId)) {
            if (containsAny(userPrompt, "胸痛", "呼吸困难", "chest pain", "breathing difficulty")) {
                return "CALL_SKILL:assess_risk";
            }
            if (containsAny(userPrompt, "生活", "饮食", "lifestyle", "diet")) {
                return "CALL_SKILL:recommend_lifestyle";
            }
            return "CALL_SKILL:search_knowledge";
        }
        if ("research_agent".equals(agentId)) {
            if (userPrompt.contains("指南") || userPrompt.contains("证据")) {
                return "CALL_SKILL:clinical_guideline";
            }
            return "CALL_SKILL:search_knowledge";
        }
        if ("diagnostic_agent".equals(agentId)) {
            if (userPrompt.contains("胸痛") || userPrompt.contains("呼吸困难")) {
                return "CALL_SKILL:assess_risk";
            }
            return "CALL_SKILL:analyze_symptoms";
        }
        if (userPrompt.contains("胸痛") || userPrompt.contains("呼吸困难")) {
            return "CALL_SKILL:assess_risk";
        }
        if (userPrompt.contains("指南")) {
            return "CALL_SKILL:clinical_guideline";
        }
        if (userPrompt.contains("生活") || userPrompt.contains("饮食")) {
            return "CALL_SKILL:recommend_lifestyle";
        }
        return "FINAL:这是基于当前信息的健康建议。";
    }

    private boolean hasObservation(String userPrompt) {
        return userPrompt.contains("Observation ") || userPrompt.contains("已获取的工具观察");
    }

    private String decomposeForLeadAgent(String userPrompt) {
        String question = extractLeadQuestion(userPrompt);
        boolean highRisk = containsAny(question, "胸痛", "呼吸困难", "昏厥", "chest pain", "breathing difficulty");
        boolean research = containsAny(question, "指南", "证据", "最新", "research", "guideline", "evidence");
        boolean lifestyle = containsAny(question, "生活", "饮食", "高血压", "hypertension", "manage");
        if (highRisk || research) {
            StringBuilder subtasks = new StringBuilder("{\"subtasks\":[");
            boolean appended = false;
            if (highRisk) {
                subtasks.append("{\"description\":\"评估症状风险等级和紧急程度\",\"assigned_agent\":\"diagnostic_agent\"}");
                appended = true;
            }
            if (research) {
                if (appended) {
                    subtasks.append(",");
                }
                subtasks.append("{\"description\":\"检索临床指南和医学证据\",\"assigned_agent\":\"research_agent\"}");
                appended = true;
            }
            if (lifestyle || highRisk) {
                if (appended) {
                    subtasks.append(",");
                }
                subtasks.append("{\"description\":\"提供健康咨询和生活方式建议\",\"assigned_agent\":\"consultation_agent\"}");
            }
            return subtasks.append("]}").toString();
        }
        return """
                {"subtasks":[{"description":"回答用户问题并提供安全的健康建议","assigned_agent":"consultation_agent"}]}
                """;
    }

    private String extractLeadQuestion(String userPrompt) {
        String marker = "用户问题：";
        int start = userPrompt.indexOf(marker);
        if (start < 0) {
            return userPrompt;
        }
        int contentStart = start + marker.length();
        int end = userPrompt.indexOf("\n\n上下文：", contentStart);
        if (end < 0) {
            end = userPrompt.length();
        }
        return userPrompt.substring(contentStart, end).trim();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text != null && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
