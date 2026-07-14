package com.medix.swarm;

import com.medix.nlu.EmergencyRiskDetector;
import com.medix.nlu.IntentLabel;
import com.medix.nlu.NluClassifier;
import com.medix.nlu.NluProperties;
import com.medix.nlu.NluResult;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SwarmRouter {
    private static final Map<IntentLabel, String> AGENTS = new EnumMap<>(IntentLabel.class);

    static {
        AGENTS.put(IntentLabel.HEALTH_CONSULTATION, "consultation_agent");
        AGENTS.put(IntentLabel.LIFESTYLE_ADVICE, "consultation_agent");
        AGENTS.put(IntentLabel.SYMPTOM_ANALYSIS, "diagnostic_agent");
        AGENTS.put(IntentLabel.RISK_ASSESSMENT, "diagnostic_agent");
        AGENTS.put(IntentLabel.DISEASE_CODE, "diagnostic_agent");
        AGENTS.put(IntentLabel.GUIDELINE_SEARCH, "research_agent");
    }

    private final NluClassifier classifier;
    private final EmergencyRiskDetector riskDetector;
    private final NluProperties properties;

    public SwarmRouter() {
        this(text -> { throw new IllegalStateException("No NLU classifier configured"); },
                new EmergencyRiskDetector(), NluProperties.disabled());
    }

    @Autowired
    public SwarmRouter(NluClassifier classifier, EmergencyRiskDetector riskDetector, NluProperties properties) {
        this.classifier = classifier;
        this.riskDetector = riskDetector;
        this.properties = properties;
    }

    public RouteDecision route(String question) {
        if (riskDetector.isEmergency(question)) {
            return new RouteDecision(RouteMode.SWARM, "diagnostic_agent",
                    List.of("diagnostic_agent", "consultation_agent"), "emergency_rule", false, Map.of());
        }
        try {
            NluResult result = classifier.classify(question);
            return routeNlu(result);
        } catch (RuntimeException exception) {
            return new RouteDecision(RouteMode.SWARM, "lead_agent",
                    List.of("consultation_agent", "diagnostic_agent", "research_agent"),
                    "nlu_unavailable", true, Map.of());
        }
    }

    private RouteDecision routeNlu(NluResult result) {
        List<Map.Entry<IntentLabel, Double>> ranked = result.probabilities().entrySet().stream()
                .sorted(Map.Entry.<IntentLabel, Double>comparingByValue(Comparator.reverseOrder()))
                .toList();
        Map<String, Double> observable = new LinkedHashMap<>();
        ranked.forEach(entry -> observable.put(entry.getKey().name(), entry.getValue()));
        if (ranked.isEmpty()) {
            return fallback("nlu_empty", observable);
        }
        double top1 = ranked.getFirst().getValue();
        double top2 = ranked.size() > 1 ? ranked.get(1).getValue() : 0.0;
        boolean crossAgentAmbiguity = ranked.size() > 1
                && !Objects.equals(AGENTS.get(ranked.getFirst().getKey()), AGENTS.get(ranked.get(1).getKey()))
                && top2 >= threshold(ranked.get(1).getKey())
                && top1 - top2 < properties.ambiguityMargin();
        if (top1 < properties.confidenceThreshold() || crossAgentAmbiguity) {
            return fallback("nlu_low_confidence_or_ambiguous", observable);
        }
        List<IntentLabel> selected = ranked.stream()
                .filter(entry -> entry.getValue() >= threshold(entry.getKey()))
                .map(Map.Entry::getKey)
                .toList();
        List<String> agents = selected.stream().map(AGENTS::get).filter(Objects::nonNull).distinct().toList();
        if (agents.isEmpty()) {
            return fallback("nlu_no_label_above_threshold", observable);
        }
        if (agents.size() == 1) {
            return new RouteDecision(RouteMode.SINGLE_AGENT, agents.getFirst(), agents,
                    "nlu_high_confidence_single", false, observable);
        }
        return new RouteDecision(RouteMode.SWARM, agents.getFirst(), agents,
                "nlu_high_confidence_multi", false, observable);
    }

    private double threshold(IntentLabel label) {
        return label == IntentLabel.RISK_ASSESSMENT ? properties.riskThreshold() : properties.labelThreshold();
    }

    private RouteDecision fallback(String reason, Map<String, Double> probabilities) {
        return new RouteDecision(RouteMode.SWARM, "lead_agent",
                List.of("consultation_agent", "diagnostic_agent", "research_agent"), reason, true, probabilities);
    }
}
