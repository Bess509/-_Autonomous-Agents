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
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1")
public class AguiController {
    private static final Logger log = LoggerFactory.getLogger(AguiController.class);
    public record ThreadView(String id, UUID ownerUserId, String title, Instant createdAt, Instant updatedAt) {}
    public record ConversationMessageView(long id, String role, String content, Instant createdAt) {}
    public record ThreadDeleteRequest(List<String> threadIds) {}
    public record DeleteResult(int deleted) {}

    private final PermissionService permissions;
    private final SwarmCoordinator coordinator;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final DeepSeekStreamingService deepSeek;
    private final ConversationQueryPreprocessor queryPreprocessor;

    @Autowired
    public AguiController(PermissionService permissions, SwarmCoordinator coordinator, JdbcTemplate jdbc,
                          DeepSeekStreamingService deepSeek, ConversationQueryPreprocessor queryPreprocessor) {
        this.permissions = permissions;
        this.coordinator = coordinator;
        this.mapper = new ObjectMapper();
        this.jdbc = jdbc;
        this.deepSeek = deepSeek;
        this.queryPreprocessor = queryPreprocessor;
    }

    public AguiController(PermissionService permissions, SwarmCoordinator coordinator, JdbcTemplate jdbc) {
        this(permissions, coordinator, jdbc,
                new DeepSeekStreamingService(false, "", "https://api.deepseek.com", "deepseek-v4-flash", new ObjectMapper()), null);
    }

