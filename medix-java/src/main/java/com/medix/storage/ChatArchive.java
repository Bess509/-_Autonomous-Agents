package com.medix.storage;

import java.time.Instant;

public record ChatArchive(
        String sessionId,
        String question,
        String answer,
        String routeMode,
        Instant createdAt
) {
}
