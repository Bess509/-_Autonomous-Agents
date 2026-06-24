# MediX Java Multi-Agent Medical Assistant Design

Date: 2026-06-24
Status: Approved for design by user, pending implementation plan

## Goal

Convert the current Python MediX agent-swarm prototype into a Java project while preserving the original Python implementation as reference material. The Java version will live in `medix-java/` and will be a Spring Boot application built around Spring AI 2.0, spring-ai-agent-utils, and LangChain4j.

The project must demonstrate a multi-agent medical question-answering platform with:

- A decoupled Skills-Agent architecture.
- Single-agent fast path and multi-agent Swarm collaboration.
- A bounded ReAct-style Agent Loop.
- Short-term and long-term memory.
- RAG over a medical knowledge base.
- YAML-defined Harness constraints with runtime validation and output repair.
- Evaluation metrics suitable for a resume/demo project.

## Existing Project Context

The workspace currently contains:

- `medix-agent-swarm/`: Python prototype with agents, skills, swarm routing, memory, constraints, validation, and medical knowledge documents.
- `medix-agent-swarm/.claude/skills/`: existing skill documentation and scripts for medical skills.
- `medix-agent-swarm/knowledge/data/documents/`: medical text documents for lifestyle, ICD-10, emergency symptoms, and clinical guidelines.
- `MediX-R1/`: model training/evaluation material that should remain untouched in this migration.

The workspace root is not currently a Git repository. The Java migration will not overwrite the Python prototype.

Local services from the referenced screenshot:

- PostgreSQL: `localhost:5432`, user `postgres`, password `123456`.
- Redis: `localhost:6379`.
- MinIO: `http://localhost:9000`, console `http://localhost:9001`, user `minioadmin`, password `minioadmin123`.
- Reranker: `http://localhost:8081/rerank`.

Observed state during exploration: PostgreSQL port 5432 is reachable. Docker containers for Redis, MinIO, and reranker exist but were stopped, so the Java app must fail gracefully when optional services are unavailable and document how to start them.

## Architecture Choice

Use a Spring Boot Java project as the application spine:

- Spring Boot handles REST APIs, dependency injection, configuration, Redis, PostgreSQL, AOP, scheduling, and tests.
- Spring AI 2.0 handles `ChatClient`, model abstraction, embeddings, pgvector integration, output parsing, and RAG plumbing.
- spring-ai-agent-utils handles Skills progressive disclosure. The app will expose each skill with lightweight metadata first, then load full skill instructions when an agent decides the skill is relevant.
- LangChain4j provides the agent-facing model of tools, ReAct loop semantics, and `ChatMemory` abstractions where it fits cleanly.

This balances resume value and implementation risk: Spring AI is the strongest fit for Spring infrastructure and vector storage, while LangChain4j makes the Agent Loop story explicit.

## Package Layout

Target root:

```text
medix-java/
  pom.xml
  README.md
  src/main/java/com/medix/
    MedixJavaApplication.java
    api/
    agent/
    skill/
    swarm/
    memory/
    rag/
    harness/
    evaluation/
    config/
    common/
  src/main/resources/
    application.yml
    agents/agent-constraints.yml
    agents/swarm-constraints.yml
    skills/
    knowledge/documents/
    db/migration/
  src/test/java/com/medix/
```

The Python directories remain in place. Existing text knowledge and skill markdown are copied or transformed into Java resources.

## Core Components

### API Layer

REST endpoints:

- `POST /api/chat`: accepts question, session id, optional patient context, and streaming preference.
- `GET /api/sessions/{sessionId}`: returns recent short-term memory and stored summary metadata.
- `POST /api/knowledge/import`: imports bundled documents into pgvector.
- `POST /api/evaluation/run`: runs the local evaluation suite and returns metrics.
- `GET /actuator/health`: reports database, Redis, MinIO, reranker, and model-provider health.

The first implementation can be API-only. A UI is not required for the Java conversion.

### Skills Layer

Define a Java `MedicalSkill` interface:

```java
public interface MedicalSkill {
    String name();
    String description();
    SkillResult invoke(SkillRequest request);
}
```

Seven atomic skills:

- `SearchKnowledgeSkill`: RAG search over pgvector.
- `AssessRiskSkill`: rule-based emergency and risk triage.
- `AnalyzeSymptomsSkill`: symptom pattern analysis.
- `RecommendLifestyleSkill`: lifestyle advice backed by knowledge retrieval.
- `Icd10CodeSkill`: ICD-10 lookup backed by knowledge retrieval.
- `ClinicalGuidelineSkill`: guideline retrieval backed by knowledge retrieval.
- `DeepResearchSkill`: multi-source research workflow using web search stubs or enabled search adapters, knowledge retrieval, reranking, and evidence synthesis.

