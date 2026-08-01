package com.medix.skill;

import java.util.Map;
import org.springframework.stereotype.Component;

/** Conservative fallback used only when reviewed RAG evidence is unavailable or insufficient. */
@Component
public class SafeMedicalGuidanceSkill implements MedicalSkill {
    @Override public String name() { return "safe_medical_guidance"; }

    @Override public String description() {
        return "在资料不足或没有可靠知识库证据时，询问关键补充信息并提供保守医疗引导";
    }

    @Override public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(name(),
                "当前没有可作为知识库依据的可靠资料。请先补充年龄或人群、症状/问题持续时间、严重程度、"
                        + "伴随表现、已采取的处理，以及既往疾病、过敏和正在使用的药物；补充后应重新检索。"
                        + "在此之前只可提供低风险的一般护理与及时就医提示，不提供个体化诊断、处方、剂量或疗程。",
                Map.of("evidenceStatus", "RAG_NO_RELIABLE_EVIDENCE"));
    }
}
