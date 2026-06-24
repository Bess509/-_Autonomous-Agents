# Agent Delegation and Degraded Swarm Design

## Background

DeepSeek live testing exposed a real LLM failure mode in the Java medical assistant: a complex chest pain and breathing difficulty question entered the Swarm path, but `diagnostic_agent` selected `deep_research`. Harness correctly blocked the call because `deep_research` belongs to `research_agent`, but the exception propagated through `CompletableFuture.join()` and the API returned 500.

The fix should preserve the value of Harness boundaries. A diagnostic worker must not gain research skills just because a real LLM asked for them. Instead, the system should make cross-agent delegation explicit and let the correct worker perform the research task.

## Goals

- Expose only role-appropriate skills to each Worker Agent.
- Add a `DELEGATE_AGENT` protocol so an Agent can request another Agent when a task exceeds its boundary.
- Route research needs from `diagnostic_agent` to `research_agent` instead of allowing direct `deep_research` calls.
- Keep Swarm responses useful when one Worker fails by aggregating successful results and recording degraded subtasks.
- Return a safe medical fallback instead of HTTP 500 when all Workers fail.
- Preserve deterministic tests with `FakeModelGateway` and add a live DeepSeek smoke path for manual verification.

## Non-Goals

- Do not add `deep_research` or `clinical_guideline` to `diagnostic_agent`.
- Do not make `research_agent` a general knowledge-search Agent.
- Do not remove Harness validation.
- Do not change the public chat API response shape unless an optional diagnostic field is already compatible with current records.

## Worker Skill Matrix

| Agent | Role | Visible and Allowed Skills |
| --- | --- | --- |
| `consultation_agent` | General health consultation, common disease education, lifestyle guidance | `search_knowledge`, `recommend_lifestyle`, `assess_risk` |
| `diagnostic_agent` | Symptom analysis, risk stratification, diagnostic coding | `assess_risk`, `analyze_symptoms`, `disease_code` |
| `research_agent` | Evidence-based medical research, guidelines, deep research | `clinical_guideline`, `deep_research` |

The visible skills passed to the LLM and the Harness YAML allowed skills should use the same matrix. This prevents a mismatch where the LLM cannot see a skill but Harness still permits it, or vice versa.

## Root Cause

`SpringAiModelGateway` currently receives the full `SkillRegistry.metadata()` map for every Agent. A real LLM can therefore see every registered skill, including skills outside the current Agent's boundary. Harness catches the violation after `AgentLoopEngine.run()` returns, but `SwarmCoordinator` treats the resulting exception as fatal.

There are two separate defects:

- Skill visibility is too broad for live LLM prompting.
- Worker failures in Swarm mode are not converted into partial results or safe fallbacks.

## Design

### Agent-Specific Skill Visibility

Introduce a small service or helper that filters `SkillRegistry.metadata()` by `HarnessValidator.allowedSkills()` for the current `agentId`.

`AgentLoopEngine` will pass only the filtered metadata to `ModelGateway.complete(...)`. The model prompt should also explicitly say that only the visible skills are callable. If the task needs another ability, the model must delegate instead of inventing or calling hidden skills.

For deterministic tests, `FakeModelGateway` can continue using keyword rules, but tests should assert that the gateway receives only the expected skill names for each Agent.

### Delegation Protocol

Extend the model output contract with one new response form:

```text
DELEGATE_AGENT:<agent_id>:<task>
```

Example:

```text
DELEGATE_AGENT:research_agent:检索胸痛和呼吸困难相关临床指南，并进行深度研究证据总结
```

`AgentLoopEngine` should parse this as a delegation request and return a result object that preserves the source agent, target agent, and delegated task. The cleanest implementation is to introduce an execution outcome model rather than overloading answer text. If the codebase stays with `AgentResult`, delegation metadata can be carried in a lightweight extension field or a new `AgentExecutionResult` used internally by `SwarmCoordinator`.

Delegation is not a user-visible final answer. It is an orchestration signal.

### Delegation Routing

`SwarmCoordinator` handles delegation after each Worker completes:

