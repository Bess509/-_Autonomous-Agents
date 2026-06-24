package com.medix.agent;

import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class FakeModelGateway implements ModelGateway {
    @Override
    public String complete(String systemPrompt, String userPrompt, Map<String, String> skillMetadata) {
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
