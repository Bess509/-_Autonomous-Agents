package com.medix.rag;

import java.util.Map;

public record KnowledgeSnippet(
        String id,
        String title,
        String content,
        double score,
        Map<String, Object> metadata
) {
    public KnowledgeSnippet withScore(double newScore) {
        return new KnowledgeSnippet(id, title, content, newScore, metadata);
    }
}
