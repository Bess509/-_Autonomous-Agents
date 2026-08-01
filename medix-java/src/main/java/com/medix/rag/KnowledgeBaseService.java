package com.medix.rag;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.medix.rag.entity.MedicalEntityExtractor;

@Service
public class KnowledgeBaseService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final List<String> MEDICAL_TERMS = List.of(
            "胸痛", "呼吸困难", "高血压", "发热", "头痛", "昏厥", "糖尿病", "指南", "证据", "生活方式"
    );

    private final ResourcePatternResolver resourceResolver;
    private final RerankerClient rerankerClient;
    private final VectorStore vectorStore;
    private final boolean medicalRagEnabled;
    private final MedicalRagEmbeddingClient medicalEmbeddings;
    private final MedicalRagRepository medicalRagRepository;
    private final MedicalEntityExtractor entityExtractor;
    private final MedicalRagReranker medicalRagReranker;
    private final List<Document> documents = new CopyOnWriteArrayList<>();

    public KnowledgeBaseService(ResourcePatternResolver resourceResolver, RerankerClient rerankerClient,
                                ObjectProvider<VectorStore> vectorStoreProvider,
                                @Value("${medix.features.vector-store:false}") boolean vectorEnabled,
                                @Value("${medix.rag.enabled:false}") boolean medicalRagEnabled,
                                MedicalRagEmbeddingClient medicalEmbeddings, MedicalRagRepository medicalRagRepository,
                                MedicalEntityExtractor entityExtractor, MedicalRagReranker medicalRagReranker) {
        this.resourceResolver = resourceResolver;
        this.rerankerClient = rerankerClient;
        this.vectorStore = vectorEnabled ? vectorStoreProvider.getIfAvailable() : null;
        this.medicalRagEnabled = medicalRagEnabled;
        this.medicalEmbeddings = medicalEmbeddings;
        this.medicalRagRepository = medicalRagRepository;
        this.entityExtractor = entityExtractor;
        this.medicalRagReranker = medicalRagReranker;
    }

    @PostConstruct
    public void loadBundledKnowledge() throws IOException {
        Resource[] resources = resourceResolver.getResources("classpath*:knowledge/*.md");
        for (Resource resource : resources) {
            String title = resource.getFilename() == null ? "knowledge" : resource.getFilename().replace(".md", "");
            addDocument(title, resource.getContentAsString(StandardCharsets.UTF_8), "classpath:knowledge/" + resource.getFilename());
        }
    }

    public List<KnowledgeSnippet> retrieve(String query, int limit) {
        if (medicalRagEnabled) {
            List<MedicalRagRecord> candidates = medicalRagRepository.search(
                    medicalEmbeddings.embedQuery(query), Math.max(limit * 4, 20));
            var entities = entityExtractor.extract(query);
            List<KnowledgeSnippet> results = medicalRagReranker.rerank(candidates, entities, limit);
            log.info("[RAG] query={} candidates={} entityTags={} reliableHits={}", compact(query), candidates.size(),
                    entities.entityTags(), results.size());
            return results;
        }
        List<Document> candidates = vectorStore == null
                ? documents
                : vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(Math.max(limit * 3, limit)).build());
        List<KnowledgeSnippet> ranked = candidates.stream()
                .filter(this::governed)
                .map(document -> toSnippet(query, document))
                .filter(snippet -> snippet.score() > 0.0)
                .sorted(Comparator.comparingDouble(KnowledgeSnippet::score).reversed())
                .limit(Math.max(limit * 3L, limit))
                .toList();
        return rerankerClient.rerank(query, ranked).stream().limit(limit).toList();
    }

    public void addDocument(String title, String content, String source) {
        List<String> chunks = split(content, 700);
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = Map.ofEntries(
                    Map.entry("id", UUID.randomUUID().toString()),
                    Map.entry("title", title),
                    Map.entry("source", source),
                    Map.entry("chunkId", title + "-" + i),
                    Map.entry("guidelineVersion", "bundled-1"),
                    Map.entry("publishedAt", "2026-01-01T00:00:00Z"),
                    Map.entry("reviewedAt", "2026-07-01T00:00:00Z"),
                    Map.entry("evidenceLevel", "INTERNAL_REFERENCE"),
                    Map.entry("reviewStatus", "APPROVED"),
                    Map.entry("tenantId", "public")
            );
            Document document = new Document(UUID.randomUUID().toString(), chunks.get(i), metadata);
            documents.add(document);
            if (vectorStore != null) vectorStore.add(List.of(document));
        }
    }

    public int size() {
        return documents.size();
    }

    private KnowledgeSnippet toSnippet(String query, Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new KnowledgeSnippet(
                String.valueOf(metadata.get("id")),
                String.valueOf(metadata.get("title")),
                document.getText(),
                document.getScore() == null ? score(query, document.getText()) : document.getScore(),
                metadata
        );
    }

    private boolean governed(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        if (!"APPROVED".equals(metadata.get("reviewStatus"))) return false;
        if (!"public".equals(metadata.get("tenantId"))) return false;
        Object reviewed = metadata.get("reviewedAt");
        try {
            return reviewed != null && Instant.parse(String.valueOf(reviewed)).isAfter(Instant.now().minusSeconds(366L * 86400));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private double score(String query, String text) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String normalizedText = text.toLowerCase(Locale.ROOT);
        double score = 0.0;
        for (String term : MEDICAL_TERMS) {
            if (normalizedQuery.contains(term) && normalizedText.contains(term)) {
                score += 3.0;
            }
        }
        for (String token : normalizedQuery.split("\\s+|,|，|。|、")) {
            if (!token.isBlank() && normalizedText.contains(token)) {
                score += 1.0;
            }
        }
        return score;
    }

    private List<String> split(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        java.util.ArrayList<String> chunks = new java.util.ArrayList<>();
        for (int start = 0; start < text.length(); start += maxChars) {
            chunks.add(text.substring(start, Math.min(text.length(), start + maxChars)));
        }
        return chunks;
    }

    private String compact(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "…";
    }
}
