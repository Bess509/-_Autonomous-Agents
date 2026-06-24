package com.medix.skill;

import com.medix.rag.KnowledgeBaseService;
import com.medix.rag.KnowledgeSnippet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SearchKnowledgeSkill implements MedicalSkill {
    private final KnowledgeBaseService knowledgeBaseService;

    public SearchKnowledgeSkill() {
        this.knowledgeBaseService = null;
    }

    @Autowired
    public SearchKnowledgeSkill(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

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
        if (knowledgeBaseService != null) {
            List<KnowledgeSnippet> snippets = knowledgeBaseService.retrieve(request.query(), 3);
            if (!snippets.isEmpty()) {
                String content = snippets.stream()
                        .map(snippet -> "- " + snippet.title() + ": " + snippet.content().replaceAll("\\s+", " ").trim())
                        .reduce("知识库摘要：\n", (left, right) -> left + right + "\n");
                return SkillResult.success(
                        name(),
                        content,
                        Map.of("source", "bundled-rag", "hits", snippets.size())
                );
            }
        }
        return SkillResult.success(
                name(),
                "知识库摘要：与问题相关的医学知识包括症状识别、风险分层、生活方式管理和及时就医建议。",
                Map.of("source", "bundled-knowledge")
        );
    }
}
