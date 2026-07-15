package com.medix.api;

import com.medix.agent.AgentRequest;
import com.medix.evaluation.EvaluationService;
import com.medix.memory.ConversationSummary;
import com.medix.memory.LongTermMemoryService;
import com.medix.permission.PermissionService;
import com.medix.security.AppPrincipal;
import com.medix.storage.ChatArchive;
import com.medix.storage.ChatArchiveService;
import com.medix.swarm.SwarmCoordinator;
import com.medix.swarm.SwarmResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final SwarmCoordinator swarmCoordinator;
    private final LongTermMemoryService longTermMemoryService;
    private final ChatArchiveService archiveService;
    private final EvaluationService evaluationService;
    private final PermissionService permissionService;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatController(
            SwarmCoordinator swarmCoordinator,
            LongTermMemoryService longTermMemoryService,
            ChatArchiveService archiveService,
            EvaluationService evaluationService,
            PermissionService permissionService
    ) {
        this.swarmCoordinator = swarmCoordinator;
        this.longTermMemoryService = longTermMemoryService;
        this.archiveService = archiveService;
        this.evaluationService = evaluationService;
        this.permissionService = permissionService;
    }

    public ChatController(SwarmCoordinator swarmCoordinator, LongTermMemoryService longTermMemoryService,
                          ChatArchiveService archiveService, EvaluationService evaluationService) {
        this(swarmCoordinator, longTermMemoryService, archiveService, evaluationService, null);
    }

    @PostMapping
    public ResponseEntity<?> chat(@RequestBody ChatRequest request, Authentication authentication) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "question is required"));
        }
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? UUID.randomUUID().toString()
                : request.sessionId();
        Map<String, Object> context = new LinkedHashMap<>();
        if (request.context() != null) {
            context.putAll(request.context());
        }
        AppPrincipal principal = authentication == null ? null : (AppPrincipal) authentication.getPrincipal();
        if (principal != null && permissionService != null) {
            context.put("permission.capabilities", permissionService.matrix());
            context.put("security.userId", principal.id().toString());
            context.put("security.principal", principal);
            context.put("runId", sessionId);
        }
        List<ConversationSummary> similarCases = longTermMemoryService.similarCases(request.question(), 3);
        if (!similarCases.isEmpty()) {
            context.put("similarCases", similarCases);
        }

        long started = System.nanoTime();
        SwarmResponse response;
        try {
            response = principal == null || permissionService == null
                    ? swarmCoordinator.processDetailed(new AgentRequest(request.question(), sessionId, context))
                    : swarmCoordinator.processDetailed(new AgentRequest(request.question(), sessionId, context), permissionService.agents(principal));
        } catch (SecurityException denied) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", denied.getMessage(), "message", "当前账号无权使用所需 Agent"));
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;

        longTermMemoryService.remember(sessionId, request.question(), response.answer());
        archiveService.archive(new ChatArchive(
                sessionId,
                request.question(),
                response.answer(),
                response.decision().mode().name(),
                Instant.now()
        ));
        evaluationService.record(response, latencyMs);

        return ResponseEntity.ok(new ChatResponse(
                sessionId,
                response.decision().mode(),
                response.decision().primaryAgent(),
                response.decision().requiredAgents(),
                response.answer(),
                latencyMs,
                response.agentResults(),
                similarCases,
                response.sharedContext()
        ));
    }

    public ResponseEntity<?> chat(ChatRequest request) { return chat(request, null); }
}
