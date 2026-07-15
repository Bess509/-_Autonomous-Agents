package com.medix.nlu;

import java.util.List;
import java.util.Locale;

/** Matches each clinical signal against its own nearby clause, rather than applying a global negation. */
public final class NegationAwareSignalMatcher {
    private static final List<String> NEGATIONS = List.of(
            "没有", "并无", "无", "否认", "未出现", "未见", "不伴", "not", "no ", "denies"
    );
    private static final List<String> CLAUSE_BOUNDARIES = List.of(
            "，", ",", "。", ".", "；", ";", "！", "!", "？", "?", "但", "不过", "然而", "可是", "却"
    );

    private NegationAwareSignalMatcher() {
    }

    public static boolean containsNonNegated(String text, String signal) {
        if (text == null || signal == null || signal.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        String normalizedSignal = signal.toLowerCase(Locale.ROOT);
        int fromIndex = 0;
        while (fromIndex < normalized.length()) {
            int signalIndex = normalized.indexOf(normalizedSignal, fromIndex);
            if (signalIndex < 0) {
                return false;
            }
            int contextStart = Math.max(0, signalIndex - 16);
            String prefix = normalized.substring(contextStart, signalIndex);
            int boundary = lastBoundary(prefix);
            String localPrefix = boundary < 0 ? prefix : prefix.substring(boundary + 1);
            if (NEGATIONS.stream().noneMatch(localPrefix::contains)) {
                return true;
            }
            fromIndex = signalIndex + normalizedSignal.length();
        }
        return false;
    }

    public static boolean containsAnyNonNegated(String text, String... signals) {
        for (String signal : signals) {
            if (containsNonNegated(text, signal)) {
                return true;
            }
        }
        return false;
    }

    private static int lastBoundary(String prefix) {
        int last = -1;
        for (String boundary : CLAUSE_BOUNDARIES) {
            last = Math.max(last, prefix.lastIndexOf(boundary));
        }
        return last;
    }
}
