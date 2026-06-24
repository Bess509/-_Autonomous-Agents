# Memory Entropy Management Design

## Goal

Add a production-oriented entropy management layer for MediX Java so long-running conversations do not cause short-term memory or Redis Swarm context to grow without bound. The layer must be automatic and transparent to Agents.

The design ports the useful behavior from `medix-agent-swarm/memory/entropy_manager.py` and adapts it to the current Java architecture.

## Current State

The Java project already has:

- `ShortTermMemory`, a singleton Spring component shared by all Agents.
- `MessageWindowReducer`, which deduplicates messages and keeps the most recent five dialogue rounds.
- `SharedContextStore`, which writes Swarm task state to Redis when Redis is enabled.
- `/api/v1/evaluation/summary`, which reports live request counters and target metrics.

Gaps:

- Deduplication is set-based, not MD5-hash based.
- Compression uses a simple joined summary instead of structured key extraction.
- No standalone entropy report object exists.
- No warning log is emitted for high entropy.
- Redis Swarm context keys do not have TTL.
- Entropy status is not exposed through API or evaluation metrics.

## Proposed Components

### `MemoryEntropyManager`

Spring service responsible for all short-term memory entropy control.

Responsibilities:

- `deduplicate(List<ChatMessage>)`: compute MD5 from `role + ":" + content`; preserve first occurrence and remove exact duplicates.
- `compress(List<ChatMessage>)`: keep the latest configurable number of messages, default 10; summarize older messages into one or more `system` summary messages.
- `estimate(List<ChatMessage>)`: compute total count, unique count, duplicate count, duplicate rate, average content length, entropy level, and recommendations.
- `autoClean(List<ChatMessage>)`: run deduplication, compression, estimate, and warning logging in one pass.

Entropy levels:

- `LOW`: normal memory shape.
- `MEDIUM`: message count or duplicate rate is rising.
- `HIGH`: message count, duplicate rate, or average length exceeds configured high-risk thresholds.

### `EntropyReport`

Immutable record returned by entropy estimation.

Fields:

- `totalMessages`
- `uniqueMessages`
- `duplicateCount`
- `duplicateRate`
- `averageMessageLength`
- `entropyLevel`
- `recommendations`

### `EntropyManagementResult`

Immutable record for `autoClean`.

Fields:

- `messages`
- `report`
- `removedDuplicates`
- `compressedMessages`

### `EntropyProperties`

Configuration bound from `medix.memory.entropy`.

Defaults:

```yaml
medix:
  memory:
    entropy:
      enabled: true
      recent-message-limit: 10
      medium-message-threshold: 20
      high-message-threshold: 50
      duplicate-rate-threshold: 0.2
      average-length-threshold: 1000
    redis-context-ttl: 2h
```

### `ShortTermMemory` Integration

`ShortTermMemory` should call `MemoryEntropyManager.autoClean` after each write, replacing the stored session list with the cleaned list. Reads should still use a snapshot to avoid concurrent modification.

This keeps the behavior Agent-transparent:

- Agents still call `memory.add(...)` and `memory.recent(...)`.
- Agent Loop does not need to know entropy management exists.
- Existing shared memory behavior remains intact.

Concurrency requirement:

- Preserve the current copy-on-write update pattern using `ConcurrentHashMap.compute`.
- Do not mutate a stored `List<ChatMessage>` in place.

### Redis Context TTL

`SharedContextStore` should set or refresh TTL whenever it writes to Redis.

Behavior:

- Key format stays `medix:swarm:<sessionId>`.
- On every hash write, call `expire(key, redisContextTtl)`.
- If Redis is unavailable, keep the existing local fallback.
- TTL defaults to 2 hours.

This prevents old Swarm context from staying in Redis indefinitely.

### API and Metrics

Add a memory API:

- `GET /api/v1/memory/entropy/{sessionId}` returns the latest entropy report for one session.

Extend evaluation summary:

- `/api/v1/evaluation/summary` should include memory entropy counters, such as latest entropy level counts or last report summary.

The API should not expose full message content by default, only aggregate health data.

## Compression Strategy

For the first implementation, use deterministic extraction rather than LLM-based summarization.

Older messages should be summarized into a short `system` message containing:

- Main user questions, truncated.
- Important symptoms or risk keywords detected in the older messages.
- Key recommendations or warnings, truncated.

This avoids adding LLM latency or cost inside memory cleanup and keeps tests deterministic.

Possible summary format:

```text
Conversation summary: questions=<...>; symptoms=<...>; recommendations=<...>
```

## Logging

When entropy level is `HIGH`, log a warning with:

- session id
- total messages
- duplicate rate
- average message length
- recommendations

Do not log full medical message content.

## Tests

Unit tests:

- MD5 dedup removes duplicate messages.
- Compression preserves the latest 10 messages.
- Compression creates a structured summary for older messages.
- Entropy estimation returns expected counts, duplicate rate, average length, and level.
- High entropy emits a warning-level outcome through the report.

Integration tests:

- `ShortTermMemory` auto-cleans after writes.
- Agent Loop still has access to recent context.
- Redis Swarm context receives a positive TTL when Redis is enabled.
- Memory entropy API returns aggregate report without message content.
- Evaluation summary includes entropy data.

## Acceptance Criteria

- Long-running sessions do not grow unbounded in `ShortTermMemory`.
- Duplicate tool or assistant messages are removed by MD5 hash.
- Recent 10 messages are preserved exactly after compression.
- High entropy can be detected from message count, duplicate rate, or average length.
- Redis Swarm context keys have TTL when Redis is enabled.
- Existing tests continue to pass.
- New entropy tests cover deduplication, compression, estimation, Redis TTL, and API exposure.

## Out Of Scope

- LLM-based summarization for entropy compression.
- Semantic similarity deduplication.
- Deleting long-term PostgreSQL conversation summaries.
- Background scheduled cleanup of PostgreSQL records.

Those can be added later if the demo evolves into a larger service.
