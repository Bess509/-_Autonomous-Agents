package com.medix.agui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medix.agent.AgentRequest;
import com.medix.permission.PermissionService;
import com.medix.security.AppPrincipal;
import com.medix.swarm.SwarmCoordinator;
import com.medix.swarm.SwarmResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AguiController {
    public record ThreadView(String id, UUID ownerUserId, String title, Instant createdAt, Instant updatedAt) {}

    private final PermissionService permissions;
    private final SwarmCoordinator coordinator;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public AguiController(PermissionService permissions, SwarmCoordinator coordinator, JdbcTemplate jdbc) {
        this.permissions = permissions;
        this.coordinator = coordinator;
        this.mapper = new ObjectMapper();
        this.jdbc = jdbc;
    }

    @PostMapping(value = "/agui", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> run(@RequestBody RunAgentInput input, Authentication auth) {
        AppPrincipal user = (AppPrincipal) auth.getPrincipal();
        validate(input);
        ThreadView thread = ownedThreadOrCreate(input.threadId(), user, input.latestUserMessage());
        String replay = replay(input.runId(), input.threadId(), user.id());
        if (replay != null) return sse(replay);

        try {
            jdbc.update("""
                    INSERT INTO agent_runs(run_id, thread_id, user_id, status)
                    VALUES (?, ?, ?, 'RUNNING')
                    """, input.runId(), thread.id(), user.id());
        } catch (DuplicateKeyException duplicate) {
            String raced = replay(input.runId(), input.threadId(), user.id());
            if (raced != null) return sse(raced);
            throw new RunInProgress();
        }

        String requested = input.forwardedProps() == null
                ? "consultation_agent"
                : String.valueOf(input.forwardedProps().getOrDefault("agentId", "consultation_agent"));
        PermissionService.Decision decision = permissions.canUseAgent(user, requested, input.runId());
        if (!decision.allowed()) {
            jdbc.update("UPDATE agent_runs SET status = 'DENIED', finished_at = now() WHERE run_id = ?", input.runId());
            throw new Forbidden(decision.reasonCode());
        }

        String payload;
        EventSequence sequence = new EventSequence(input.runId());
        try {
            List<Map<String, Object>> events = new ArrayList<>();
            events.add(sequence.event("RUN_STARTED", Map.of("threadId", input.threadId(), "runId", input.runId())));
            events.add(sequence.event("STEP_STARTED", Map.of("stepName", "安全检查")));
            events.add(sequence.event("STEP_FINISHED", Map.of("stepName", "安全检查")));
            SwarmResponse response = coordinator.processDetailed(new AgentRequest(input.latestUserMessage(), input.threadId(),
                    Map.of("runId", input.runId(), "permission.capabilities", permissions.matrix(),
                            "security.userId", user.id().toString(), "security.principal", user)), permissions.agents(user));
            String routeStep = "路由 · " + response.decision().reason();
            events.add(sequence.event("STEP_STARTED", Map.of("stepName", routeStep)));
            events.add(sequence.event("STEP_FINISHED", Map.of("stepName", routeStep)));
            for (var result : response.agentResults()) {
                events.add(sequence.event("STEP_STARTED", Map.of("stepName", result.agentId())));
                for (String skill : result.skillCalls()) {
                    PermissionService.Decision capability = permissions.canExecute(user, result.agentId(), skill, input.runId());
                    if (!capability.allowed()) throw new SecurityException(capability.reasonCode());
                    String callId = input.runId() + ":tool:" + sequence.nextNumber();
                    events.add(sequence.event("TOOL_CALL_START", Map.of("toolCallId", callId, "toolCallName", skill)));
                    events.add(sequence.event("TOOL_CALL_ARGS", Map.of("toolCallId", callId, "delta", "{}")));
                    events.add(sequence.event("TOOL_CALL_END", Map.of("toolCallId", callId)));
                    events.add(sequence.event("TOOL_CALL_RESULT", Map.of("toolCallId", callId, "messageId", callId + ":result",
                            "content", "能力调用已完成", "role", "tool")));
                }
                events.add(sequence.event("STEP_FINISHED", Map.of("stepName", result.agentId())));
            }
            String messageId = input.runId() + ":assistant";
            events.add(sequence.event("TEXT_MESSAGE_START", Map.of("messageId", messageId, "role", "assistant")));
            for (String part : chunks(response.answer(), 80)) {
                events.add(sequence.event("TEXT_MESSAGE_CONTENT", Map.of("messageId", messageId, "delta", part)));
            }
            events.add(sequence.event("TEXT_MESSAGE_END", Map.of("messageId", messageId)));
            events.add(sequence.event("STATE_SNAPSHOT", Map.of("snapshot", Map.of(
                    "route", response.decision().reason(),
                    "agents", response.decision().requiredAgents(),
                    "skills", response.agentResults().stream().flatMap(result -> result.skillCalls().stream()).distinct().toList()
            ))));
            events.add(sequence.event("RUN_FINISHED", Map.of("threadId", input.threadId(), "runId", input.runId(),
                    "outcome", Map.of("type", "success"))));
            payload = encode(events);
            saveResult(input.runId(), "COMPLETED", payload);
        } catch (SecurityException denied) {
            payload = encode(List.of(sequence.event("RUN_ERROR", Map.of("message", "能力权限不足", "code", denied.getMessage()))));
            saveResult(input.runId(), "DENIED", payload);
        } catch (RuntimeException failure) {
            payload = encode(List.of(sequence.event("RUN_ERROR", Map.of("message", "运行失败，请稍后重试", "code", "RUN_FAILED"))));
            saveResult(input.runId(), "FAILED", payload);
        }
        return sse(payload);
    }

    @GetMapping("/me/threads")
    public List<ThreadView> threads(Authentication authentication) {
        UUID owner = ((AppPrincipal) authentication.getPrincipal()).id();
        return jdbc.query("""
                SELECT id, owner_user_id, title, created_at, updated_at
                FROM conversation_threads WHERE owner_user_id = ? ORDER BY updated_at DESC
                """, (rs, row) -> thread(rs), owner);
    }

    @GetMapping("/me/threads/{id}")
    public ThreadView thread(@PathVariable String id, Authentication authentication) {
        UUID owner = ((AppPrincipal) authentication.getPrincipal()).id();
        return jdbc.query("""
                SELECT id, owner_user_id, title, created_at, updated_at
                FROM conversation_threads WHERE id = ? AND owner_user_id = ?
                """, (rs, row) -> thread(rs), id, owner).stream().findFirst().orElseThrow(() -> new Forbidden("THREAD_NOT_OWNED"));
    }

    private ThreadView ownedThreadOrCreate(String id, AppPrincipal owner, String title) {
        List<ThreadView> existing = jdbc.query("""
                SELECT id, owner_user_id, title, created_at, updated_at FROM conversation_threads WHERE id = ?
                """, (rs, row) -> thread(rs), id);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().ownerUserId().equals(owner.id())) throw new Forbidden("THREAD_NOT_OWNED");
            return existing.getFirst();
        }
        try {
            jdbc.update("INSERT INTO conversation_threads(id, owner_user_id, title) VALUES (?, ?, ?)",
                    id, owner.id(), title.length() > 180 ? title.substring(0, 180) : title);
        } catch (DuplicateKeyException race) {
            return ownedThreadOrCreate(id, owner, title);
        }
        return thread(id, new PrincipalAuthentication(owner));
    }

    private String replay(String runId, String threadId, UUID owner) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT user_id, thread_id, status, result FROM agent_runs WHERE run_id = ?
                """, runId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        if (!owner.equals(row.get("user_id")) || !threadId.equals(row.get("thread_id"))) {
            throw new Forbidden("RUN_NOT_OWNED");
        }
        if (row.get("result") == null) return null;
        try {
            JsonNode result = mapper.readTree(String.valueOf(row.get("result")));
            return result.path("sse").asText();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored run result is invalid", exception);
        }
    }

    private void saveResult(String runId, String status, String payload) {
        try {
            String json = mapper.writeValueAsString(Map.of("sse", payload));
            jdbc.update("UPDATE agent_runs SET status = ?, finished_at = now(), result = CAST(? AS jsonb) WHERE run_id = ?",
                    status, json, runId);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist run result", exception);
        }
    }

    private ThreadView thread(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ThreadView(rs.getString("id"), rs.getObject("owner_user_id", UUID.class), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private ResponseEntity<String> sse(String payload) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(payload);
    }

    private void validate(RunAgentInput input) {
        if (input == null || blank(input.threadId()) || blank(input.runId()) || blank(input.latestUserMessage())) throw new Invalid();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String encode(List<Map<String, Object>> events) {
        return events.stream().map(event -> {
            try {
                return "id: " + event.get("eventId") + "\n" + "data: " + mapper.writeValueAsString(event) + "\n\n";
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(exception);
            }
        }).reduce("", String::concat);
    }

    private static final class EventSequence {
        private final String runId;
        private long sequence;

        private EventSequence(String runId) { this.runId = runId; }
        private long nextNumber() { return sequence + 1; }
        private Map<String, Object> event(String type, Map<String, Object> data) {
            long number = ++sequence;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.put("timestamp", number);
            event.put("runId", runId);
            event.put("sequence", number);
            event.put("eventId", runId + ":" + number);
            event.putAll(data);
            return event;
        }
    }

    private List<String> chunks(String value, int size) {
        if (value == null || value.isEmpty()) return List.of("");
        List<String> chunks = new ArrayList<>();
        for (int offset = 0; offset < value.length(); offset += size) {
            chunks.add(value.substring(offset, Math.min(offset + size, value.length())));
        }
        return chunks;
    }

    private record PrincipalAuthentication(AppPrincipal principal) implements Authentication {
        @Override public List<org.springframework.security.core.GrantedAuthority> getAuthorities() { return List.of(); }
        @Override public Object getCredentials() { return ""; }
        @Override public Object getDetails() { return null; }
        @Override public Object getPrincipal() { return principal; }
        @Override public boolean isAuthenticated() { return true; }
        @Override public void setAuthenticated(boolean authenticated) {}
        @Override public String getName() { return principal.username(); }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    static class Invalid extends RuntimeException {}

    @ResponseStatus(HttpStatus.FORBIDDEN)
    static class Forbidden extends RuntimeException {
        Forbidden(String code) { super(code); }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    static class RunInProgress extends RuntimeException {}
}
