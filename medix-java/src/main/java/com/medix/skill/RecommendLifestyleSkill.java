package com.medix.skill;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RecommendLifestyleSkill implements MedicalSkill {
    @Override
    public String name() {
        return "recommend_lifestyle";
    }

    @Override
    public String description() {
        return "提供饮食、运动、睡眠和慢病管理生活方式建议。";
    }

    @Override
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(
                name(),
                "生活方式建议：保持低盐均衡饮食、规律作息、适量运动、记录血压或症状变化，避免自行调整处方药。",
                Map.of("category", "lifestyle")
        );
    }
}
