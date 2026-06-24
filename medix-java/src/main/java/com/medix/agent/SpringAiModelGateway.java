package com.medix.agent;

import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "medix.features", name = "live-llm", havingValue = "true")
public class SpringAiModelGateway implements ModelGateway {
    private final ChatClient chatClient;

    public SpringAiModelGateway(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String complete(String agentId, String userPrompt, Map<String, String> skillMetadata) {
        String systemPrompt = """
                You are a medical ReAct agent. Decide exactly one next step.
                Follow a Think-Act-Observe loop, but only emit the action or final answer.
                Return CALL_SKILL:<skill_name> when a skill is needed.
                Return FINAL:<answer> when enough evidence is available.
                Treat Observation sections in the user prompt as tool results from earlier loop rounds.
                Do not repeat the same skill unless the latest observation clearly requires it.
                Agent id: %s
                Available skills: %s
                Never provide a definitive diagnosis or prescription.
                """.formatted(agentId, skillMetadata);
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }
}
