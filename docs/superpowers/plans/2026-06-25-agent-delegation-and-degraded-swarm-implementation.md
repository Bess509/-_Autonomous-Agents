# Agent Delegation and Degraded Swarm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make live LLM Swarm execution respect Agent skill boundaries, delegate research work to `research_agent`, and return degraded safe answers instead of HTTP 500 when a Worker fails.

**Architecture:** Filter visible skill metadata per Agent using Harness skill boundaries, parse `DELEGATE_AGENT:<agent_id>:<task>` as an orchestration signal, and let `SwarmCoordinator` execute delegated research subtasks or produce degraded results. Keep `AgentResult` API shape stable by representing delegation as an internal runtime signal caught by the coordinator.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI ChatClient, JUnit 6, AssertJ, Mockito, existing `SkillRegistry`, `HarnessValidator`, `AgentLoopEngine`, and `SwarmCoordinator`.

---

### Task 1: Agent-Specific Skill Visibility

**Files:**
- Modify: `medix-java/src/main/java/com/medix/harness/HarnessValidator.java`
- Modify: `medix-java/src/main/java/com/medix/agent/AgentLoopEngine.java`
- Modify: `medix-java/src/main/resources/agents/agent-constraints.yml`
- Test: `medix-java/src/test/java/com/medix/agent/AgentLoopEngineTest.java`

- [ ] **Step 1: Write failing metadata filtering test**

Add this test to `AgentLoopEngineTest`:

```java
@Test
void onlyExposesAllowedSkillsForDiagnosticAgent() {
    SkillRegistry registry = new SkillRegistry(List.of(
            new StaticSkill("assess_risk", "risk", "risk result"),
            new StaticSkill("analyze_symptoms", "symptoms", "symptom result"),
            new StaticSkill("disease_code", "icd", "icd result"),
            new StaticSkill("deep_research", "research", "research result")
    ));
    ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
    CapturingMetadataModelGateway modelGateway = new CapturingMetadataModelGateway("FINAL:done");
    AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 5, 3);

    loop.run("diagnostic_agent", new AgentRequest("胸痛和呼吸困难", "visibility", Map.of()));

    assertThat(modelGateway.lastMetadata().keySet())
            .containsExactlyInAnyOrder("assess_risk", "analyze_symptoms", "disease_code");
}
```

Add helper class beside `ScriptedModelGateway`:

```java
private static class CapturingMetadataModelGateway extends ScriptedModelGateway {
    private Map<String, String> lastMetadata = Map.of();

    CapturingMetadataModelGateway(String... decisions) {
        super(decisions);
    }

    @Override
    public String complete(String agentId, String userPrompt, Map<String, String> skillMetadata) {
        lastMetadata = Map.copyOf(skillMetadata);
        return super.complete(agentId, userPrompt, skillMetadata);
    }

    Map<String, String> lastMetadata() {
        return lastMetadata;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn test "-Dtest=AgentLoopEngineTest#onlyExposesAllowedSkillsForDiagnosticAgent"
```

Expected: FAIL because `deep_research` is still visible to `diagnostic_agent`.

- [ ] **Step 3: Add filtering API to HarnessValidator**

Add this method:

```java
public Map<String, String> visibleSkillMetadata(String agentId, Map<String, String> metadata) {
    Set<String> allowed = allowedSkills.get(agentId);
    if (allowed == null || allowed.isEmpty()) {
        return Map.copyOf(metadata);
    }
    Map<String, String> visible = new LinkedHashMap<>();
    metadata.forEach((name, description) -> {
        if (allowed.contains(name)) {
            visible.put(name, description);
        }
    });
    return Map.copyOf(visible);
}
```

- [ ] **Step 4: Inject HarnessValidator into AgentLoopEngine**

Update constructors so Spring injection receives `HarnessValidator`, while test constructors default to `new HarnessValidator()`:

```java
private final HarnessValidator harnessValidator;

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
```

In `run`, replace:

```java
modelGateway.complete(agentId, prompt, skillRegistry.metadata())
```

with:

```java
Map<String, String> visibleSkills = harnessValidator.visibleSkillMetadata(agentId, skillRegistry.metadata());
modelGateway.complete(agentId, prompt, visibleSkills)
```

