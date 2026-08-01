package com.medix.rag;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medix.rag.entity.MedicalEntities;
import com.medix.rag.entity.MedicalEntityExtractor;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Imports a JSON array of DX medical QA records only when MEDIX_RAG_IMPORT_FILE is configured. */
@Component
public class MedicalRagImportRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MedicalRagImportRunner.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MedicalEntityExtractor extractor;
    private final MedicalRagEmbeddingClient embeddings;
    private final MedicalRagRepository repository;
    private final String importFile;
    private final int batchSize;

    public MedicalRagImportRunner(MedicalEntityExtractor extractor,
                                  MedicalRagEmbeddingClient embeddings, MedicalRagRepository repository,
                                  @Value("${medix.rag.import-file:}") String importFile,
                                  @Value("${medix.rag.import-batch-size:200}") int batchSize) {
        this.extractor = extractor;
        this.embeddings = embeddings;
        this.repository = repository;
        this.importFile = importFile;
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        if (importFile != null && !importFile.isBlank()) importFile(Path.of(importFile));
    }

    void importFile(Path file) throws Exception {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("DX import file does not exist: " + file);
        try (InputStream input = Files.newInputStream(file); JsonParser parser = objectMapper.getFactory().createParser(input)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) throw new IllegalArgumentException("DX import file must be a JSON array");
            List<JsonNode> batch = new ArrayList<>(batchSize);
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                batch.add(objectMapper.readTree(parser));
                if (batch.size() == batchSize) {
                    importBatch(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) importBatch(batch);
        }
    }

    void importBatch(List<JsonNode> nodes) {
        List<ImportRecord> records = new ArrayList<>(nodes.size());
        for (JsonNode node : nodes) {
            try {
                records.add(toRecord(node));
            } catch (IllegalArgumentException exception) {
                log.warn("Skipping incomplete DX record {}: {}", node.path("id").asText("<unknown>"), exception.getMessage());
            }
        }
        if (records.isEmpty()) return;
        List<double[]> vectors = embeddings.embedDocuments(records.stream().map(ImportRecord::question).toList());
        for (int index = 0; index < records.size(); index++) {
            ImportRecord record = records.get(index);
            repository.upsert(record.externalId(), record.question(), record.answer(), record.metadata(), vectors.get(index), record.contentHash());
        }
    }

    ImportRecord toRecord(JsonNode node) {
        String externalId = required(node, "id", "original_id", "qid", "question_id");
        String question = required(node, "question", "query", "instruction");
        String answer = required(node, "answer", "response", "output");
        MedicalEntities entities = extractor.extract(question + "\n" + answer);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provenance", Map.of("subset", "DX", "original_id", externalId, "author_type", "doctor", "reviewer_type", "doctor"));
        metadata.put("medical", MetadataEntities.medicalMetadata(entities,
                optional(node, "department", "科室"), optional(node, "disease_category", "disease", "疾病分类"),
                optional(node, "question_type", "type", "问题类型")));
        return new ImportRecord(externalId, question, answer, metadata, sha256(question + "\n" + answer));
    }

    private static String required(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        throw new IllegalArgumentException("DX record is missing one of fields: " + String.join(", ", names));
    }

    private static String optional(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record ImportRecord(String externalId, String question, String answer, Map<String, Object> metadata, String contentHash) { }
}
