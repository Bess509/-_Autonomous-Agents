package com.medix.swarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.medix.nlu.EmergencyRiskDetector;
import com.medix.nlu.IntentLabel;
import com.medix.nlu.NluClassifier;
import com.medix.nlu.NluProperties;
import com.medix.nlu.NluResult;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SwarmRouterTest {
    @Test
    void routesHighConfidenceSingleLabelDirectly() {
        RouteDecision decision = router(scores(0.86, 0.10, 0.08, 0.05, 0.03, 0.20)).route("怎么改善睡眠");

        assertThat(decision.mode()).isEqualTo(RouteMode.SINGLE_AGENT);
        assertThat(decision.primaryAgent()).isEqualTo("consultation_agent");
        assertThat(decision.requiresLeadAgent()).isFalse();
    }

    @Test
    void routesHighConfidenceMultipleLabelsToWorkers() {
        RouteDecision decision = router(scores(0.10, 0.95, 0.81, 0.77, 0.05, 0.04)).route("症状和指南");

        assertThat(decision.mode()).isEqualTo(RouteMode.SWARM);
        assertThat(decision.requiredAgents()).containsExactly("diagnostic_agent", "research_agent");
        assertThat(decision.requiresLeadAgent()).isFalse();
    }

    @Test
    void fallsBackOnLowConfidence() {
        RouteDecision decision = router(scores(0.48, 0.42, 0.20, 0.10, 0.10, 0.10)).route("不太确定的问题");

        assertThat(decision.requiresLeadAgent()).isTrue();
        assertThat(decision.reason()).isEqualTo("nlu_low_confidence_or_ambiguous");
    }

    @Test
    void emergencyRuleHasPriorityOverClassifier() {
        NluClassifier exploding = text -> { throw new AssertionError("classifier must not be called"); };
        RouteDecision decision = new SwarmRouter(exploding, new EmergencyRiskDetector(), properties()).route("突发胸痛并且呼吸困难");

        assertThat(decision.reason()).isEqualTo("emergency_rule");
        assertThat(decision.requiredAgents()).contains("diagnostic_agent");
        assertThat(decision.requiresLeadAgent()).isFalse();
    }

    @Test
    void ignoresNegatedEmergencySignal() {
        RouteDecision decision = router(scores(0.88, 0.10, 0.05, 0.04, 0.03, 0.20))
                .route("目前没有胸痛，只是想了解日常保健");

        assertThat(decision.reason()).isEqualTo("nlu_high_confidence_single");
    }

    @Test
    void closeLabelsForSameAgentDoNotTriggerAmbiguityFallback() {
        RouteDecision decision = router(scores(0.82, 0.10, 0.05, 0.04, 0.03, 0.78))
                .route("健康咨询和生活方式建议");

        assertThat(decision.mode()).isEqualTo(RouteMode.SINGLE_AGENT);
        assertThat(decision.primaryAgent()).isEqualTo("consultation_agent");
        assertThat(decision.requiresLeadAgent()).isFalse();
    }

    @Test
    void classifierExceptionFallsBackToLeadAgent() {
        NluClassifier exploding = text -> { throw new IllegalStateException("ollama unavailable"); };
        RouteDecision decision = new SwarmRouter(exploding, new EmergencyRiskDetector(), properties()).route("普通问题");

        assertThat(decision.requiresLeadAgent()).isTrue();
        assertThat(decision.reason()).isEqualTo("nlu_unavailable");
    }

    @Test
    void disabledNluBypassesClassifierAndUsesLeadAgent() {
        NluClassifier exploding = text -> { throw new AssertionError("classifier must not be called"); };
        NluProperties disabled = new NluProperties(false, "http://localhost:11434", "test", Duration.ofSeconds(1),
                0.70, 0.55, 0.10, 0.30);

        RouteDecision decision = new SwarmRouter(exploding, new EmergencyRiskDetector(), disabled).route("普通问题");

        assertThat(decision.requiresLeadAgent()).isTrue();
        assertThat(decision.reason()).isEqualTo("nlu_disabled");
        assertThat(decision.probabilities()).isEmpty();
    }

    @Test
    void currentHeadacheSuppressesDriftingDiseaseCodeAndUsesDiagnosticRoute() {
        RouteDecision decision = router(scores(0.02, 0.72, 0.65, 0.01, 0.98, 0.01)).route("我有点头痛");

        assertThat(decision.primaryAgent()).isEqualTo("diagnostic_agent");
        assertThat(decision.requiredAgents()).containsExactly("diagnostic_agent");
        assertThat(decision.reason()).isEqualTo("symptom_policy_disease_code_suppressed");
    }

    @Test
    void explicitIcdQuestionCanUseDiseaseCodeIntent() {
        RouteDecision decision = router(scores(0.02, 0.10, 0.05, 0.01, 0.98, 0.01))
                .route("头痛对应什么 ICD-10 编码");

        assertThat(decision.primaryAgent()).isEqualTo("diagnostic_agent");
        assertThat(decision.reason()).isEqualTo("nlu_high_confidence_single");
    }

    @Test
    void severeHeadacheWithNeurologicDeficitIsEmergencyButMildHeadacheIsNot() {
        EmergencyRiskDetector detector = new EmergencyRiskDetector();
        assertThat(detector.isEmergency("突发剧烈头痛，伴一侧无力和意识异常")).isTrue();
        assertThat(detector.isEmergency("我有点头痛")).isFalse();
        assertThat(detector.isEmergency("没有剧烈头痛，也没有一侧无力")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "没有胸痛，只是轻微不适",
            "没有剧烈头痛，只是有点轻微不适",
            "否认呼吸困难，也未出现意识不清"
    })
    void emergencyDetectorIgnoresLocallyNegatedRedFlags(String input) {
        assertThat(new EmergencyRiskDetector().isEmergency(input)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "现在呼吸困难",
            "没有胸痛，但现在呼吸困难",
            "没有剧烈头痛，不过随后突发剧烈头痛"
    })
    void emergencyDetectorKeepsLaterAffirmedRedFlags(String input) {
        assertThat(new EmergencyRiskDetector().isEmergency(input)).isTrue();
    }

    private SwarmRouter router(NluResult result) {
        return new SwarmRouter(text -> result, new EmergencyRiskDetector(), properties());
    }

    private NluProperties properties() {
        return new NluProperties(true, "http://localhost:11434", "test", Duration.ofSeconds(1), 0.70, 0.55, 0.10, 0.30);
    }

    private NluResult scores(double health, double symptom, double risk, double guideline, double code, double lifestyle) {
        EnumMap<IntentLabel, Double> values = new EnumMap<>(IntentLabel.class);
        values.put(IntentLabel.HEALTH_CONSULTATION, health);
        values.put(IntentLabel.SYMPTOM_ANALYSIS, symptom);
        values.put(IntentLabel.RISK_ASSESSMENT, risk);
        values.put(IntentLabel.GUIDELINE_SEARCH, guideline);
        values.put(IntentLabel.DISEASE_CODE, code);
        values.put(IntentLabel.LIFESTYLE_ADVICE, lifestyle);
        return new NluResult(values);
    }
}
