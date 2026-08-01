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

    public SearchKnowledgeSkill() { this.knowledgeBaseService = null; }

    @Autowired
    public SearchKnowledgeSkill(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override public String name() { return "search_knowledge"; }

    @Override public String description() {
        return "检索高置信度医学知识库；仅返回达到相关性阈值的资料";
    }

    @Override
    public SkillResult invoke(SkillRequest request) {
        if (knowledgeBaseService != null) {
            List<KnowledgeSnippet> snippets = knowledgeBaseService.retrieve(request.query(), 3);
            if (!snippets.isEmpty()) {
                String content = snippets.stream()
                        .map(snippet -> "- " + snippet.title() + ": " + snippet.content().replaceAll("\\s+", " ").trim())
                        .reduce("知识库摘要：\n", (left, right) -> left + right + "\n");
                return SkillResult.success(name(), content, Map.of(
                        "source", "medical-rag", "hits", snippets.size(),
                        "evidenceStatus", "RELIABLE_RAG_EVIDENCE"));
            }
        }
        return SkillResult.success(name(),
                "RAG_NO_RELIABLE_EVIDENCE：没有达到相关性阈值的医学知识库资料；请勿将任何文档作为证据引用。",
                Map.of("source", "medical-rag", "hits", 0, "evidenceStatus", "RAG_NO_RELIABLE_EVIDENCE"));
    }
}
