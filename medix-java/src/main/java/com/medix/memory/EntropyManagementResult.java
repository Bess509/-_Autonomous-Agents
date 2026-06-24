package com.medix.memory;

import java.util.List;

public record EntropyManagementResult(
        List<ChatMessage> messages,
        EntropyReport report,
        int removedDuplicates,
        int compressedMessages
) {
}
