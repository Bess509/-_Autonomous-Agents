package com.medix.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {
    private static final List<String> MEDICAL_TERMS = List.of(
            "胸痛", "呼吸困难", "高血压", "发热", "头痛", "昏厥", "糖尿病", "指南", "证据", "生活方式"
    );

    private final ResourcePatternResolver resourceResolver;
    private final RerankerClient rerankerClient;
    private final List<TextSegment> segments = new CopyOnWriteArrayList<>();

    public KnowledgeBaseService(ResourcePatternResolver resourceResolver, RerankerClient rerankerClient) {
        this.resourceResolver = resourceResolver;
        this.rerankerClient = rerankerClient;
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
        List<KnowledgeSnippet> ranked = segments.stream()
                .map(segment -> toSnippet(query, segment))
                .filter(snippet -> snippet.score() > 0.0)
                .sorted(Comparator.comparingDouble(KnowledgeSnippet::score).reversed())
                .limit(Math.max(limit * 3L, limit))
                .toList();
        return rerankerClient.rerank(query, ranked).stream().limit(limit).toList();
    }

    public void addDocument(String title, String content, String source) {
        List<String> chunks = split(content, 700);
        for (int i = 0; i < chunks.size(); i++) {
            Metadata metadata = Metadata.from(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "title", title,
                    "source", source,
                    "chunk", i
            ));
            segments.add(TextSegment.from(chunks.get(i), metadata));
        }
    }

    public int size() {
        return segments.size();
    }

    private KnowledgeSnippet toSnippet(String query, TextSegment segment) {
        Map<String, Object> metadata = segment.metadata().toMap();
        return new KnowledgeSnippet(
                String.valueOf(metadata.get("id")),
                String.valueOf(metadata.get("title")),
                segment.text(),
                score(query, segment.text()),
                metadata
        );
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
}
