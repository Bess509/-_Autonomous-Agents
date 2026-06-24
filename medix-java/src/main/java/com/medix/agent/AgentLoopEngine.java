package com.medix.agent;

import com.medix.harness.OutputRepairService;
import com.medix.memory.ChatMessage;
import com.medix.memory.ShortTermMemory;
import com.medix.skill.SkillRegistry;
import com.medix.skill.SkillRequest;
import com.medix.skill.SkillResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AgentLoopEngine {
    private final SkillRegistry skillRegistry;
    private final ShortTermMemory memory;
    private final OutputRepairService repairService;
    private final ModelGateway modelGateway;
    private final int maxIterations;
    private final int maxSkillCalls;

    @Autowired
    public AgentLoopEngine(
            SkillRegistry skillRegistry,
            ShortTermMemory memory,
            OutputRepairService repairService,
            ModelGateway modelGateway
    ) {
        this(skillRegistry, memory, repairService, modelGateway, 5, 3);
    }

    public AgentLoopEngine(
            SkillRegistry skillRegistry,
            ShortTermMemory memory,
            OutputRepairService repairService,
            int maxIterations,
            int maxSkillCalls
    ) {
        this(skillRegistry, memory, repairService, new FakeModelGateway(), maxIterations, maxSkillCalls);
    }

    public AgentLoopEngine(
            SkillRegistry skillRegistry,
            ShortTermMemory memory,
            OutputRepairService repairService,
            ModelGateway modelGateway,
            int maxIterations,
            int maxSkillCalls
    ) {
        this.skillRegistry = skillRegistry;
        this.memory = memory;
        this.repairService = repairService;
        this.modelGateway = modelGateway;
        this.maxIterations = maxIterations;
        this.maxSkillCalls = maxSkillCalls;
    }

    public AgentResult run(String agentId, AgentRequest request) {
        memory.add(request.sessionId(), "user", request.question());
        List<String> skillCalls = new ArrayList<>();
        String answer = "FINAL:这是基于当前信息的健康建议。";
        int iterations = 0;

        for (; iterations < maxIterations; iterations++) {
            String prompt = buildPrompt(request);
            String decision = modelGateway.complete(agentId, prompt, skillRegistry.metadata());
            if (decision.startsWith("CALL_SKILL:") && skillCalls.size() < maxSkillCalls) {
                String skillName = decision.substring("CALL_SKILL:".length());
                SkillResult result = skillRegistry.invoke(skillName, new SkillRequest(request.question(), request.sessionId(), request.context()));
                skillCalls.add(skillName);
                answer = "FINAL:" + result.content();
                break;
            }
            answer = decision;
            break;
        }

        String finalAnswer = answer.replaceFirst("^FINAL:", "");
        finalAnswer = repairService.repair(finalAnswer);
        memory.add(request.sessionId(), "assistant", finalAnswer);
        return new AgentResult(agentId, finalAnswer, iterations + 1, skillCalls);
    }

    private String buildPrompt(AgentRequest request) {
        List<ChatMessage> recent = memory.recent(request.sessionId());
        StringBuilder prompt = new StringBuilder(request.question());
        if (!recent.isEmpty()) {
            prompt.append("\n\n历史上下文：");
            recent.forEach(message -> prompt.append("\n").append(message.role()).append(": ").append(message.content()));
        }
        if (!request.context().isEmpty()) {
            prompt.append("\n\n附加上下文：").append(request.context());
        }
        return prompt.toString();
    }
}