- [ ] **Step 5: Tighten YAML skill matrix**

Update `agent-constraints.yml`:

```yaml
diagnostic_agent:
  allowedSkills: [assess_risk, analyze_symptoms, disease_code]
research_agent:
  allowedSkills: [clinical_guideline, deep_research]
```

- [ ] **Step 6: Run visibility tests**

Run:

```bash
mvn test "-Dtest=AgentLoopEngineTest#onlyExposesAllowedSkillsForDiagnosticAgent"
```

Expected: PASS.

### Task 2: Delegation Protocol in AgentLoopEngine

**Files:**
- Create: `medix-java/src/main/java/com/medix/agent/AgentDelegationRequest.java`
- Modify: `medix-java/src/main/java/com/medix/agent/AgentLoopEngine.java`
- Modify: `medix-java/src/main/java/com/medix/agent/SpringAiModelGateway.java`
- Test: `medix-java/src/test/java/com/medix/agent/AgentLoopEngineTest.java`

- [ ] **Step 1: Write failing delegation tests**

Add tests:

```java
@Test
void parsesExplicitAgentDelegationRequest() {
    SkillRegistry registry = new SkillRegistry(List.of());
    ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
    ScriptedModelGateway modelGateway = new ScriptedModelGateway(
            "DELEGATE_AGENT:research_agent:检索胸痛和呼吸困难相关指南证据"
    );
    AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 5, 3);

    assertThatThrownBy(() -> loop.run("diagnostic_agent", new AgentRequest("需要指南证据", "delegate", Map.of())))
            .isInstanceOf(AgentDelegationRequest.class)
            .satisfies(error -> {
                AgentDelegationRequest request = (AgentDelegationRequest) error;
                assertThat(request.sourceAgent()).isEqualTo("diagnostic_agent");
                assertThat(request.targetAgent()).isEqualTo("research_agent");
                assertThat(request.task()).contains("胸痛");
            });
}

@Test
void mapsResearchSkillViolationToResearchDelegation() {
    SkillRegistry registry = new SkillRegistry(List.of(
            new StaticSkill("deep_research", "research", "research result")
    ));
    ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
    ScriptedModelGateway modelGateway = new ScriptedModelGateway("CALL_SKILL:deep_research");
    AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), modelGateway, 5, 3);

    assertThatThrownBy(() -> loop.run("diagnostic_agent", new AgentRequest("需要深度研究", "delegate-skill", Map.of())))
            .isInstanceOf(AgentDelegationRequest.class)
            .satisfies(error -> {
                AgentDelegationRequest request = (AgentDelegationRequest) error;
                assertThat(request.targetAgent()).isEqualTo("research_agent");
                assertThat(request.task()).contains("deep_research");
            });
}
```

Add static import:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
mvn test "-Dtest=AgentLoopEngineTest#parsesExplicitAgentDelegationRequest,AgentLoopEngineTest#mapsResearchSkillViolationToResearchDelegation"
```

Expected: FAIL because `AgentDelegationRequest` and parsing do not exist.

- [ ] **Step 3: Add AgentDelegationRequest**

Create:

```java
package com.medix.agent;

public class AgentDelegationRequest extends RuntimeException {
    private final String sourceAgent;
    private final String targetAgent;
    private final String task;

    public AgentDelegationRequest(String sourceAgent, String targetAgent, String task) {
        super("Agent " + sourceAgent + " delegated to " + targetAgent + ": " + task);
        this.sourceAgent = sourceAgent;
        this.targetAgent = targetAgent;
        this.task = task;
    }

    public String sourceAgent() {
        return sourceAgent;
    }

    public String targetAgent() {
        return targetAgent;
    }

