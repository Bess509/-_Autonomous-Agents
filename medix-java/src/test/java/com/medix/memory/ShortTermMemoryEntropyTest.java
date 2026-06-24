package com.medix.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShortTermMemoryEntropyTest {
    @Test
    void autoCleansDuplicateMessagesOnWrite() {
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());

        memory.add("s1", "tool", "same tool result");
        memory.add("s1", "tool", "same tool result");
        memory.add("s1", "assistant", "different answer");

        List<ChatMessage> recent = memory.recent("s1");
        EntropyReport report = memory.entropyReport("s1");

        assertThat(recent).containsExactly(
                new ChatMessage("tool", "same tool result"),
                new ChatMessage("assistant", "different answer")
        );
        assertThat(report.totalMessages()).isEqualTo(2);
        assertThat(report.duplicateCount()).isZero();
    }

    @Test
    void compressesLongSessionAndPreservesLatestTenMessages() {
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());

        for (int i = 0; i < 14; i++) {
            memory.add("long", i % 2 == 0 ? "user" : "assistant", "message " + i);
        }

        List<ChatMessage> recent = memory.recent("long");

        assertThat(recent).hasSize(11);
        assertThat(recent.getFirst().role()).isEqualTo("system");
        assertThat(recent.getFirst().content()).contains("Conversation summary");
        assertThat(recent.subList(1, 11)).containsExactly(
                new ChatMessage("user", "message 4"),
                new ChatMessage("assistant", "message 5"),
                new ChatMessage("user", "message 6"),
                new ChatMessage("assistant", "message 7"),
                new ChatMessage("user", "message 8"),
                new ChatMessage("assistant", "message 9"),
                new ChatMessage("user", "message 10"),
                new ChatMessage("assistant", "message 11"),
                new ChatMessage("user", "message 12"),
                new ChatMessage("assistant", "message 13")
        );
    }

    @Test
    void exposesEntropyOverviewWithoutRawMessageContent() {
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());

        memory.add("private", "user", "very private symptom text");
        Map<String, Object> overview = memory.entropyOverview();

        assertThat(overview).containsKey("trackedSessions");
        assertThat(overview.toString()).doesNotContain("very private symptom text");
    }
}
