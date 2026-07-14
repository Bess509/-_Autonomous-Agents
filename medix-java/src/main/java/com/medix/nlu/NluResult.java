package com.medix.nlu;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record NluResult(Map<IntentLabel, Double> probabilities) {
    public NluResult {
        EnumMap<IntentLabel, Double> copy = new EnumMap<>(IntentLabel.class);
        if (probabilities != null) {
            copy.putAll(probabilities);
        }
        probabilities = Collections.unmodifiableMap(copy);
    }

    public double probability(IntentLabel label) {
        return probabilities.getOrDefault(label, 0.0);
    }
}