    public String task() {
        return task;
    }
}
```

- [ ] **Step 4: Parse delegation in AgentLoopEngine**

Add before `FINAL:` handling:

```java
if (decision.startsWith("DELEGATE_AGENT:")) {
    throw parseDelegation(agentId, decision);
}
```

Add helper:

```java
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
```

Before invoking a skill, map hidden research skills:

```java
if (!harnessValidator.canUseSkill(agentId, skillName)) {
    if ("clinical_guideline".equals(skillName) || "deep_research".equals(skillName)) {
        throw new AgentDelegationRequest(agentId, "research_agent", "需要由 research_agent 调用 " + skillName + " 完成循证分析");
    }
    throw new IllegalStateException("Agent " + agentId + " cannot use skill " + skillName);
}
```

- [ ] **Step 5: Make SpringAiModelGateway role-aware**

In worker prompt, say:

```text
Only call skills listed in Available skills.
If the task needs hidden research, guideline, or evidence-review capability, return DELEGATE_AGENT:research_agent:<task>.
Emit exactly one of CALL_SKILL:<skill_name>, DELEGATE_AGENT:<agent_id>:<task>, or FINAL:<answer>.
```

For `lead_agent`, use a separate system prompt that asks for JSON subtasks only.

- [ ] **Step 6: Run delegation tests**

Run:

```bash
mvn test "-Dtest=AgentLoopEngineTest#parsesExplicitAgentDelegationRequest,AgentLoopEngineTest#mapsResearchSkillViolationToResearchDelegation"
```

Expected: PASS.

### Task 3: Swarm Delegation and Graceful Degradation

**Files:**
- Modify: `medix-java/src/main/java/com/medix/swarm/SwarmCoordinator.java`
- Test: `medix-java/src/test/java/com/medix/swarm/SwarmCoordinatorTest.java`

- [ ] **Step 1: Write failing Swarm delegation test**

Add a diagnostic agent test double that throws delegation:

```java
private static class DelegatingDiagnosticAgent extends DiagnosticAgent {
    DelegatingDiagnosticAgent() {
        super(null);
    }

    @Override
    public AgentResult answer(AgentRequest request) {
        throw new AgentDelegationRequest("diagnostic_agent", "research_agent", "执行深度研究");
    }
}
```

Add test:

```java
@Test
void executesDelegatedResearchSubtaskInsteadOfFailingSwarm() {
    LeadAgent leadAgent = new LeadAgent((agentId, prompt, skillMetadata) -> """
            {"subtasks":[{"description":"评估胸痛风险","assigned_agent":"diagnostic_agent"}]}
            """);
    SharedContextStore sharedContextStore = new SharedContextStore();
    SwarmCoordinator coordinator = new SwarmCoordinator(
            new SwarmRouter(),
            new CapturingConsultationAgent("consultation_agent"),
            new DelegatingDiagnosticAgent(),
            new CapturingResearchAgent("research_agent"),
            leadAgent,
            sharedContextStore
    );

    SwarmResponse response = coordinator.processDetailed(new AgentRequest("胸痛和呼吸困难，需要指南证据", "delegated-swarm", Map.of()));

    assertThat(response.answer()).contains("research_agent handled: 执行深度研究");
    assertThat(response.agentResults()).extracting(AgentResult::agentId).contains("research_agent");
    assertThat(response.sharedContext()).containsEntry("subtask.1.status", "delegated");
    assertThat(response.sharedContext()).containsValue("research_agent");
}
```

- [ ] **Step 2: Write failing Worker failure degradation test**

Add a failing agent:

```java
private static class FailingResearchAgent extends ResearchAgent {
    FailingResearchAgent() {
        super(null);
    }

    @Override
    public AgentResult answer(AgentRequest request) {
        throw new IllegalStateException("provider timeout");
    }
}
```

Add test:

```java
@Test
void returnsPartialSwarmAnswerWhenOneWorkerFails() {
    LeadAgent leadAgent = new LeadAgent((agentId, prompt, skillMetadata) -> """
            {"subtasks":[
              {"description":"提供生活建议","assigned_agent":"consultation_agent"},
              {"description":"执行深度研究","assigned_agent":"research_agent"}
            ]}
            """);
    SharedContextStore sharedContextStore = new SharedContextStore();
    SwarmCoordinator coordinator = new SwarmCoordinator(
            new SwarmRouter(),
            new CapturingConsultationAgent("consultation_agent"),
            new CapturingDiagnosticAgent("diagnostic_agent"),
            new FailingResearchAgent(),
            leadAgent,
            sharedContextStore
    );

    SwarmResponse response = coordinator.processDetailed(new AgentRequest("高血压管理和指南证据", "partial-failure", Map.of()));

    assertThat(response.answer()).contains("consultation_agent handled: 提供生活建议");
    assertThat(response.answer()).contains("research_agent 暂时无法完成");
    assertThat(response.sharedContext()).containsEntry("subtask.2.status", "failed");
}
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```bash
mvn test "-Dtest=SwarmCoordinatorTest#executesDelegatedResearchSubtaskInsteadOfFailingSwarm,SwarmCoordinatorTest#returnsPartialSwarmAnswerWhenOneWorkerFails"
```

