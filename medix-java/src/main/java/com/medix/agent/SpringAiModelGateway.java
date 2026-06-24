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
                You are a medical ReAct agent. Decide one next step only.
                Return CALL_SKILL:<skill_name> when a skill is needed.
                Return FINAL:<answer> when enough evidence is available.
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
