package com.medix.harness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class HarnessValidator {
    private final Map<String, Set<String>> allowedSkills;

    public HarnessValidator() {
        this.allowedSkills = defaults();
    }

    public HarnessValidator(HarnessProperties properties) {
        if (properties.agents() == null || properties.agents().isEmpty()) {
            this.allowedSkills = defaults();
            return;
        }
        this.allowedSkills = new LinkedHashMap<>();
        properties.agents().forEach((agentId, constraint) -> this.allowedSkills.put(
                agentId,
                constraint.allowedSkills() == null ? Set.of() : Set.copyOf(constraint.allowedSkills())
        ));
    }

    public boolean canUseSkill(String agentId, String skillName) {
        return allowedSkills.getOrDefault(agentId, Set.of(skillName)).contains(skillName);
    }

    public Set<String> violations(String agentId, Iterable<String> skillCalls) {
        Set<String> allowed = allowedSkills.get(agentId);
        if (allowed == null) {
            return Set.of();
        }
        return stream(skillCalls)
                .filter(skill -> !allowed.contains(skill))
                .collect(Collectors.toSet());
    }

    public Map<String, Set<String>> allowedSkills() {
        return Map.copyOf(allowedSkills);
    }

    private static Map<String, Set<String>> defaults() {
        return Map.of(
                "consultation_agent", Set.of("search_knowledge", "recommend_lifestyle", "assess_risk"),
                "diagnostic_agent", Set.of("assess_risk", "analyze_symptoms", "disease_code", "clinical_guideline"),
                "research_agent", Set.of("clinical_guideline", "deep_research", "search_knowledge")
        );
    }

    private static java.util.stream.Stream<String> stream(Iterable<String> values) {
        java.util.List<String> copy = new java.util.ArrayList<>();
        values.forEach(copy::add);
        return copy.stream();
    }
}
