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
        String systemPrompt = switch (agentId) {
            case "lead_agent" -> leadAgentPrompt(skillMetadata);
            case "lead_synthesizer" -> synthesisPrompt();
            default -> workerPrompt(agentId, skillMetadata);
        };
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    @Override
    public boolean live() {
        return true;
    }

    private String synthesisPrompt() {
        return """
                You are MediX's final response synthesizer. Use only the supplied user question and worker/tool evidence.
                Return exactly one concise Chinese medical-information answer, with no JSON, markdown fence, hidden reasoning,
                credentials, internal identifiers, or provider details. Preserve urgent red flags. Never diagnose with certainty
                or prescribe. When evidence is insufficient, say what information is missing and give conservative next steps.
                Do not add more than one medical disclaimer.
                """;
    }

    private String leadAgentPrompt(Map<String, String> workerCapabilities) {
        return """
                You are the medical Swarm LeadAgent.
                Decompose the user problem into the fewest independent worker subtasks.
                Return only JSON with this schema:
                {"subtasks":[{"description":"...","assigned_agent":"consultation_agent"}]}
                Valid assigned_agent values and capabilities: %s
                Do not return ReAct actions. Do not include markdown fences.
                """.formatted(workerCapabilities);
    }

    private String workerPrompt(String agentId, Map<String, String> skillMetadata) {
        return """
                You are a medical ReAct agent. Decide exactly one next step.
                Follow a Think-Act-Observe loop, but only emit the action or final answer.
                Return CALL_SKILL:<skill_name> when a skill is needed.
                Return DELEGATE_AGENT:<agent_id>:<task> when the task needs another Agent's hidden capability.
                Return FINAL:<answer> when enough evidence is available.
                Treat Observation sections in the user prompt as tool results from earlier loop rounds.
                Do not repeat the same skill unless the latest observation clearly requires it.
                Agent id: %s
                Available skills: %s
                Only call skills listed in Available skills.
                If guideline, evidence review, or deep research is needed but not visible, return DELEGATE_AGENT:research_agent:<task>.
                Never provide a definitive diagnosis or prescription.
                """.formatted(agentId, skillMetadata);
    }
}
