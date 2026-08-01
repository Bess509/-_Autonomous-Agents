package com.medix.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.medix.rag.entity.EntityCategory;
import com.medix.rag.entity.MedicalEntities;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MedicalRagRerankerTest {
    @Test
    void exactEntityMatchCanPromoteARelevantCandidate() {
        MedicalEntities queryEntities = new MedicalEntities(Map.of(
                EntityCategory.DRUG, List.of("二甲双胍"),
                EntityCategory.DISEASE, List.of(),
                EntityCategory.SYMPTOM, List.of(),
                EntityCategory.EXAMINATION, List.of()));
        Map<String, Object> matching = Map.of("medical", Map.of("entity_tags", List.of("二甲双胍")));
        Map<String, Object> nonMatching = Map.of("medical", Map.of("entity_tags", List.of("阿卡波糖")));
        List<KnowledgeSnippet> ranked = new MedicalRagReranker().rerank(List.of(
                new MedicalRagRecord("semantic-only", "q1", "a1", 0.90, nonMatching),
                new MedicalRagRecord("entity-match", "q2", "a2", 0.85, matching)), queryEntities, 2);

        assertThat(ranked).extracting(KnowledgeSnippet::id).containsExactly("entity-match", "semantic-only");
        assertThat(ranked.getFirst().score()).isEqualTo(0.8725);
    }
}
