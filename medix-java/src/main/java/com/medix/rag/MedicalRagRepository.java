package com.medix.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MedicalRagRepository {
    private final ObjectProvider<JdbcTemplate> jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MedicalRagRepository(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MedicalRagRecord> search(double[] embedding, int limit) {
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        if (jdbc == null) return List.of();
        String vector = vectorLiteral(embedding);
        return jdbc.query("""
                select id, question, answer, metadata, 1 - (embedding <=> cast(? as vector)) as vector_score
                from medical_rag where confidence = 'high'
                order by embedding <=> cast(? as vector) limit ?
                """, (rows, index) -> new MedicalRagRecord(String.valueOf(rows.getLong("id")), rows.getString("question"),
                rows.getString("answer"), rows.getDouble("vector_score"), readMetadata(rows.getString("metadata"))), vector, vector, limit);
    }

    public void upsert(String externalId, String question, String answer, Map<String, Object> metadata, double[] embedding, String contentHash) {
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        if (jdbc == null) throw new IllegalStateException("PostgreSQL is unavailable for medical RAG import");
        try {
            jdbc.update("""
                    insert into medical_rag(external_id, question, answer, confidence, source, metadata, embedding, content_hash)
                    values (?, ?, ?, 'high', 'DX', cast(? as jsonb), cast(? as vector), ?)
                    on conflict (source, external_id) do update set question = excluded.question, answer = excluded.answer,
                    metadata = excluded.metadata, embedding = excluded.embedding, content_hash = excluded.content_hash, updated_at = now()
                    """, externalId, question, answer, objectMapper.writeValueAsString(metadata), vectorLiteral(embedding), contentHash);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot write medical RAG record " + externalId, exception);
        }
    }

    static String vectorLiteral(double[] vector) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) result.append(',');
            result.append(Double.toString(vector[index]));
        }
        return result.append(']').toString();
    }

    private Map<String, Object> readMetadata(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid medical RAG metadata", exception);
        }
    }
}