Expected: FAIL because coordinator does not catch delegation or Worker failures.

- [ ] **Step 4: Catch delegation in runSubtask**

Wrap `runAgent` in `try/catch`:

```java
try {
    AgentResult result = runAgent(subtaskRequest, agent);
    markCompleted(...);
    return result;
} catch (AgentDelegationRequest delegation) {
    sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".status", "delegated");
    sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".delegatedTo", delegation.targetAgent());
    sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".delegatedTask", delegation.task());
    return runDelegatedSubtask(request, assignedSubtask, delegation);
}
```

Implement `runDelegatedSubtask` with a single-hop guard. If target Agent is already running or completed, return:

```java
new AgentResult(delegation.sourceAgent(), "已将超出当前能力边界的部分交由 " + delegation.targetAgent() + " 处理。", 1, List.of())
```

Otherwise create a new `SwarmSubtask` with id `assignedSubtask.id() + "-delegated"` and run it through `runSubtask(...)`.

- [ ] **Step 5: Catch failures in runSubtask**

Add a broad non-fatal catch:

```java
catch (RuntimeException exception) {
    sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".status", "failed");
    sharedContextStore.put(request.sessionId(), "subtask." + assignedSubtask.id() + ".error", exception.getClass().getSimpleName());
    return degradedResult(agent.agentId(), exception);
}
```

Add:

```java
private AgentResult degradedResult(String agentId, RuntimeException exception) {
    return new AgentResult(
            agentId,
            agentId + " 暂时无法完成该子任务，系统将基于其他可用信息继续给出安全建议。",
            0,
            List.of()
    );
}
```

- [ ] **Step 6: Run Swarm tests**

Run:

```bash
mvn test "-Dtest=SwarmCoordinatorTest#executesDelegatedResearchSubtaskInsteadOfFailingSwarm,SwarmCoordinatorTest#returnsPartialSwarmAnswerWhenOneWorkerFails"
```

Expected: PASS.

### Task 4: End-to-End Verification

**Files:**
- Modify tests only if deterministic expectations need updated ordering.

- [ ] **Step 1: Run focused test suite**

Run:

```bash
mvn test "-Dtest=AgentLoopEngineTest,SwarmCoordinatorTest,LeadAgentTest,ChatControllerMemoryContextTest"
```

Expected: PASS.

- [ ] **Step 2: Run full test suite**

Run:

```bash
mvn test
```

Expected: PASS with all deterministic tests.

- [ ] **Step 3: Manual live DeepSeek smoke**

Run the application with environment variables only:

```powershell
$env:MEDIX_LIVE_LLM='true'
$env:MEDIX_OPENAI_API_KEY='<temporary key from shell only>'
$env:MEDIX_OPENAI_BASE_URL='https://api.deepseek.com'
$env:MEDIX_OPENAI_MODEL='deepseek-chat'
$env:MEDIX_REDIS_ENABLED='false'
$env:MEDIX_RERANKER_ENABLED='false'
$env:MEDIX_MINIO_ENABLED='false'
$env:SERVER_PORT='18080'
mvn spring-boot:run
```

Send:

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:18080/api/v1/chat' `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"sessionId":"live-delegation","question":"52岁男性，高血压多年，今天出现胸痛和呼吸困难，想了解可能风险、临床指南证据以及下一步应该怎么办。","context":{"age":52,"sex":"male"}}'
```

Expected:

- HTTP 200.
- `routeMode` is `SWARM` for complex decomposition, or `SINGLE_AGENT` with recorded delegation if LeadAgent under-decomposes.
- No Harness violation.
- `research_agent` appears in `agentResults` or `sharedContext`.
- Answer includes urgent-care warning and disclaimer.

