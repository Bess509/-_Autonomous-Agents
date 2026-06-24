package com.medix.agent;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class LeadAgent {
    public String synthesize(String question, List<AgentResult> results) {
        String body = results.stream()
                .map(result -> "【" + result.agentId() + "】\n" + result.answer())
                .collect(Collectors.joining("\n\n"));
        return "综合问题：" + question + "\n\n" + body;
    }
}
