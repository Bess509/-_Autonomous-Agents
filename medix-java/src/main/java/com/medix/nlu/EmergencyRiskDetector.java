package com.medix.nlu;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class EmergencyRiskDetector {
    private static final List<String> SIGNALS = List.of(
            "胸痛", "胸口压榨", "呼吸困难", "喘不上气", "意识不清", "昏迷",
            "大出血", "抽搐", "自杀", "轻生", "chest pain", "difficulty breathing",
            "unconscious", "suicide"
    );
    private static final List<String> NEGATIONS = List.of(
            "没有", "无", "否认", "未出现", "不伴", "not", "no ", "denies"
    );

    public boolean isEmergency(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return SIGNALS.stream().anyMatch(signal -> containsNonNegated(normalized, signal));
    }

    private boolean containsNonNegated(String text, String signal) {
        int fromIndex = 0;
        while (fromIndex < text.length()) {
            int signalIndex = text.indexOf(signal, fromIndex);
            if (signalIndex < 0) {
                return false;
            }
            String prefix = text.substring(Math.max(0, signalIndex - 8), signalIndex);
            if (NEGATIONS.stream().noneMatch(prefix::contains)) {
                return true;
            }
            fromIndex = signalIndex + signal.length();
        }
        return false;
    }
}
