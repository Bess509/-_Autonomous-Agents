package com.medix.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.medix.agent.AgentRequest;
import com.medix.agent.AgentResult;
import com.medix.agui.AguiController;
import com.medix.agui.RunAgentInput;
import com.medix.harness.HarnessValidator;
import com.medix.harness.OutputRepairService;
import com.medix.permission.PermissionService;
import com.medix.security.AppPrincipal;
import com.medix.skill.MedicalSkill;
import com.medix.skill.SkillRegistry;
import com.medix.skill.SkillRequest;
import com.medix.skill.SkillResult;
import com.medix.skill.AnalyzeSymptomsSkill;
import com.medix.skill.AssessRiskSkill;
import com.medix.swarm.SwarmCoordinator;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

class AgentScopeRuntimeAdapterTest {
    @Test
    void revokedSecondAuthorizationExecutesNoRegistryToolkitOrSkillCode() {
        AtomicInteger skillExecutions = new AtomicInteger();
        MedicalSkill skill = new MedicalSkill() {
            public String name() { return "search_knowledge"; }
            public String description() { return "search reviewed knowledge"; }
            public SkillResult invoke(SkillRequest request) {
                skillExecutions.incrementAndGet();
                return SkillResult.success(name(), "must not execute", Map.of());
            }
        };
        SkillRegistry registry = spy(new SkillRegistry(List.of(skill)));
        PermissionService permissions = mock(PermissionService.class);
        AppPrincipal principal = new AppPrincipal(UUID.randomUUID(), "revoked", "Revoked", Set.of("USER"));
        when(permissions.canExecute(principal, "consultation_agent", "search_knowledge", "run-revoked"))
                .thenReturn(new PermissionService.Decision(false, "AGENT_CAPABILITY_GRANT_MISSING",
                        principal.id().toString(), "consultation_agent", "search_knowledge"));
        MedicalAgentScopeTools toolkitBoundary = new MedicalAgentScopeTools(
                "consultation_agent", "thread-revoked", Map.of(
                "runId", "run-revoked", "security.principal", principal), registry, permissions);

        assertThatThrownBy(() -> toolkitBoundary.search("hypertension"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("AGENT_CAPABILITY_GRANT_MISSING");

        assertThat(toolkitBoundary.invokedCapabilities()).isEmpty();
        assertThat(skillExecutions).hasValue(0);
        verify(registry, never()).invoke(any(), any());
        verify(permissions).canExecute(principal, "consultation_agent", "search_knowledge", "run-revoked");
    }

    @Test
    void realReactToolkitInvokesPermissionGateBeforeMedicalSkill() {
        MedicalSkill skill = new MedicalSkill() {
            public String name() { return "search_knowledge"; }
            public String description() { return "search reviewed knowledge"; }
            public SkillResult invoke(SkillRequest request) {
                return SkillResult.success(name(), "reviewed evidence", Map.of("source", "test"));
            }
        };
        PermissionService permissions = mock(PermissionService.class);
        AppPrincipal principal = new AppPrincipal(UUID.randomUUID(), "tester", "Tester", Set.of("USER"));
        when(permissions.canExecute(eq(principal), eq("consultation_agent"), eq("search_knowledge"), eq("run-1")))
                .thenReturn(new PermissionService.Decision(true, "EXPLICIT_GRANT", principal.id().toString(),
                        "consultation_agent", "search_knowledge"));
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(null);
        AgentScopeRuntimeAdapter runtime = new AgentScopeRuntimeAdapter(
                new SkillRegistry(List.of(skill)), new HarnessValidator(), new OutputRepairService(),
                permissions, models, false, "deepseek-v4-flash", 3, Duration.ofSeconds(5));

        AgentResult result = runtime.run("consultation_agent", new AgentRequest("高血压知识", "thread-1", Map.of(
                "runId", "run-1", "security.principal", principal, "security.userId", principal.id().toString(),
                "permission.capabilities", Map.of("consultation_agent", Set.of("search_knowledge")))));

        assertThat(result.answer()).contains("reviewed evidence").doesNotContain("免责声明");
        assertThat(result.skillCalls()).containsExactly("search_knowledge");
        verify(permissions).canExecute(principal, "consultation_agent", "search_knowledge", "run-1");
    }

    @Test
    void identityQuestionIsAnsweredDirectlyWithoutCallingMedicalTool() {
        PermissionService permissions = mock(PermissionService.class);
        AppPrincipal principal = new AppPrincipal(UUID.randomUUID(), "tester", "Tester", Set.of("USER"));
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(null);
        AgentScopeRuntimeAdapter runtime = new AgentScopeRuntimeAdapter(
                new SkillRegistry(List.of()), new HarnessValidator(), new OutputRepairService(),
                permissions, models, false, "deepseek-v4-flash", 3, Duration.ofSeconds(5));

        AgentResult result = runtime.run("consultation_agent", new AgentRequest(
                "回答用户问题并提供安全的健康建议：你是谁", "thread-identity", Map.of(
                "runId", "run-identity", "security.principal", principal,
                "security.userId", principal.id().toString(),
                "permission.capabilities", Map.of("consultation_agent", Set.of()))));

        assertThat(result.answer()).contains("MediX 医疗助手");
        assertThat(result.skillCalls()).isEmpty();
    }

    @Test
    void offlineHeadachePlanUsesSymptomAndRiskToolsRegardlessOfRegistryOrder() {
        PermissionService permissions = mock(PermissionService.class);
        AppPrincipal principal = new AppPrincipal(UUID.randomUUID(), "tester", "Tester", Set.of("USER"));
        for (String skill : List.of("analyze_symptoms", "assess_risk")) {
            when(permissions.canExecute(eq(principal), eq("diagnostic_agent"), eq(skill), eq("run-headache")))
                    .thenReturn(new PermissionService.Decision(true, "EXPLICIT_GRANT", principal.id().toString(),
                            "diagnostic_agent", skill));
        }
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(null);
        AgentScopeRuntimeAdapter runtime = new AgentScopeRuntimeAdapter(
                new SkillRegistry(List.of(new AssessRiskSkill(), new AnalyzeSymptomsSkill())),
                new HarnessValidator(), new OutputRepairService(), permissions, models, false,
                "deepseek-v4-flash", 5, Duration.ofSeconds(5));

        AgentResult result = runtime.run("diagnostic_agent", new AgentRequest("我有点头痛", "headache", Map.of(
                "runId", "run-headache", "security.principal", principal,
                "permission.capabilities", Map.of("diagnostic_agent", Set.of("assess_risk", "analyze_symptoms")))));

        assertThat(result.skillCalls()).containsExactly("analyze_symptoms", "assess_risk");
        assertThat(result.skillCalls()).doesNotContain("disease_code");
        assertThat(result.answer()).contains("内部证据贡献").doesNotContain("免责声明", "I10", "R07.4");
    }

    @Test
    void liveFalseNeverInvokesAvailableSpringProvider() {
        PermissionService permissions = mock(PermissionService.class);
        ChatModel provider = mock(ChatModel.class);
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(provider);
        AgentScopeRuntimeAdapter runtime = new AgentScopeRuntimeAdapter(
                new SkillRegistry(List.of()), new HarnessValidator(), new OutputRepairService(), permissions,
                models, false, "deepseek-v4-flash", 2, Duration.ofSeconds(2));

        AgentResult result = runtime.run("consultation_agent", new AgentRequest("你是谁", "rollback", Map.of()));

        assertThat(result.answer()).contains("MediX");
        verifyNoInteractions(provider);
    }

    @Test
    void failureDiagnosticEncodesOnlyStableStageCountAndAllowlistedCapabilities() {
        PermissionService permissions = mock(PermissionService.class);
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(null);
        AgentScopeRuntimeAdapter runtime = new AgentScopeRuntimeAdapter(
                new SkillRegistry(List.of()), new HarnessValidator(), new OutputRepairService(), permissions,
                models, false, "deepseek-v4-flash", 2, Duration.ofSeconds(2));
        RuntimeException provider = new RuntimeException(
                new IllegalStateException("LLM_PROVIDER_BADREQUESTEXCEPTION_BUILD_HTTP_BAD_REQUEST_TOOL"));

        IllegalStateException first = ReflectionTestUtils.invokeMethod(
                runtime, "diagnosticFailure", provider, List.of());
        IllegalStateException afterTool = ReflectionTestUtils.invokeMethod(
                runtime, "diagnosticFailure", provider,
                List.of("search_knowledge", "not_allowlisted"));

        assertThat(first).hasMessage("LLM_PROVIDER_BADREQUESTEXCEPTION_BUILD_HTTP_BAD_REQUEST_TOOL"
                + "_FIRST_MODEL_COUNT_ZERO_NONE");
        assertThat(afterTool).hasMessage("LLM_PROVIDER_BADREQUESTEXCEPTION_BUILD_HTTP_BAD_REQUEST_TOOL"
                + "_AFTER_TOOL_COUNT_TWO_SEARCH_KNOWLEDGE");
        assertThat(afterTool.getMessage()).doesNotContain("not_allowlisted");
    }

    @Test
    void toolExceptionTerminatesWithStableRedactedCodeWithinBudget() {
        AtomicInteger skillExecutions = new AtomicInteger();
        MedicalSkill failing = new MedicalSkill() {
            public String name() { return "search_knowledge"; }
            public String description() { return "failure injection"; }
            public SkillResult invoke(SkillRequest request) {
                skillExecutions.incrementAndGet();
                throw new IllegalStateException("sensitive-tool-detail");
            }
        };
        AgentScopeRuntimeAdapter runtime = runtimeForInjectedSkill(failing, Duration.ofSeconds(2), "run-tool-error");
        long started = System.nanoTime();

        AgentResult result = runtime.run("consultation_agent", injectedRequest("run-tool-error"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(skillExecutions).hasValue(1);
        assertThat(result.skillCalls()).containsExactly("search_knowledge");
        assertThat(result.answer()).contains("LLM_TOOL_EXECUTION_FAILED")
                .doesNotContain("sensitive-tool-detail");
    }

    @Test
    void toolTimeoutTerminatesWithStableRedactedCodeWithinBudget() {
        AtomicInteger skillExecutions = new AtomicInteger();
        MedicalSkill slow = new MedicalSkill() {
            public String name() { return "search_knowledge"; }
            public String description() { return "timeout injection"; }
            public SkillResult invoke(SkillRequest request) {
                skillExecutions.incrementAndGet();
                try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return SkillResult.success(name(), "late result", Map.of());
            }
        };
        AgentScopeRuntimeAdapter runtime = runtimeForInjectedSkill(slow, Duration.ofMillis(100), "run-tool-timeout");
        long started = System.nanoTime();

        assertThatThrownBy(() -> runtime.run("consultation_agent", injectedRequest("run-tool-timeout")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LLM_AGENT_SCOPE_RUNTIME_AFTER_TOOL_COUNT_ONE_SEARCH_KNOWLEDGE")
                .hasMessageNotContaining("late result");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(800));
        assertThat(skillExecutions).hasValue(1);
    }

    @Test
    void aguiToolFailureProducesOneRedactedTerminalEvent() {
        PermissionService permissions = mock(PermissionService.class);
        SwarmCoordinator coordinator = mock(SwarmCoordinator.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AppPrincipal principal = new AppPrincipal(UUID.randomUUID(), "failure-user", "Failure", Set.of("USER"));
        String runId = "run-agui-tool-failure";
        String threadId = "thread-agui-tool-failure";
        when(permissions.canUseAgent(principal, "consultation_agent", runId))
                .thenReturn(new PermissionService.Decision(true, "EXPLICIT_GRANT", principal.id().toString(),
                        "consultation_agent", null));
        when(permissions.agents(principal)).thenReturn(Set.of("consultation_agent"));
        when(jdbc.query(any(String.class), org.mockito.ArgumentMatchers.<RowMapper<AguiController.ThreadView>>any(),
                any(Object[].class)))
                .thenReturn(List.of(), List.of(new AguiController.ThreadView(
                        threadId, principal.id(), "tool failure", Instant.now(), Instant.now())));
        when(jdbc.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        when(coordinator.processDetailed(any(AgentRequest.class), eq(Set.of("consultation_agent"))))
                .thenThrow(new IllegalStateException("sensitive-tool-detail"));
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        RunAgentInput input = new RunAgentInput(threadId, runId, Map.of(), List.of(
                new RunAgentInput.Message("m1", "user", "tool failure")), List.of(), List.of(),
                Map.of("agentId", "consultation_agent"));

        var stream = new AguiController(permissions, coordinator, jdbc)
                .run(input, authentication).getBody();
        var bytes = new java.io.ByteArrayOutputStream();
        assertThat(stream).isNotNull();
        try {
            stream.writeTo(bytes);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        String payload = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(payload).contains("RUN_ERROR", "RUN_FAILED", "运行失败，请稍后重试")
                .doesNotContain("RUN_FINISHED", "sensitive-tool-detail");
        assertThat(count(payload, "\"type\":\"RUN_ERROR\"")).isEqualTo(1);
    }

    private AgentScopeRuntimeAdapter runtimeForInjectedSkill(MedicalSkill skill, Duration timeout, String runId) {
        PermissionService permissions = mock(PermissionService.class);
        AppPrincipal principal = injectedPrincipal(runId);
        when(permissions.canExecute(principal, "consultation_agent", "search_knowledge", runId))
                .thenReturn(new PermissionService.Decision(true, "EXPLICIT_GRANT", principal.id().toString(),
                        "consultation_agent", "search_knowledge"));
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(null);
        return new AgentScopeRuntimeAdapter(new SkillRegistry(List.of(skill)), new HarnessValidator(),
                new OutputRepairService(), permissions, models, false, "deepseek-v4-flash", 3, timeout);
    }

    private AgentRequest injectedRequest(String runId) {
        AppPrincipal principal = injectedPrincipal(runId);
        return new AgentRequest("sleep evidence", "thread-" + runId, Map.of(
                "runId", runId, "security.principal", principal,
                "security.userId", principal.id().toString(),
                "permission.capabilities", Map.of("consultation_agent", Set.of("search_knowledge"))));
    }

    private AppPrincipal injectedPrincipal(String runId) {
        return new AppPrincipal(UUID.nameUUIDFromBytes(runId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "injected", "Injected", Set.of("USER"));
    }

    private int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    @Test
    void chineseEmergencyCombinationStopsBeforeLiveProviderAndToolkit() {
        PermissionService permissions = mock(PermissionService.class);
        ChatModel provider = mock(ChatModel.class);
        when(provider.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:1").apiKey("local-runtime-value")
                .model("deepseek-v4-flash").build());
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(provider);
        AgentScopeRuntimeAdapter runtime = new AgentScopeRuntimeAdapter(
                new SkillRegistry(List.of()), new HarnessValidator(), new OutputRepairService(), permissions,
                models, true, "deepseek-v4-flash", 2, Duration.ofSeconds(2));

        AgentResult result = runtime.run("diagnostic_agent", new AgentRequest(
                "我现在胸痛、呼吸困难而且意识有点不清。", "emergency-pre-model", Map.of()));

        assertThat(result.answer()).contains("120", "急诊");
        assertThat(result.iterations()).isZero();
        assertThat(result.skillCalls()).isEmpty();
        verify(provider, never()).call(any(Prompt.class));
        verifyNoInteractions(permissions);
    }
}