All agents receive the same skill registry but select skills independently. Skills do not depend on agents. Agents depend only on the skill registry and the common skill interface.

`src/main/resources/skills/*/SKILL.md` will preserve the progressive disclosure content. The application loads names and descriptions at startup, then uses spring-ai-agent-utils SkillsTool to reveal full instructions only when needed.

### Agent Layer

Agents:

- `ConsultationAgent`: general health consultation, lifestyle advice, basic risk triage.
- `DiagnosticAgent`: symptom analysis, differential-diagnosis reasoning, ICD-10 lookup, risk escalation.
- `ResearchAgent`: evidence lookup, clinical guideline search, deep research.
- `LeadAgent`: question complexity assessment, task decomposition, and final synthesis.

Each worker agent wraps the same execution contract:

```java
AgentResult answer(AgentRequest request);
AgentContribution handleSubtask(SwarmSubtask subtask);
```

The agent prompt includes:

- Role and medical safety boundaries.
- Available skill metadata.
- Session memory summary.
- Retrieved similar cases.
- Current Swarm context if present.

### Agent Loop

Implement `AgentLoopEngine` with a bounded Think-Act-Observe cycle:

1. Build messages from system prompt, short-term memory, similar long-term cases, and user input.
2. Ask the model to respond or call a skill.
3. Validate the skill call against Harness constraints.
4. Invoke the skill and append an observation.
5. Stop when final answer is produced or `maxIterations` is reached.
6. Repair and validate the final output before returning.

Defaults:

- `maxIterations`: 5 for worker agents.
- `maxSkillCalls`: 3 per request.
- Single-agent timeout target: 15 seconds.
- Swarm timeout target: 30 seconds for demo configuration, with partial result synthesis on timeout.

LangChain4j `ChatMemory` is the preferred short-term memory abstraction, while Spring beans provide singleton lifecycle and sharing.

### Swarm Layer

`SwarmRouter` decides:

- Fast path: one agent for simple health advice or single-domain questions.
- Swarm path: multi-symptom, high-risk, research-heavy, or cross-domain questions.

The router uses a hybrid strategy:

- Deterministic heuristics for high-risk keywords, multi-condition patterns, and request type.
- Optional LLM classification for ambiguous cases.
- YAML constraints for required agents in specific scenarios.

`SwarmCoordinator` workflow:

1. Call `LeadAgent` to create subtasks.
2. Persist Swarm state in Redis with a session/task key namespace.
3. Dispatch subtasks to worker agents with `CompletableFuture`.
4. Workers write `AgentContribution` records into Redis shared context.
5. `LeadAgent` synthesizes final answer from all completed contributions.
6. Partial synthesis is allowed if one agent times out, but emergency warnings must still be preserved.

This implements the stated 70% single-agent / 30% Swarm split as an evaluation target, not as a hard-coded quota.

### Memory Layer

Short-term memory:

- Use LangChain4j `ChatMemory` behind a singleton Spring bean.
- Store the latest conversation turns by `sessionId`.
- Integrate a Java `MessageWindowReducer` that:
  - Deduplicates exact duplicate messages.
  - Keeps the latest 5 user-assistant rounds.
  - Compresses older context into one summary message.
  - Records compression ratio for evaluation.

Redis is used to persist short-term memory and shared Swarm state when available. If Redis is down, the app falls back to local in-memory storage and reports degraded health.

Long-term memory:

- Store session summaries in PostgreSQL.
- Store embeddings in pgvector for similar-case retrieval.
- Search top similar summaries before answering.
- Link memory records to session id, route mode, agents involved, risk level, latency, and timestamp.

This replaces the Python prototype's Mem0 dependency with local PostgreSQL + pgvector.

### RAG Layer

Use bundled medical documents as the initial knowledge base:

- Split documents into chunks.
- Embed chunks through Spring AI embedding model configuration.
- Store vectors and metadata in PostgreSQL + pgvector.
- Retrieve top candidates for knowledge, lifestyle, ICD-10, and guideline skills.
- Call local reranker `http://localhost:8081/rerank` when available.
- Fall back to vector similarity ordering if reranker is unavailable.

MinIO is optional for the first implementation. It is reserved for uploaded document storage and source-file provenance.

### Harness Constraints And Output Repair

YAML resources:

- `agents/agent-constraints.yml`: allowed skills, forbidden actions, required output constraints per agent.
- `agents/swarm-constraints.yml`: routing, required agents, task decomposition rules, timeout and partial-result rules.

Runtime enforcement:

- Spring AOP intercepts skill calls and final agent outputs.
- Constraint violations are logged with agent id, skill name, session id, and request id.
- Forbidden tool calls are blocked or downgraded to warnings depending on severity.

