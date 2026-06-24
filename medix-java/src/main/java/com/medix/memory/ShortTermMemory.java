package com.medix.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ShortTermMemory {
    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();
    private final Map<String, EntropyReport> reports = new ConcurrentHashMap<>();
    private final MessageWindowReducer reducer;
    private final MemoryEntropyManager entropyManager;

    public ShortTermMemory(MessageWindowReducer reducer) {
        this(reducer, new MemoryEntropyManager());
    }

    @Autowired
    public ShortTermMemory(MessageWindowReducer reducer, MemoryEntropyManager entropyManager) {
        this.reducer = reducer;
        this.entropyManager = entropyManager;
    }

    public void add(String sessionId, String role, String content) {
        sessions.compute(sessionId, (ignored, existing) -> {
            List<ChatMessage> messages = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            messages.add(new ChatMessage(role, content));
            EntropyManagementResult result = entropyManager.autoClean(sessionId, messages);
            reports.put(sessionId, result.report());
            return result.messages();
        });
    }

    public List<ChatMessage> recent(String sessionId) {
        return reducer.reduce(List.copyOf(sessions.getOrDefault(sessionId, List.of())));
    }

    public EntropyReport entropyReport(String sessionId) {
        EntropyReport report = reports.get(sessionId);
        if (report != null) {
            return report;
        }
        return entropyManager.estimate(sessions.getOrDefault(sessionId, List.of()));
    }

    public Map<String, Object> entropyOverview() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (EntropyLevel level : EntropyLevel.values()) {
            levels.put(level.name(), 0);
        }
        for (EntropyReport report : reports.values()) {
            levels.computeIfPresent(report.entropyLevel().name(), (ignored, count) -> count + 1);
        }

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("trackedSessions", sessions.size());
        overview.put("entropyLevels", levels);
        overview.put("highEntropySessions", levels.get(EntropyLevel.HIGH.name()));
        return overview;
    }
}
