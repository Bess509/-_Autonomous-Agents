package com.medix.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ShortTermMemory {
    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();
    private final MessageWindowReducer reducer;

    public ShortTermMemory(MessageWindowReducer reducer) {
        this.reducer = reducer;
    }

    public void add(String sessionId, String role, String content) {
        sessions.compute(sessionId, (ignored, existing) -> {
            List<ChatMessage> messages = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            messages.add(new ChatMessage(role, content));
            return messages;
        });
    }

    public List<ChatMessage> recent(String sessionId) {
        return reducer.reduce(List.copyOf(sessions.getOrDefault(sessionId, List.of())));
    }
}
