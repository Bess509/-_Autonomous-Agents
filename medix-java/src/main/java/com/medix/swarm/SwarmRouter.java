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
        if (!properties.enabled()) {
            return isCurrentSymptom(question)
                    ? symptomRoute("symptom_policy_nlu_disabled", Map.of())
                    : fallback("nlu_disabled", Map.of());
        }
        try {
            NluResult result = classifier.classify(question);
            return routeNlu(question, result);
        } catch (RuntimeException exception) {
            return new RouteDecision(RouteMode.SWARM, "lead_agent",
                    List.of("consultation_agent", "diagnostic_agent", "research_agent"),
                    "nlu_unavailable", true, Map.of());
        }
    }

    private RouteDecision routeNlu(String question, NluResult result) {
        List<Map.Entry<IntentLabel, Double>> ranked = result.probabilities().entrySet().stream()
                .sorted(Map.Entry.<IntentLabel, Double>comparingByValue(Comparator.reverseOrder()))
                .toList();
        Map<String, Double> observable = new LinkedHashMap<>();
        ranked.forEach(entry -> observable.put(entry.getKey().name(), entry.getValue()));
        if (ranked.isEmpty()) {
            return fallback("nlu_empty", observable);
        }
        boolean explicitCodeRequest = explicitlyRequestsCode(question);
        if (isCurrentSymptom(question) && !explicitCodeRequest) {
            String reason = result.probabilities().getOrDefault(IntentLabel.DISEASE_CODE, 0.0) >= threshold(IntentLabel.DISEASE_CODE)
                    ? "symptom_policy_disease_code_suppressed" : "symptom_policy";
            return symptomRoute(reason, observable);
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
                .filter(label -> label != IntentLabel.DISEASE_CODE || explicitCodeRequest)
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

    private RouteDecision symptomRoute(String reason, Map<String, Double> probabilities) {
        return new RouteDecision(RouteMode.SINGLE_AGENT, "diagnostic_agent",
                List.of("diagnostic_agent"), reason, false, probabilities);
    }

    private boolean explicitlyRequestsCode(String question) {
        if (question == null) return false;
        String normalized = question.toLowerCase();
        return normalized.contains("icd") || normalized.contains("疾病编码")
                || normalized.contains("疾病代码") || normalized.contains("诊断编码");
    }

    private boolean isCurrentSymptom(String question) {
        if (question == null || question.isBlank()) return false;
        String normalized = question.replaceAll("[\\s，。！？,.!?]", "");
        boolean negated = normalized.contains("没有头痛") || normalized.contains("无头痛")
                || normalized.contains("否认头痛") || normalized.contains("只想了解头痛");
        return !negated && (normalized.contains("我头痛") || normalized.contains("我有点头痛")
                || normalized.matches(".*头痛(了|已有|持续|两天|三天|一周).*"));
    }

    private double threshold(IntentLabel label) {
        return label == IntentLabel.RISK_ASSESSMENT ? properties.riskThreshold() : properties.labelThreshold();
    }

    private RouteDecision fallback(String reason, Map<String, Double> probabilities) {
        return new RouteDecision(RouteMode.SWARM, "lead_agent",
                List.of("consultation_agent", "diagnostic_agent", "research_agent"), reason, true, probabilities);
    }
}
