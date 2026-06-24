package com.medix.agent;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "medix.features", name = "live-llm", havingValue = "false", matchIfMissing = true)
public class FakeModelGateway implements ModelGateway {
    @Override
    public String complete(String agentId, String userPrompt, Map<String, String> skillMetadata) {
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
}
