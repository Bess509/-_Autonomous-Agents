package com.medix.skill;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ClinicalGuidelineSkill implements MedicalSkill {
    @Override
    public String name() {
        return "clinical_guideline";
    }

    @Override
    public String description() {
        return "检索临床指南和专家共识。";
    }

    @Override
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(
                name(),
                "指南摘要：出现胸痛、呼吸困难等警示症状时，应优先排除急性心血管和呼吸系统紧急情况。",
                Map.of("category", "guideline")
        );
    }
}
