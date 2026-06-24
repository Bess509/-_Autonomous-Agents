package com.medix.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageWindowReducerTest {
    @Test
    void deduplicatesAndKeepsLatestFiveRounds() {
        MessageWindowReducer reducer = new MessageWindowReducer(5);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", "重复问题"));
        messages.add(new ChatMessage("user", "重复问题"));
        for (int i = 0; i < 7; i++) {
            messages.add(new ChatMessage("user", "问题 " + i));
            messages.add(new ChatMessage("assistant", "回答 " + i));
        }

        List<ChatMessage> reduced = reducer.reduce(messages);

        assertThat(reduced).hasSizeLessThan(messages.size());
        assertThat(reduced).extracting(ChatMessage::content).contains("问题 6", "回答 6");
        assertThat(reduced).extracting(ChatMessage::content).doesNotHaveDuplicates();
        assertThat(reduced.getFirst().role()).isEqualTo("system");
        assertThat(reduced.getFirst().content()).contains("历史摘要");
    }
}
