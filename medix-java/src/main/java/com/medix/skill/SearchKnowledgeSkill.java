package com.medix.skill;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SearchKnowledgeSkill implements MedicalSkill {
    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "检索医学知识库，返回疾病、症状、风险和护理相关信息。";
    }

    @Override
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(
                name(),
                "知识库摘要：与问题相关的医学知识包括症状识别、风险分层、生活方式管理和及时就医建议。",
                Map.of("source", "bundled-knowledge")
        );
    }
}
