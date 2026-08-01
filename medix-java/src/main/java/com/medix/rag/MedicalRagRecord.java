package com.medix.rag;

import java.util.Map;

public record MedicalRagRecord(String id, String question, String answer, double vectorScore, Map<String, Object> metadata) {
    public KnowledgeSnippet toSnippet(double score) {
        return new KnowledgeSnippet(id, question, answer, score, metadata);
    }
}
