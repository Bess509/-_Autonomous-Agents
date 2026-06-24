package com.medix.memory;

import java.time.Instant;

public record ConversationSummary(
        long id,
        String sessionId,
        String question,
        String summary,
        Instant createdAt
) {
}
