package com.medix.skill;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class Icd10CodeSkill implements MedicalSkill {
    @Override
    public String name() {
        return "disease_code";
    }

    @Override
    public String description() {
        return "查询常见疾病 ICD-10 编码和分类。";
    }

    @Override
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(
                name(),
                "ICD-10 参考：高血压常见编码 I10；胸痛可参考 R07.4；最终编码需由医生结合诊断确定。",
                Map.of("category", "icd10")
        );
    }
}