    @PostMapping(value = "/agui", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> runHttp(@RequestBody RunAgentInput input, Authentication auth,
                                                          HttpServletResponse servletResponse) {
        // Tomcat otherwise retains small SSE frames in its response buffer until the Agent work has completed.
        servletResponse.setBufferSize(1);
        return run(input, auth);
    }

    public ResponseEntity<StreamingResponseBody> run(RunAgentInput input, Authentication auth) {
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
        saveConversationMessage(input.threadId(), "user", input.latestUserMessage());
        log.info("[RUN] accepted runId={} threadId={} user={} question={}", input.runId(), input.threadId(), user.id(), compact(input.latestUserMessage()));

        String requested = input.forwardedProps() == null
                ? "consultation_agent"
                : String.valueOf(input.forwardedProps().getOrDefault("agentId", "consultation_agent"));
        PermissionService.Decision decision = permissions.canUseAgent(user, requested, input.runId());
        if (!decision.allowed()) {
            jdbc.update("UPDATE agent_runs SET status = 'DENIED', finished_at = now() WHERE run_id = ?", input.runId());
            throw new Forbidden(decision.reasonCode());
        }

        StreamingResponseBody body = output -> streamRun(output, input, user);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private void streamRun(OutputStream output, RunAgentInput input, AppPrincipal user) throws IOException {
        StringBuilder payload = new StringBuilder();
        EventSequence sequence = new EventSequence(input.runId());
        try {
            primeSse(output);
            emit(output, payload, sequence.event("RUN_STARTED", Map.of("threadId", input.threadId(), "runId", input.runId())));
            emit(output, payload, sequence.event("STEP_STARTED", Map.of("stepName", "安全检查")));
            emit(output, payload, sequence.event("STEP_FINISHED", Map.of("stepName", "安全检查")));
            emit(output, payload, sequence.event("STEP_STARTED", Map.of("stepName", "多 Agent 分析")));
            ConversationQueryPreprocessor.Result query = queryPreprocessor == null
                    ? new ConversationQueryPreprocessor.Result(input.latestUserMessage(), "", false)
                    : queryPreprocessor.rewrite(input.latestUserMessage(), recentMessages(input.threadId()));
            Map<String, Object> requestContext = new LinkedHashMap<>();
            requestContext.put("runId", input.runId());
            requestContext.put("permission.capabilities", permissions.matrix());
            requestContext.put("security.userId", user.id().toString());
            requestContext.put("security.principal", user);
            requestContext.put("rag.retrievalQuery", query.retrievalQuery());
            requestContext.put("conversation.summary", query.conversationSummary());
            requestContext.put("conversation.queryRewritten", query.rewritten());
            log.info("[RUN] routing runId={} retrievalQuery={} rewritten={}", input.runId(), compact(query.retrievalQuery()), query.rewritten());
            SwarmResponse response = coordinator.processDetailed(new AgentRequest(input.latestUserMessage(), input.threadId(),
                    requestContext), permissions.agents(user));
            emit(output, payload, sequence.event("STEP_FINISHED", Map.of("stepName", "多 Agent 分析")));
            String routeStep = "路由 · " + response.decision().reason();
            emit(output, payload, sequence.event("STEP_STARTED", Map.of("stepName", routeStep)));
            emit(output, payload, sequence.event("STEP_FINISHED", Map.of("stepName", routeStep)));
            for (var result : response.agentResults()) {
                emit(output, payload, sequence.event("STEP_STARTED", Map.of("stepName", result.agentId())));
                for (String skill : result.skillCalls()) {
                    PermissionService.Decision capability = permissions.canExecute(user, result.agentId(), skill, input.runId());
                    if (!capability.allowed()) throw new SecurityException(capability.reasonCode());
                    String callId = input.runId() + ":tool:" + sequence.nextNumber();
                    emit(output, payload, sequence.event("TOOL_CALL_START", Map.of("toolCallId", callId, "toolCallName", skill)));
                    emit(output, payload, sequence.event("TOOL_CALL_ARGS", Map.of("toolCallId", callId, "delta", "{}")));
                    emit(output, payload, sequence.event("TOOL_CALL_END", Map.of("toolCallId", callId)));
                    emit(output, payload, sequence.event("TOOL_CALL_RESULT", Map.of("toolCallId", callId,
                            "messageId", callId + ":result", "content", "能力调用已完成", "role", "tool")));
                }
                emit(output, payload, sequence.event("STEP_FINISHED", Map.of("stepName", result.agentId())));
            }
            streamAnswer(output, payload, sequence, input, response);
            saveConversationMessage(input.threadId(), "assistant", response.answer());
            log.info("[RUN] complete runId={} route={} agents={} answer={}", input.runId(), response.decision().reason(),
                    response.decision().requiredAgents(), compact(response.answer()));
            emit(output, payload, sequence.event("STATE_SNAPSHOT", Map.of("snapshot", Map.of(
                    "route", response.decision().reason(), "agents", response.decision().requiredAgents(),
                    "skills", response.agentResults().stream().flatMap(result -> result.skillCalls().stream()).distinct().toList()))));
            emit(output, payload, sequence.event("RUN_FINISHED", Map.of("threadId", input.threadId(), "runId", input.runId(),
                    "outcome", Map.of("type", "success"))));
            saveResult(input.runId(), "COMPLETED", payload.toString());
        } catch (SecurityException denied) {
            log.warn("[RUN_ERROR] runId={} type=security code={}", input.runId(), denied.getMessage());
            emit(output, payload, sequence.event("RUN_ERROR", Map.of("message", "能力权限不足", "code", denied.getMessage())));
            saveResult(input.runId(), "DENIED", payload.toString());
        } catch (Exception failure) {
            log.error("[RUN_ERROR] runId={} type={}", input.runId(), failure.getClass().getSimpleName(), failure);
            emit(output, payload, sequence.event("RUN_ERROR", Map.of("message", "运行失败，请稍后重试", "code", "RUN_FAILED")));
            saveResult(input.runId(), "FAILED", payload.toString());
        }
    }

    private void streamAnswer(OutputStream output, StringBuilder payload, EventSequence sequence,
                              RunAgentInput input, SwarmResponse response) throws Exception {
        String messageId = input.runId() + ":assistant";
        String thinkingId = input.runId() + ":thinking";
        emit(output, payload, sequence.event("THINKING_START", Map.of("messageId", thinkingId)));
        emit(output, payload, sequence.event("TEXT_MESSAGE_START", Map.of("messageId", messageId, "role", "assistant")));
        if (deepSeek.enabled()) {
            boolean[] thinkingEnded = {false};
            boolean[] contentStarted = {false};
            try {
                deepSeek.stream(input.latestUserMessage(), response.answer(), delta -> {
                    try {
                        if (!delta.reasoning().isEmpty())
                            emit(output, payload, sequence.event("THINKING_CONTENT", Map.of("messageId", thinkingId, "delta", delta.reasoning())));
                        if (!delta.content().isEmpty()) {
                            if (!thinkingEnded[0]) {
                                emit(output, payload, sequence.event("THINKING_END", Map.of("messageId", thinkingId)));
                                thinkingEnded[0] = true;
                            }
                            contentStarted[0] = true;
                            emit(output, payload, sequence.event("TEXT_MESSAGE_CONTENT", Map.of("messageId", messageId, "delta", delta.content())));
                        }
                    } catch (IOException failure) { throw new StreamWriteFailure(failure); }
                });
            } catch (Exception failure) {
                log.warn("[FALLBACK] component=FINAL_STREAM reason=deepseek_stream_failure type={} contentStarted={}",
                        failure.getClass().getSimpleName(), contentStarted[0]);
                if (!contentStarted[0]) {
                    if (!thinkingEnded[0]) {
                        emit(output, payload, sequence.event("THINKING_END", Map.of("messageId", thinkingId)));
                        thinkingEnded[0] = true;
                    }
                    for (String part : chunks(response.answer(), 80))
                        emit(output, payload, sequence.event("TEXT_MESSAGE_CONTENT", Map.of("messageId", messageId, "delta", part)));
                }
            }
            if (!thinkingEnded[0]) emit(output, payload, sequence.event("THINKING_END", Map.of("messageId", thinkingId)));
        } else {
            emit(output, payload, sequence.event("THINKING_CONTENT", Map.of("messageId", thinkingId,
                    "delta", "已完成意图路由、医疗安全检查和多 Agent 证据整合。")));
            emit(output, payload, sequence.event("THINKING_END", Map.of("messageId", thinkingId)));
            for (String part : chunks(response.answer(), 80))
                emit(output, payload, sequence.event("TEXT_MESSAGE_CONTENT", Map.of("messageId", messageId, "delta", part)));
        }
        emit(output, payload, sequence.event("TEXT_MESSAGE_END", Map.of("messageId", messageId)));
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

    @GetMapping("/me/threads/{id}/messages")
    public List<ConversationMessageView> messages(@PathVariable String id, Authentication authentication) {
        UUID owner = ((AppPrincipal) authentication.getPrincipal()).id();
        Boolean owned = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM conversation_threads WHERE id = ? AND owner_user_id = ?)",
                Boolean.class, id, owner);
        if (!Boolean.TRUE.equals(owned)) throw new Forbidden("THREAD_NOT_OWNED");
        return jdbc.query("""
                SELECT id, role, content, created_at FROM conversation_messages
                WHERE thread_id = ? ORDER BY created_at, id
                """, (rs, row) -> new ConversationMessageView(rs.getLong("id"), rs.getString("role"),
                rs.getString("content"), rs.getTimestamp("created_at").toInstant()), id);
    }

    @DeleteMapping("/me/threads/{id}")
    @Transactional
    public DeleteResult deleteThread(@PathVariable String id, Authentication authentication) {
        return deleteThreads(List.of(id), ((AppPrincipal) authentication.getPrincipal()).id());
    }

    @DeleteMapping("/me/threads")
    @Transactional
    public DeleteResult deleteThreads(@RequestBody ThreadDeleteRequest request, Authentication authentication) {
        if (request == null) throw new Invalid();
        return deleteThreads(request.threadIds(), ((AppPrincipal) authentication.getPrincipal()).id());
    }

    private DeleteResult deleteThreads(List<String> requestedIds, UUID owner) {
        if (requestedIds == null || requestedIds.isEmpty() || requestedIds.size() > 100) throw new Invalid();
        List<String> ids = requestedIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty() || ids.size() != requestedIds.size()) throw new Invalid();
        for (String id : ids) {
            Boolean owned = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM conversation_threads WHERE id = ? AND owner_user_id = ?)",
                    Boolean.class, id, owner);
            if (!Boolean.TRUE.equals(owned)) throw new Forbidden("THREAD_NOT_OWNED");
            Integer running = jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE thread_id = ? AND status = 'RUNNING'",
                    Integer.class, id);
            if (running != null && running > 0) throw new RunInProgress();
        }
        for (String id : ids) {
            jdbc.update("DELETE FROM conversation_summaries WHERE session_id = ?", id);
            jdbc.update("DELETE FROM agent_runs WHERE thread_id = ?", id);
            jdbc.update("DELETE FROM conversation_threads WHERE id = ? AND owner_user_id = ?", id, owner);
        }
        return new DeleteResult(ids.size());
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

    private List<RunAgentInput.Message> recentMessages(String threadId) {
        List<RunAgentInput.Message> newestFirst = jdbc.query("""
                SELECT role, content FROM conversation_messages
                WHERE thread_id = ? ORDER BY created_at DESC, id DESC LIMIT 10
                """, (rs, row) -> new RunAgentInput.Message(null, rs.getString("role"), rs.getString("content")), threadId);
        java.util.Collections.reverse(newestFirst);
        return newestFirst;
    }

    private void saveConversationMessage(String threadId, String role, String content) {
        if (content == null || content.isBlank()) return;
        jdbc.update("INSERT INTO conversation_messages(thread_id, role, content) VALUES (?, ?, ?)", threadId, role, content.trim());
        jdbc.update("UPDATE conversation_threads SET updated_at = now() WHERE id = ?", threadId);
    }

    private ThreadView thread(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ThreadView(rs.getString("id"), rs.getObject("owner_user_id", UUID.class), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private ResponseEntity<StreamingResponseBody> sse(String payload) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(output -> {
                    primeSse(output);
                    output.write(payload.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                });
    }

    private void emit(OutputStream output, StringBuilder payload, Map<String, Object> event) throws IOException {
        String frame = encode(List.of(event));
        payload.append(frame);
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void primeSse(OutputStream output) throws IOException {
        // Tomcat's connector retains sub-buffer responses even after flush(); an SSE comment crosses that
        // boundary without becoming an AG-UI event, so RUN_STARTED reaches the browser before model work.
        output.write((":" + " ".repeat(8192) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void validate(RunAgentInput input) {
        if (input == null || blank(input.threadId()) || blank(input.runId()) || blank(input.latestUserMessage())
                || input.threadId().length() > 120 || input.runId().length() > 120
                || input.latestUserMessage().length() > 4000) throw new Invalid();
    }

    private String compact(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "…";
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

    private static final class StreamWriteFailure extends RuntimeException {
        private StreamWriteFailure(IOException cause) { super(cause); }
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