Output repair:

- Use a Spring AI `OutputParser`-style repair pipeline.
- Append a medical disclaimer if missing.
- Add an emergency warning for high-risk symptoms such as chest pain, breathing difficulty, loss of consciousness, stroke-like symptoms, or severe sudden headache.
- Rephrase definitive diagnosis language into probabilistic triage language.
- Keep repair deterministic where possible; use model-assisted rewriting only for complex forbidden language.

### Evaluation

Add a local evaluation module with curated test cases:

- Routing accuracy: simple versus Swarm route.
- RAG retrieval accuracy: expected document/category appears in top results.
- Response latency: single-agent and Swarm timing buckets.
- Multi-turn context understanding: follow-up questions resolve references from previous turns.
- Harness repair: missing disclaimer and high-risk warnings are fixed.
- Skill independence: skills run without agent-specific dependencies.

Target metrics for the README/demo:

- Routing accuracy target: 95%.
- RAG retrieval accuracy target: 87%.
- Single-agent latency target: 5-15 seconds when model service responds normally.
- Swarm latency target: 20-30 seconds for complex cases.
- Multi-turn context target: 92%.

The implementation should report measured local values. If model credentials are missing, tests use deterministic fake model adapters and mark live LLM tests as skipped.

## Data Model

PostgreSQL tables:

- `medical_documents`: document metadata and source.
- `medical_document_chunks`: text chunks, metadata, vector embedding.
- `session_summaries`: final answer summaries, mode, agents, risk level, latency, vector embedding.
- `agent_runs`: route decision, iterations, skill calls, token estimates, errors.
- `evaluation_runs`: aggregate metrics and per-case results.

Redis key pattern:

- `medix:session:{sessionId}:messages`
- `medix:swarm:{sessionId}:context`
- `medix:swarm:{sessionId}:subtasks`
- `medix:agent:{agentId}:status`

## Configuration

`application.yml` will include:

- Model provider endpoint and API key via environment variables.
- PostgreSQL URL, username, and password.
- Redis host, port, and optional password.
- MinIO endpoint and credentials.
- Reranker endpoint.
- Agent iteration limits, timeouts, and routing thresholds.
- Feature flags for Redis, MinIO, reranker, and live LLM tests.

Secrets should be overridable through environment variables. The default local database password can be documented for this local project, but production-style examples should use environment variables.

## Error Handling

- Missing LLM key: app starts in degraded mode; deterministic tests still run.
- Redis down: short-term memory and Swarm state fall back to in-memory storage.
- Reranker down: RAG falls back to pgvector similarity order.
- MinIO down: document upload is disabled; bundled resources still import.
- pgvector missing: Flyway migration fails with a clear message explaining extension setup.
- Agent timeout: return partial answer with timeout metadata and safety disclaimer.
- Harness violation: block severe violations, repair fixable output, and log all violations.

## Testing Strategy

Unit tests:

- Each skill.
- Router heuristics.
- Message reducer.
- Harness validator and output repair.
- RAG chunking and metadata mapping.

Integration tests:

- PostgreSQL repository with Testcontainers when available, or local profile.
- Redis shared context with fallback behavior.
- Reranker client fallback.
- Agent Loop with fake model adapter.

End-to-end tests:

- Simple consultation route.
- Complex symptom Swarm route.
- High-risk warning repair.
- Multi-turn follow-up.
- Similar-case retrieval.
- Knowledge import and retrieval.

The first implementation plan should prioritize deterministic tests before live model behavior.

## Migration Plan

1. Scaffold `medix-java/` Spring Boot project.
2. Copy skill markdown and knowledge documents into Java resources.
3. Add configuration, Flyway migrations, and health checks.
4. Build skill registry, seven skills, and RAG infrastructure.
5. Build memory layer and message reducer.
6. Build agents and bounded Agent Loop.
7. Build Swarm router/coordinator with Redis shared context.
8. Build Harness AOP validation and output repair.
9. Build REST APIs and evaluation suite.
10. Write README with local service startup and demo commands.

## Open Constraints

- The workspace root is not a Git repository. The design can be written locally, but committing requires either initializing Git or working inside a repository.
- Live model calls require a valid API key. The current `config.py` has an empty key, so the Java project must support fake model tests and environment-based live configuration.
- Redis, MinIO, and reranker containers exist but were stopped during inspection. The app must document startup commands and degrade gracefully when these are unavailable.

## Approval Gate

The user approved the approach: create a parallel `medix-java/` Java project and keep the Python implementation as reference.

Before implementation begins, this design document should be reviewed by the user. After approval, the next step is to create a detailed implementation plan using the writing-plans workflow.
