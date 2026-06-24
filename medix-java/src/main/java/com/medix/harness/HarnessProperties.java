package com.medix.harness;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medix.harness")
public record HarnessProperties(Map<String, Constraint> agents) {
    public record Constraint(List<String> allowedSkills, List<String> forbiddenActions) {
    }
}
