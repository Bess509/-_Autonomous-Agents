# Memory Entropy Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add automatic entropy management for short-term memory and Redis Swarm context.

**Architecture:** Introduce a focused `MemoryEntropyManager` service with records for reports/results and wire it into `ShortTermMemory`. Extend Redis context writes with TTL, expose entropy reports through REST and evaluation summary, and keep the behavior transparent to Agents.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data Redis, JUnit 6, AssertJ, Mockito.

---

## File Structure

- Create `medix-java/src/main/java/com/medix/memory/EntropyLevel.java`: enum for `LOW`, `MEDIUM`, `HIGH`.
- Create `medix-java/src/main/java/com/medix/memory/EntropyReport.java`: immutable entropy metrics.
- Create `medix-java/src/main/java/com/medix/memory/EntropyManagementResult.java`: cleaned messages and report.
- Create `medix-java/src/main/java/com/medix/memory/MemoryProperties.java`: `medix.memory` configuration.
- Create `medix-java/src/main/java/com/medix/memory/MemoryEntropyManager.java`: MD5 dedup, compression, estimation, warning logging.
- Modify `medix-java/src/main/java/com/medix/memory/ShortTermMemory.java`: auto-clean on write and expose entropy reports.
- Modify `medix-java/src/main/java/com/medix/swarm/SharedContextStore.java`: set Redis TTL on writes.
- Create `medix-java/src/main/java/com/medix/api/MemoryController.java`: expose session entropy report.
- Modify `medix-java/src/main/java/com/medix/evaluation/EvaluationService.java`: include memory entropy overview.
- Modify `medix-java/src/main/resources/application.yml`: add memory entropy and Redis TTL config.
- Modify `medix-java/README.md`: document entropy management and Redis TTL.
- Create tests in `medix-java/src/test/java/com/medix/memory/MemoryEntropyManagerTest.java`.
- Create tests in `medix-java/src/test/java/com/medix/memory/ShortTermMemoryEntropyTest.java`.
- Create tests in `medix-java/src/test/java/com/medix/swarm/SharedContextStoreTest.java`.
- Update `medix-java/src/test/java/com/medix/api/ApiSmokeTest.java` for memory entropy API and evaluation summary.

---

### Task 1: Entropy Manager Core

**Files:**
- Create: `medix-java/src/test/java/com/medix/memory/MemoryEntropyManagerTest.java`
- Create: `medix-java/src/main/java/com/medix/memory/EntropyLevel.java`
- Create: `medix-java/src/main/java/com/medix/memory/EntropyReport.java`
- Create: `medix-java/src/main/java/com/medix/memory/EntropyManagementResult.java`
- Create: `medix-java/src/main/java/com/medix/memory/MemoryProperties.java`
- Create: `medix-java/src/main/java/com/medix/memory/MemoryEntropyManager.java`

- [ ] **Step 1: Write failing tests**

Create tests for MD5 deduplication, sliding-window compression, structured summary, and entropy estimation.

- [ ] **Step 2: Verify RED**

Run: `mvn test "-Dtest=MemoryEntropyManagerTest"`

Expected: compilation fails because `MemoryEntropyManager`, `EntropyReport`, and `EntropyLevel` do not exist.

- [ ] **Step 3: Implement core classes**

Implement:

```java
public enum EntropyLevel { LOW, MEDIUM, HIGH }
```

`MemoryEntropyManager.autoClean(sessionId, messages)` must:

1. Estimate the original message list.
2. Deduplicate by MD5 of `role + ":" + content`.
3. Compress older messages into one `system` summary when size exceeds `recentMessageLimit`.
4. Estimate the cleaned list.
5. Log warning when the cleaned report is `HIGH`.

- [ ] **Step 4: Verify GREEN**

Run: `mvn test "-Dtest=MemoryEntropyManagerTest"`

Expected: all core entropy tests pass.

- [ ] **Step 5: Commit**

```bash
git add medix-java/src/main/java/com/medix/memory medix-java/src/test/java/com/medix/memory/MemoryEntropyManagerTest.java
git commit -m "feat: add memory entropy manager"
```

---

### Task 2: Short-Term Memory Integration

**Files:**
- Modify: `medix-java/src/main/java/com/medix/memory/ShortTermMemory.java`
- Create: `medix-java/src/test/java/com/medix/memory/ShortTermMemoryEntropyTest.java`

- [ ] **Step 1: Write failing integration tests**

Tests must show that repeated writes are deduplicated, long histories are compressed, and `entropyReport(sessionId)` returns aggregate metrics without exposing full content.

- [ ] **Step 2: Verify RED**

Run: `mvn test "-Dtest=ShortTermMemoryEntropyTest"`

Expected: fails because `ShortTermMemory` has no entropy report API and does not auto-clean on write.

