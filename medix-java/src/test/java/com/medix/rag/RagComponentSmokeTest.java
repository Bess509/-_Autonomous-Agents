package com.medix.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medix.rag.entity.EntityCategory;
import com.medix.rag.entity.MedicalEntities;
import com.medix.rag.entity.MedicalEntityExtractor;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Dependency-free executable smoke test for environments where the legacy test output is locked. */
public final class RagComponentSmokeTest {
    public static void main(String[] args) throws Exception {
        verifiesAcExtractionAndLongestMatch();
        verifiesEntityReranking();
        verifiesBgeProtocol();
        System.out.println("RAG_COMPONENT_SMOKE_TEST_PASSED");
    }

    private static void verifiesAcExtractionAndLongestMatch() {
        MedicalEntityExtractor extractor = new MedicalEntityExtractor();
        extractor.initialize();
        MedicalEntities entities = extractor.extract("服用二甲双胍缓释片后空腹血糖还是8.5");
        require(entities.values(EntityCategory.DRUG).equals(List.of("二甲双胍缓释片")), "longest drug match failed");
        require(entities.values(EntityCategory.EXAMINATION).equals(List.of("空腹血糖")), "examination extraction failed");
    }

    private static void verifiesEntityReranking() {
        MedicalEntities query = new MedicalEntities(Map.of(
                EntityCategory.DRUG, List.of("二甲双胍"), EntityCategory.DISEASE, List.of(),
                EntityCategory.SYMPTOM, List.of(), EntityCategory.EXAMINATION, List.of()));
        Map<String, Object> matched = Map.of("medical", Map.of("entity_tags", List.of("二甲双胍")));
        Map<String, Object> missed = Map.of("medical", Map.of("entity_tags", List.of("阿卡波糖")));
        List<KnowledgeSnippet> results = new MedicalRagReranker().rerank(List.of(
                new MedicalRagRecord("semantic-only", "q", "a", 0.90, missed),
                new MedicalRagRecord("entity-match", "q", "a", 0.85, matched)), query, 2);
        require(results.getFirst().id().equals("entity-match"), "entity reranking failed");
    }

    private static void verifiesBgeProtocol() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", exchange -> {
            String vector = "0.1,".repeat(1023) + "0.1";
            byte[] body = ("{\"embeddings\":[[" + vector + "]]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            MedicalRagEmbeddingClient client = new MedicalRagEmbeddingClient(HttpClient.newHttpClient(), new ObjectMapper(),
                    "http://localhost:" + server.getAddress().getPort(), "bge-m3", 1024, Duration.ofSeconds(2));
            require(client.embedQuery("二甲双胍副作用").length == 1024, "BGE vector validation failed");
        } finally {
            server.stop(0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
