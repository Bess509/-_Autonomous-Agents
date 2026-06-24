package com.medix.memory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LongTermMemoryService {
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    public LongTermMemoryService(ObjectProvider<JdbcTemplate> jdbcTemplate, EmbeddingService embeddingService) {
        this.jdbcTemplate = jdbcTemplate.getIfAvailable();
        this.embeddingService = embeddingService;
    }

    public void remember(String sessionId, String question, String answer) {
        if (jdbcTemplate == null) {
            return;
        }
        String summary = summarize(question, answer);
        String vector = embeddingService.pgVectorLiteral(embeddingService.embed(question + "\n" + answer));
        try {
            jdbcTemplate.update("""
                    insert into conversation_summaries(session_id, question, answer, summary, embedding)
                    values (?, ?, ?, ?, cast(? as vector))
                    """, sessionId, question, answer, summary, vector);
        } catch (DataAccessException ignored) {
            // The assistant remains usable when pgvector is unavailable.
        }
    }

    public List<ConversationSummary> similarCases(String question, int limit) {
        if (jdbcTemplate == null) {
            return List.of();
        }
        String vector = embeddingService.pgVectorLiteral(embeddingService.embed(question));
        try {
            return jdbcTemplate.query("""
                    select id, session_id, question, summary, created_at
                    from conversation_summaries
                    order by embedding <-> cast(? as vector)
                    limit ?
                    """, (rs, rowNum) -> mapSummary(rs), vector, limit);
        } catch (DataAccessException ignored) {
            return List.of();
        }
    }

    private ConversationSummary mapSummary(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ConversationSummary(
                rs.getLong("id"),
                rs.getString("session_id"),
                rs.getString("question"),
                rs.getString("summary"),
                createdAt == null ? null : createdAt.toInstant()
        );
    }

    private String summarize(String question, String answer) {
        String text = (question == null ? "" : question) + " | " + (answer == null ? "" : answer);
        return text.length() <= 320 ? text : text.substring(0, 320) + "...";
    }
}