- [ ] **Step 3: Wire entropy manager**

`ShortTermMemory.add(...)` should call `entropyManager.autoClean(...)` inside `ConcurrentHashMap.compute` and store the cleaned list. Add:

```java
public EntropyReport entropyReport(String sessionId)
public Map<String, Object> entropyOverview()
```

- [ ] **Step 4: Verify GREEN**

Run: `mvn test "-Dtest=ShortTermMemoryEntropyTest,AgentLoopEngineTest,SwarmCoordinatorTest"`

Expected: all listed tests pass.

- [ ] **Step 5: Commit**

```bash
git add medix-java/src/main/java/com/medix/memory/ShortTermMemory.java medix-java/src/test/java/com/medix/memory/ShortTermMemoryEntropyTest.java
git commit -m "feat: apply entropy management to short term memory"
```

---

### Task 3: Redis TTL

**Files:**
- Modify: `medix-java/src/main/java/com/medix/swarm/SharedContextStore.java`
- Modify: `medix-java/src/main/resources/application.yml`
- Create: `medix-java/src/test/java/com/medix/swarm/SharedContextStoreTest.java`

- [ ] **Step 1: Write failing TTL test**

Mock `StringRedisTemplate` and `HashOperations`; verify `expire("medix:swarm:s1", Duration.ofHours(2))` is called after a put.

- [ ] **Step 2: Verify RED**

Run: `mvn test "-Dtest=SharedContextStoreTest"`

Expected: fails because TTL is not set.

- [ ] **Step 3: Implement TTL**

Inject `MemoryProperties` into `SharedContextStore`; call `redisTemplate.expire(redisKey(sessionId), redisContextTtl)` after each hash write.

- [ ] **Step 4: Verify GREEN**

Run: `mvn test "-Dtest=SharedContextStoreTest,SwarmCoordinatorTest"`

Expected: all listed tests pass.

- [ ] **Step 5: Commit**

```bash
git add medix-java/src/main/java/com/medix/swarm/SharedContextStore.java medix-java/src/main/resources/application.yml medix-java/src/test/java/com/medix/swarm/SharedContextStoreTest.java
git commit -m "feat: add ttl to redis swarm context"
```

---

### Task 4: API and Evaluation Metrics

**Files:**
- Create: `medix-java/src/main/java/com/medix/api/MemoryController.java`
- Modify: `medix-java/src/main/java/com/medix/evaluation/EvaluationService.java`
- Modify: `medix-java/src/test/java/com/medix/api/ApiSmokeTest.java`

- [ ] **Step 1: Write failing API tests**

Extend `ApiSmokeTest` to call:

- `GET /api/v1/memory/entropy/api-test`
- `GET /api/v1/evaluation/summary`

Assert both responses contain entropy aggregate data and do not contain raw medical message content.

- [ ] **Step 2: Verify RED**

Run: `mvn test "-Dtest=ApiSmokeTest"`

Expected: fails because memory entropy endpoint and evaluation entropy data do not exist.

- [ ] **Step 3: Implement API and metrics**

`MemoryController` should return `ShortTermMemory.entropyReport(sessionId)`.

`EvaluationService.summary()` should include:

```java
"memoryEntropy", shortTermMemory.entropyOverview()
```

- [ ] **Step 4: Verify GREEN**

Run: `mvn test "-Dtest=ApiSmokeTest"`

Expected: API smoke tests pass.

- [ ] **Step 5: Commit**

```bash
git add medix-java/src/main/java/com/medix/api/MemoryController.java medix-java/src/main/java/com/medix/evaluation/EvaluationService.java medix-java/src/test/java/com/medix/api/ApiSmokeTest.java
git commit -m "feat: expose memory entropy metrics"
```

---

### Task 5: Final Verification and Documentation

**Files:**
- Modify: `medix-java/README.md`

- [ ] **Step 1: Update README**

Document entropy management, configuration, Redis TTL, and API endpoint.

- [ ] **Step 2: Run full verification**

Run: `mvn test`

Expected: all tests pass.

- [ ] **Step 3: Manual Redis verification**

Start the service with:

```powershell
$env:MEDIX_REDIS_ENABLED='true'
$env:MEDIX_REDIS_HEALTH_ENABLED='true'
$env:MEDIX_REDIS_PASSWORD='123321'
mvn spring-boot:run
```

Call chat endpoint and verify Redis TTL:

```powershell
docker exec code-guardian-redis redis-cli -a 123321 ttl medix:swarm:redis-demo
```

Expected: positive TTL.

- [ ] **Step 4: Commit documentation**

```bash
git add medix-java/README.md
git commit -m "docs: document memory entropy management"
```

- [ ] **Step 5: Final status**

Report test evidence, Redis TTL evidence, and changed commits.
