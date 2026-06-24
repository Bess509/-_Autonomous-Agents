package com.medix.agent;

import com.medix.harness.HarnessValidator;
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
    private final HarnessValidator harnessValidator;
    private final int maxIterations;
    private final int maxSkillCalls;

    @Autowired
    public AgentLoopEngine(
            SkillRegistry skillRegistry,
            ShortTermMemory memory,
            OutputRepairService repairService,
            ModelGateway modelGateway,
            HarnessValidator harnessValidator
    ) {
        this(skillRegistry, memory, repairService, modelGateway, harnessValidator, 5, 3);
    }

    public AgentLoopEngine(
            SkillRegistry skillRegistry,
            ShortTermMemory memory,
            OutputRepairService repairService,
            int maxIterations,
            int maxSkillCalls
    ) {
        this(skillRegistry, memory, repairService, new FakeModelGateway(), new HarnessValidator(), maxIterations, maxSkillCalls);
    }

    public AgentLoopEngine(
            SkillRegistry skillRegistry,
            ShortTermMemory memory,
            OutputRepairService repairService,
            ModelGateway modelGateway,
            int maxIterations,
            int maxSkillCalls
    ) {
        this(skillRegistry, memory, repairService, modelGateway, new HarnessValidator(), maxIterations, maxSkillCalls);
    }

    public AgentLoopEngine(
            SkillRegistry skillRegistry,
            ShortTermMemory memory,
            OutputRepairService repairService,
            ModelGateway modelGateway,
            HarnessValidator harnessValidator,
            int maxIterations,
            int maxSkillCalls
    ) {
        this.skillRegistry = skillRegistry;
        this.memory = memory;
        this.repairService = repairService;
        this.modelGateway = modelGateway;
        this.harnessValidator = harnessValidator;
        this.maxIterations = maxIterations;
        this.maxSkillCalls = maxSkillCalls;
    }

    public AgentResult run(String agentId, AgentRequest request) {
        memory.add(request.sessionId(), "user", request.question());
        List<String> skillCalls = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        String answer = null;
        int iterations = 0;

        while (iterations < maxIterations && answer == null) {
            iterations++;
            String prompt = buildPrompt(request, observations);
            Map<String, String> visibleSkills = harnessValidator.visibleSkillMetadata(agentId, skillRegistry.metadata());
            String decision = normalizeDecision(modelGateway.complete(agentId, prompt, visibleSkills));
            if (decision.startsWith("CALL_SKILL:")) {
                if (skillCalls.size() >= maxSkillCalls) {
                    answer = fallbackAnswer("已达到工具调用上限", observations);
                    break;
                }
                String skillName = parseSkillName(decision);
                if (!harnessValidator.canUseSkill(agentId, skillName)) {
                    if ("clinical_guideline".equals(skillName) || "deep_research".equals(skillName)) {
                        throw new AgentDelegationRequest(
                                agentId,
                                "research_agent",
                                "需要由 research_agent 调用 " + skillName + " 完成循证分析"
                        );
                    }
                    throw new IllegalStateException("Agent " + agentId + " cannot use skill " + skillName);
                }
                SkillResult result = skillRegistry.invoke(skillName, new SkillRequest(request.question(), request.sessionId(), request.context()));
                skillCalls.add(skillName);
                observations.add(formatObservation(observations.size() + 1, result));
                if (skillCalls.size() >= maxSkillCalls) {
                    answer = fallbackAnswer("已达到工具调用上限", observations);
                }
                continue;
            }
            if (decision.startsWith("DELEGATE_AGENT:")) {
                throw parseDelegation(agentId, decision);
            }
            if (decision.startsWith("FINAL:")) {
                answer = stripFinalPrefix(decision);
                continue;
            }
            answer = decision.isBlank() ? fallbackAnswer("模型未返回有效步骤", observations) : decision;
            break;
        }

        if (answer == null) {
            answer = fallbackAnswer("已达到最大推理轮次", observations);
        }

        String finalAnswer = stripFinalPrefix(answer);
        finalAnswer = repairService.repair(finalAnswer);
        memory.add(request.sessionId(), "assistant", finalAnswer);
        return new AgentResult(agentId, finalAnswer, iterations, skillCalls);
    }

    private String buildPrompt(AgentRequest request, List<String> observations) {
        List<ChatMessage> recent = memory.recent(request.sessionId());
        StringBuilder prompt = new StringBuilder(request.question());
        prompt.append("\n\n请按 ReAct 协议只返回一个下一步：CALL_SKILL:<skill_name> 或 FINAL:<answer>。");
        if (!recent.isEmpty()) {
            prompt.append("\n\n历史上下文：");
            recent.forEach(message -> prompt.append("\n").append(message.role()).append(": ").append(message.content()));
        }
        if (!request.context().isEmpty()) {
            prompt.append("\n\n附加上下文：").append(request.context());
        }
        if (!observations.isEmpty()) {
            prompt.append("\n\n已获取的工具观察：");
            observations.forEach(observation -> prompt.append("\n").append(observation));
        }
        return prompt.toString();
    }

    private String normalizeDecision(String decision) {
        return decision == null ? "" : decision.trim();
    }

    private String parseSkillName(String decision) {
        String skillName = decision.substring("CALL_SKILL:".length()).trim();
        int lineBreak = skillName.indexOf('\n');
        if (lineBreak >= 0) {
            skillName = skillName.substring(0, lineBreak).trim();
        }
        return skillName;
    }

    private AgentDelegationRequest parseDelegation(String sourceAgent, String decision) {
        String payload = decision.substring("DELEGATE_AGENT:".length()).trim();
        String[] parts = payload.split(":", 2);
        String targetAgent = parts.length > 0 ? parts[0].trim() : "";
        String task = parts.length > 1 ? parts[1].trim() : "";
        if (targetAgent.isBlank()) {
            targetAgent = "consultation_agent";
        }
        if (task.isBlank()) {
            task = "继续处理超出当前 Agent 能力边界的部分";
        }
        return new AgentDelegationRequest(sourceAgent, targetAgent, task);
    }

    private String stripFinalPrefix(String answer) {
        return answer.replaceFirst("^FINAL:\\s*", "");
    }

    private String formatObservation(int index, SkillResult result) {
        return "Observation " + index
                + " [" + result.skillName() + ", success=" + result.success() + "]\n"
                + result.content()
                + "\nmetadata: " + result.metadata();
    }

    private String fallbackAnswer(String reason, List<String> observations) {
        StringBuilder answer = new StringBuilder(reason).append("，以下是基于已获取信息的阶段性建议：");
        if (observations.isEmpty()) {
            answer.append("\n").append("这是基于当前信息的健康建议。");
            return answer.toString();
        }
        observations.forEach(observation -> answer.append("\n").append(observation));
        return answer.toString();
    }
}
