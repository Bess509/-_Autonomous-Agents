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
        sessions.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(new ChatMessage(role, content));
    }

    public List<ChatMessage> recent(String sessionId) {
        return reducer.reduce(sessions.getOrDefault(sessionId, List.of()));
    }
}
