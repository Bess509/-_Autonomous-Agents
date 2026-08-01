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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class SwarmRouter {
    private static final Logger log = LoggerFactory.getLogger(SwarmRouter.class);
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
            log.warn("[FALLBACK] component=ROUTER reason=nlu_unavailable type={} message={}",
                    exception.getClass().getSimpleName(), safeMessage(exception));
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
        // High-confidence labels can legitimately belong to different specialist agents.
        // They are complementary evidence requests, not an ambiguity to be collapsed by
        // the LeadAgent. Dispatch them together and let the LeadAgent synthesize results.
        if (top1 < properties.confidenceThreshold()) {
            return fallback("nlu_low_confidence", observable);
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
            log.info("[ROUTER] decision=nlu_high_confidence_single agents={} probabilities={}", agents, observable);
            return new RouteDecision(RouteMode.SINGLE_AGENT, agents.getFirst(), agents,
                    "nlu_high_confidence_single", false, observable);
        }
        log.info("[ROUTER] decision=nlu_high_confidence_multi agents={} probabilities={}", agents, observable);
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
        log.warn("[FALLBACK] component=ROUTER reason={} probabilities={}", reason, probabilities);
        return new RouteDecision(RouteMode.SWARM, "lead_agent",
                List.of("consultation_agent", "diagnostic_agent", "research_agent"), reason, true, probabilities);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        String normalized = message == null ? "" : message.replaceAll("\\s+", " ");
        return normalized.substring(0, Math.min(180, normalized.length()));
    }
}
