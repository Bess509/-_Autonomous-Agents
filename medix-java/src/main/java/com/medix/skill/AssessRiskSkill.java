package com.medix.skill;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AssessRiskSkill implements MedicalSkill {
    @Override
    public String name() {
        return "assess_risk";
    }

    @Override
    public String description() {
        return "评估症状风险等级，识别胸痛、呼吸困难、昏厥、剧烈头痛等高危信号。";
    }

    @Override
    public SkillResult invoke(SkillRequest request) {
        String query = request.query();
        boolean highRisk = containsAny(query, "胸痛", "呼吸困难", "昏厥", "剧烈头痛", "偏瘫", "意识不清");
        if (highRisk) {
            return SkillResult.success(
                    name(),
                    "风险等级：高危。检测到可能需要急诊评估的症状，建议立即就医或拨打 120。",
                    Map.of("riskLevel", "HIGH")
            );
        }
        return SkillResult.success(
                name(),
                "风险等级：中低风险。建议观察症状变化，必要时到正规医疗机构就诊。",
                Map.of("riskLevel", "LOW_OR_MEDIUM")
        );
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