1. Run each LeadAgent-assigned subtask.
2. If a result requests delegation, check whether the target Agent already has a pending, running, or completed subtask in the same session.
3. If no matching research subtask exists, create a new `SwarmSubtask` assigned to `research_agent` with the delegated task description.
4. Execute the delegated subtask through the same `runSubtask(...)` path.
5. Record delegation in `SharedContextStore`:
   - `subtask.<id>.status=delegated`
   - `subtask.<id>.delegatedTo=research_agent`
   - `subtask.<newId>.delegatedFrom=<id>`

For the known failure, the intended flow becomes:

```text
用户复杂问题
→ LeadAgent 拆分诊断与研究子任务
→ diagnostic_agent 只做 assess_risk/analyze_symptoms/disease_code
→ research_agent 调用 clinical_guideline/deep_research
→ LeadAgent 汇总
```

If LeadAgent under-decomposes and only assigns `diagnostic_agent`, delegation still gives the system a second chance to invoke `research_agent`.

### Graceful Degradation

`SwarmCoordinator` should never let one Worker exception fail the entire HTTP request when partial results are available.

Worker-level failure handling:

- Catch exceptions around `runSubtask(...)`.
- Mark the subtask as failed in `SharedContextStore`.
- Return a degraded `AgentResult` or internal failure result with a concise safe message.
- Include the original exception class and sanitized reason in shared context, not in the user-facing answer.

Swarm-level aggregation:

- If at least one Worker succeeds, synthesize using successful and degraded results.
- If all Workers fail, return a safe fallback:
  - Mention that the full multi-agent analysis could not be completed.
  - For chest pain, breathing difficulty, syncope, severe headache, paralysis, or confusion, advise urgent medical care or calling 120.
  - Preserve the standard disclaimer through `OutputRepairService`.

Single-agent mode should also catch failures and return the same safe fallback instead of 500.

### Prompt Contract

`SpringAiModelGateway` should use role-aware system prompts:

- State the Agent role in plain terms.
- List only visible skills.
- State that hidden skills must not be called.
- State that cross-boundary needs must use `DELEGATE_AGENT`.
- Keep the existing ReAct output constraint: emit exactly one of `CALL_SKILL`, `DELEGATE_AGENT`, or `FINAL`.

For `lead_agent`, keep a separate decomposition prompt path because it expects JSON subtasks, not ReAct actions. This avoids the current tension where the same ReAct system prompt is applied to both Worker Agents and LeadAgent.

## Error Handling Rules

- Invalid skill from LLM: convert to delegation if a deterministic mapping exists, otherwise degraded failure.
- Harness violation: degraded failure plus shared-context record; no API 500 if any fallback can be produced.
- Delegation to unknown Agent: degraded failure.
- Delegation loop: reject repeated delegation after one hop for the same source subtask and target Agent.
- Model timeout or provider error: degraded failure for that Worker.
- All Workers failed: safe fallback answer with urgent-care warning when high-risk terms are present.

## Tests

Unit tests:

- `diagnostic_agent` receives only `assess_risk`, `analyze_symptoms`, and `disease_code` metadata.
- `research_agent` receives only `clinical_guideline` and `deep_research` metadata.
- `consultation_agent` receives only `search_knowledge`, `recommend_lifestyle`, and `assess_risk` metadata.
- `AgentLoopEngine` parses `DELEGATE_AGENT:research_agent:<task>`.
- `SwarmCoordinator` executes a delegated research subtask.
- Existing research subtask prevents duplicate delegation.
- Worker exception in Swarm mode produces partial response instead of throwing.
- All-worker failure returns safe fallback.

Integration tests:

- Complex chest pain and breathing difficulty question returns `SWARM`, includes diagnostic and research contributions, and does not return 500.
- Harness still fails closed for direct disallowed skill calls in isolated validation tests.

Manual live smoke:

- Run with `MEDIX_LIVE_LLM=true`, DeepSeek-compatible base URL, and a temporary API key in process environment only.
- Ask a complex question requiring risk assessment and evidence review.
- Expected: `SWARM`, no Harness violation, research agent uses `clinical_guideline` or `deep_research`, response includes disclaimer and urgent-care warning.

## Rollout

1. Implement metadata filtering and tests first.
2. Add delegation parsing and internal result representation.
3. Add Swarm delegation execution and duplicate prevention.
4. Add graceful degradation for Worker and all-worker failures.
5. Re-run full deterministic test suite.
6. Run one manual live DeepSeek smoke test without committing any API key.

