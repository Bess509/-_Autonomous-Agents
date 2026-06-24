package com.medix.memory;

import java.util.List;

public record EntropyReport(
        int totalMessages,
        int uniqueMessages,
        int duplicateCount,
        double duplicateRate,
        double averageMessageLength,
        EntropyLevel entropyLevel,
        List<String> recommendations
) {
}
