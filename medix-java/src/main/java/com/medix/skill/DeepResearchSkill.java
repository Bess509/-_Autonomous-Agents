package com.medix.skill;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DeepResearchSkill implements MedicalSkill {
    @Override
    public String name() {
        return "deep_research";
    }

    @Override
    public String description() {
        return "综合知识库、指南和外部证据进行深度医学研究。";
    }

    @Override
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(
                name(),
                "深度研究摘要：综合证据提示复杂症状需要分层评估，优先处理高危信号，再讨论慢病管理和随访策略。",
                Map.of("category", "deep-research")
        );
    }
}
