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
        String query = request.query() == null ? "" : request.query();
        if (query.contains("头痛")) {
            return SkillResult.success(
                    name(),
                    "头痛信息尚不完整：需要核对开始时间、持续时间、强度及是否突然发生，并留意发热、反复呕吐、视力变化、肢体无力或麻木、意识变化等伴随表现。",
                    Map.of("category", "headache-symptom-analysis")
            );
        }
        return SkillResult.success(
                name(),
                "症状分析：当前描述涉及心肺系统风险信号，需要结合持续时间、诱因、伴随症状和基础病史综合判断。",
                Map.of("category", "symptom-analysis")
        );
    }
}
