package com.medix.swarm;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SwarmRouter {
    public RouteDecision route(String question) {
        boolean highRisk = question.contains("胸痛") || question.contains("呼吸困难") || question.contains("昏厥");
        boolean research = question.contains("指南") || question.contains("证据") || question.contains("最新");
        boolean complex = question.length() > 35 || highRisk || research;
        if (complex) {
            return new RouteDecision(
                    RouteMode.SWARM,
                    "lead_agent",
                    List.of("consultation_agent", "diagnostic_agent", "research_agent"),
                    "complex_or_high_risk"
            );
        }
        return new RouteDecision(
                RouteMode.SINGLE_AGENT,
                "consultation_agent",
                List.of("consultation_agent"),
                "simple_health_question"
        );
    }
}
