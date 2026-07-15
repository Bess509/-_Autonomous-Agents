package com.medix.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medix.agent.AgentRequest;
import com.medix.agent.AgentResult;
import com.medix.agent.AgentRuntimePort;
import com.medix.harness.HarnessValidator;
import com.medix.harness.OutputRepairService;
import com.medix.skill.SkillRegistry;
import com.medix.permission.PermissionService;
import com.medix.nlu.EmergencyRiskDetector;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentScopeRuntimeAdapter implements AgentRuntimePort {
    private static final Set<String> DIAGNOSTIC_CAPABILITY_ALLOWLIST = Set.of(
            "analyze_symptoms", "assess_risk", "search_knowledge", "clinical_guideline",
            "recommend_lifestyle", "disease_code", "deep_research");
    private final SkillRegistry skills;
    private final HarnessValidator harness;
    private final OutputRepairService safety;
    private final Model model;
    private final int maxIterations;
    private final Duration timeout;
    private final PermissionService permissions;
    private final EmergencyRiskDetector emergency = new EmergencyRiskDetector();

    public AgentScopeRuntimeAdapter(SkillRegistry skills, HarnessValidator harness, OutputRepairService safety,
                                    PermissionService permissions, ObjectProvider<ChatModel> chatModel,
                                    @Value("${medix.features.live-llm:false}") boolean live,
                                    @Value("${spring.ai.openai.chat.options.model:}") String configuredModel,
                                    @Value("${medix.agent.max-iterations:5}") int maxIterations,
                                    @Value("${medix.agent.single-agent-timeout:15s}") Duration timeout) {
        this.skills = skills;
        this.harness = harness;
        this.safety = safety;
        this.maxIterations = maxIterations;
        this.timeout = timeout;
        this.permissions = permissions;
        ChatModel springModel = chatModel.getIfAvailable();
        this.model = live && springModel != null
                ? new SpringAiAgentScopeChatModel(springModel, new ObjectMapper(), configuredModel)
                : new FakeAgentScopeChatModel();
    }

    @Override
    public AgentResult run(String agentId, AgentRequest request) {
        if (emergency.isEmergency(request.question())) {
            String emergencyAnswer = safety.repair(
                    "检测到可能危及生命的急症信号。请立即拨打 120 或前往急诊，不要等待在线回复，"
                            + "也不要自行驾车。", request.question());
            return new AgentResult(agentId, emergencyAnswer, 0, List.of());
        }
        if (isIdentityQuestion(request.question())) {
            String introduction = safety.repair(
                    "我是 MediX 医疗助手，可以提供健康科普、症状风险提示和医学资料检索。"
                            + "我不会替代医生作出诊断或开具处方；如出现紧急症状，请立即联系 120 或前往急诊。"
            , request.question());
            return new AgentResult(agentId, introduction, 1, List.of());
        }
        Toolkit toolkit = new Toolkit();
        MedicalAgentScopeTools medicalTools = new MedicalAgentScopeTools(
                agentId, request.sessionId(), request.context(), skills, permissions);
        toolkit.registerTool(medicalTools);
        List<String> allowed = harness.visibleSkillMetadata(agentId, skills.metadata()).keySet().stream().toList();
        toolkit.getToolSchemas().stream().map(schema -> schema.getName()).filter(name -> !allowed.contains(name)).toList()
                .forEach(toolkit::removeTool);
        // Fail closed before model invocation: an absent capability matrix exposes no tools.
        Object matrix = request.context().get("permission.capabilities");
        if (!(matrix instanceof Map<?, ?> permissionMatrix)) toolkit.getToolSchemas().stream().map(s -> s.getName()).toList().forEach(toolkit::removeTool);
        else toolkit.getToolSchemas().stream().map(s -> s.getName())
                .filter(name -> !(permissionMatrix.get(agentId) instanceof java.util.Collection<?> c && c.contains(name)))
                .toList().forEach(toolkit::removeTool);

        ReActAgent agent = ReActAgent.builder().name(agentId).model(model).toolkit(toolkit)
                .sysPrompt("You are MediX, a cautious medical information assistant. "
                        + "For identity or capability questions, introduce yourself directly without calling a tool. "
                        + "For medical questions, use only registered tools when evidence is needed. "
                        + "After any tool result, answer the original question using that evidence in one final response. "
                        + "Never expose hidden reasoning, diagnose with certainty, invent evidence, or prescribe treatment.")
                .maxIters(maxIterations).build();
        String userId = String.valueOf(request.context().getOrDefault("security.userId", "anonymous"));
        RuntimeContext runtime = RuntimeContext.builder().userId(userId).sessionId(request.sessionId()).build();
        Msg result;
        try {
            result = agent.call(request.question(), runtime).block(timeout);
        } catch (RuntimeException failure) {
            throw diagnosticFailure(failure, medicalTools.invokedCapabilities());
        }
        if (result == null) throw new IllegalStateException("AgentScope returned no result");
        String answer = result.getTextContent();
        return new AgentResult(agentId, answer, maxIterations, medicalTools.invokedCapabilities());
    }

    private IllegalStateException diagnosticFailure(RuntimeException failure, List<String> invoked) {
        List<String> safeNames = new LinkedHashSet<>(invoked).stream()
                .filter(DIAGNOSTIC_CAPABILITY_ALLOWLIST::contains)
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .sorted()
                .toList();
        String stage = invoked.isEmpty() ? "FIRST_MODEL" : "AFTER_TOOL";
        String count = switch (invoked.size()) {
            case 0 -> "COUNT_ZERO";
            case 1 -> "COUNT_ONE";
            case 2 -> "COUNT_TWO";
            default -> "COUNT_MANY";
        };
        String names = safeNames.isEmpty() ? "NONE" : String.join("_", safeNames);
        return new IllegalStateException(stableFailureCode(failure) + "_" + stage + "_" + count + "_" + names);
    }

    private String stableFailureCode(Throwable failure) {
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 8; depth++, cursor = cursor.getCause()) {
            String message = cursor.getMessage();
            if (message != null && message.matches("LLM_[A-Z_]+")) return message;
        }
        return "LLM_AGENT_SCOPE_RUNTIME";
    }

    private boolean isIdentityQuestion(String value) {
        if (value == null) return false;
        String normalized = value.replaceAll("[\\s，。！？,.!?]", "").toLowerCase();
        return normalized.contains("你是谁") || normalized.contains("你是什么")
                || normalized.contains("介绍一下你自己") || normalized.contains("whoareyou");
    }
}
