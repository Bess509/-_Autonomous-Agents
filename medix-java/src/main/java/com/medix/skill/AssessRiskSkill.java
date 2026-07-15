package com.medix.skill;

import com.medix.nlu.NegationAwareSignalMatcher;
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
        boolean headacheEmergency = NegationAwareSignalMatcher.containsAnyNonNegated(query, "突发剧烈头痛", "最严重的头痛")
                || (NegationAwareSignalMatcher.containsNonNegated(query, "剧烈头痛")
                    && NegationAwareSignalMatcher.containsAnyNonNegated(query,
                        "偏瘫", "一侧无力", "肢体无力", "意识异常", "意识不清"));
        boolean highRisk = NegationAwareSignalMatcher.containsAnyNonNegated(query, "胸痛", "呼吸困难", "昏厥")
                || headacheEmergency;
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
}
