package com.medix.swarm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SwarmRouterTest {
    private final SwarmRouter router = new SwarmRouter();

    @Test
    void routesSimpleQuestionToSingleAgent() {
        RouteDecision decision = router.route("多喝水有什么好处？");

        assertThat(decision.mode()).isEqualTo(RouteMode.SINGLE_AGENT);
        assertThat(decision.primaryAgent()).isEqualTo("consultation_agent");
    }

    @Test
    void routesComplexHighRiskQuestionToSwarm() {
        RouteDecision decision = router.route("52岁男性高血压多年，胸痛、呼吸困难，还想了解指南证据。");

        assertThat(decision.mode()).isEqualTo(RouteMode.SWARM);
        assertThat(decision.requiredAgents()).contains("consultation_agent", "diagnostic_agent", "research_agent");
    }
}
