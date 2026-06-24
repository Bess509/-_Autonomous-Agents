package com.medix.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MessageWindowReducer {
    private final int roundsToKeep;

    public MessageWindowReducer() {
        this(5);
    }

    public MessageWindowReducer(int roundsToKeep) {
        this.roundsToKeep = roundsToKeep;
    }

    public List<ChatMessage> reduce(List<ChatMessage> messages) {
        List<ChatMessage> unique = deduplicate(messages);
        int keepMessages = roundsToKeep * 2;
        if (unique.size() <= keepMessages) {
            return unique;
        }
        List<ChatMessage> older = unique.subList(0, unique.size() - keepMessages);
        List<ChatMessage> recent = unique.subList(unique.size() - keepMessages, unique.size());
        String summary = older.stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + " " + right)
                .trim();
        List<ChatMessage> reduced = new ArrayList<>();
        reduced.add(new ChatMessage("system", "历史摘要：" + abbreviate(summary, 260)));
        reduced.addAll(recent);
        return reduced;
    }

    private List<ChatMessage> deduplicate(List<ChatMessage> messages) {
        List<ChatMessage> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ChatMessage message : messages) {
            String key = message.role() + ":" + message.content();
            if (seen.add(key)) {
                unique.add(message);
            }
        }
        return unique;
    }

    private String abbreviate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
