package com.medix.harness;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class HarnessValidator {
    private final Map<String, Set<String>> allowedSkills = Map.of(
            "consultation_agent", Set.of("search_knowledge", "recommend_lifestyle", "assess_risk"),
            "diagnostic_agent", Set.of("assess_risk", "analyze_symptoms", "disease_code", "clinical_guideline"),
            "research_agent", Set.of("clinical_guideline", "deep_research", "search_knowledge")
    );

    public boolean canUseSkill(String agentId, String skillName) {
        return allowedSkills.getOrDefault(agentId, Set.of(skillName)).contains(skillName);
    }
}
