package com.medix.nlu;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EmergencyRiskDetector {
    private static final List<String> SIGNALS = List.of(
            "胸痛", "胸口压榨", "呼吸困难", "喘不上气", "意识不清", "昏迷",
            "大出血", "抽搐", "自杀", "轻生", "chest pain", "difficulty breathing",
            "unconscious", "suicide"
    );
    public boolean isEmergency(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (SIGNALS.stream().anyMatch(signal -> NegationAwareSignalMatcher.containsNonNegated(text, signal))) {
            return true;
        }
        boolean severeHeadache = NegationAwareSignalMatcher.containsNonNegated(text, "突发剧烈头痛")
                || NegationAwareSignalMatcher.containsNonNegated(text, "最严重的头痛")
                || (NegationAwareSignalMatcher.containsNonNegated(text, "剧烈头痛")
                    && NegationAwareSignalMatcher.containsAnyNonNegated(text,
                        "一侧无力", "肢体无力", "偏瘫", "意识异常", "意识不清"));
        return severeHeadache;
    }
}
