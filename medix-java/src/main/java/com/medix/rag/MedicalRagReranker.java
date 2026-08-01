package com.medix.rag;

import com.medix.rag.entity.MedicalEntities;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Combines semantic retrieval with exact medical-entity matches. */
@Component
public class MedicalRagReranker {
    private static final Logger log = LoggerFactory.getLogger(MedicalRagReranker.class);
    private final double minimumScore;

    public MedicalRagReranker(@Value("${medix.rag.rerank-min-score:0.70}") double minimumScore) {
        this.minimumScore = minimumScore;
    }

    public MedicalRagReranker() { this(0.70); }

    public List<KnowledgeSnippet> rerank(List<MedicalRagRecord> records, MedicalEntities queryEntities, int limit) {
        Set<String> queryTags = Set.copyOf(queryEntities.entityTags());
        List<KnowledgeSnippet> ranked = records.stream().map(record -> {
            Set<String> recordTags = MetadataEntities.tags(record.metadata());
            long matched = queryTags.stream().filter(recordTags::contains).count();
            double entityScore = queryTags.isEmpty() ? 0.0 : (double) matched / queryTags.size();
            return record.toSnippet(0.85 * Math.max(0.0, record.vectorScore()) + 0.15 * entityScore);
        }).filter(snippet -> snippet.score() >= minimumScore)
                .sorted(Comparator.comparingDouble(KnowledgeSnippet::score).reversed()).limit(limit).toList();
        log.info("[RAG_RERANK] candidates={} threshold={} queryTags={} accepted={}", records.size(), minimumScore, queryTags, ranked.size());
        return ranked;
    }
}
