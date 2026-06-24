package com.medix.skill;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AnalyzeSymptomsSkill implements MedicalSkill {
    @Override
    public String name() {
        return "analyze_symptoms";
    }

    @Override
    public String description() {
        return "分析症状模式和潜在系统关联。";
    }

    @Override
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(
                name(),
                "症状分析：当前描述涉及心肺系统风险信号，需要结合持续时间、诱因、伴随症状和基础病史综合判断。",
                Map.of("category", "symptom-analysis")
        );
    }
}
