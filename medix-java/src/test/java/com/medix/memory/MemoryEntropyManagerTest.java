package com.medix.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryEntropyManagerTest {
    private final MemoryEntropyManager manager = new MemoryEntropyManager();

    @Test
    void deduplicatesMessagesUsingRoleAndContentMd5() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "same content"),
                new ChatMessage("user", "same content"),
                new ChatMessage("assistant", "same content")
        );

        EntropyManagementResult result = manager.autoClean("s1", messages);

        assertThat(result.messages()).containsExactly(
                new ChatMessage("user", "same content"),
                new ChatMessage("assistant", "same content")
        );
        assertThat(result.removedDuplicates()).isEqualTo(1);
        assertThat(result.report().duplicateCount()).isZero();
    }

    @Test
    void compressesOlderMessagesAndPreservesLatestTen() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            messages.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", "message " + i));
        }

        EntropyManagementResult result = manager.autoClean("s1", messages);

        assertThat(result.messages()).hasSize(11);
        assertThat(result.messages().getFirst().role()).isEqualTo("system");
        assertThat(result.messages().getFirst().content()).contains("Conversation summary");
        assertThat(result.messages().subList(1, 11)).containsExactlyElementsOf(messages.subList(4, 14));
        assertThat(result.compressedMessages()).isEqualTo(3);
    }

    @Test
    void extractsQuestionsSymptomsAndRecommendationsIntoSummary() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", "The user reports chest pain and breathing difficulty after exercise."));
        messages.add(new ChatMessage("assistant", "Important recommendation: seek urgent care and do not delay."));
        for (int i = 0; i < 10; i++) {
            messages.add(new ChatMessage("user", "recent message " + i));
        }

        EntropyManagementResult result = manager.autoClean("s1", messages);

        String summary = result.messages().getFirst().content();
        assertThat(summary).contains("questions=");
        assertThat(summary).contains("symptoms=");
        assertThat(summary).contains("recommendations=");
        assertThat(summary).contains("chest pain");
        assertThat(summary).contains("urgent care");
    }

    @Test
    void estimatesEntropyFromCountDuplicateRateAndAverageLength() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            messages.add(new ChatMessage("tool", "same tool result"));
        }

        EntropyReport report = manager.estimate(messages);

        assertThat(report.totalMessages()).isEqualTo(55);
        assertThat(report.uniqueMessages()).isEqualTo(1);
        assertThat(report.duplicateCount()).isEqualTo(54);
        assertThat(report.duplicateRate()).isGreaterThan(0.9);
        assertThat(report.entropyLevel()).isEqualTo(EntropyLevel.HIGH);
        assertThat(report.recommendations()).isNotEmpty();
    }
}
