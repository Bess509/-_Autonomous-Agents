package com.medix.agentscope;

import com.medix.permission.PermissionService;
import com.medix.security.AppPrincipal;
import com.medix.skill.SkillRegistry;
import com.medix.skill.SkillRequest;
import com.medix.skill.SkillResult;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Request-scoped tool object with authorization, deduplication and a hard call budget. */
final class MedicalAgentScopeTools {
    private static final Logger log = LoggerFactory.getLogger(MedicalAgentScopeTools.class);
    private final String agentId;
    private final String sessionId;
    private final Map<String, Object> context;
    private final SkillRegistry registry;
    private final PermissionService permissions;
    private final int maxSkillCalls;
    private final AtomicInteger requestedCalls = new AtomicInteger();
    private final Map<String, SkillResult> cache = new ConcurrentHashMap<>();
    private final List<String> invokedCapabilities = new CopyOnWriteArrayList<>();
    private volatile SkillResult reliableEvidence;

    MedicalAgentScopeTools(String agentId, String sessionId, Map<String, Object> context, SkillRegistry registry,
                           PermissionService permissions) {
        this(agentId, sessionId, context, registry, permissions, 3);
    }

    MedicalAgentScopeTools(String agentId, String sessionId, Map<String, Object> context, SkillRegistry registry,
                           PermissionService permissions, int maxSkillCalls) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        // Some test and integration paths provide Map.of(...). Keep the tool's mutable
        // per-request flags local instead of assuming the caller's map is writable.
        this.context = new ConcurrentHashMap<>(context == null ? Map.of() : context);
        this.registry = registry;
        this.permissions = permissions;
        this.maxSkillCalls = Math.max(1, maxSkillCalls);
    }

    @Tool(name="analyze_symptoms", description="Analyze reported symptoms without making a diagnosis")
    public SkillResult analyze(@ToolParam(name="query", description="Symptoms to analyze") String query) { return invoke("analyze_symptoms", query); }
    @Tool(name="assess_risk", description="Assess urgency and emergency warning signs")
    public SkillResult risk(@ToolParam(name="query", description="Symptoms and context") String query) { return invoke("assess_risk", query); }
    @Tool(name="search_knowledge", description="Search reviewed medical knowledge")
    public SkillResult search(@ToolParam(name="query", description="Medical knowledge query") String query) {
        Object rewritten = context.get("rag.retrievalQuery");
        return invoke("search_knowledge", rewritten instanceof String value && !value.isBlank() ? value : query);
    }
    @Tool(name="clinical_guideline", description="Retrieve reviewed clinical guideline evidence")
    public SkillResult guideline(@ToolParam(name="query", description="Guideline question") String query) { return invoke("clinical_guideline", query); }
    @Tool(name="recommend_lifestyle", description="Provide low-risk lifestyle guidance")
    public SkillResult lifestyle(@ToolParam(name="query", description="Lifestyle question") String query) { return invoke("recommend_lifestyle", query); }
    @Tool(name="disease_code", description="Look up a disease classification code")
    public SkillResult code(@ToolParam(name="query", description="Disease name") String query) { return invoke("disease_code", query); }
    @Tool(name="deep_research", description="Research medical evidence and uncertainty")
    public SkillResult research(@ToolParam(name="query", description="Research question") String query) { return invoke("deep_research", query); }
    @Tool(name="safe_medical_guidance", description="Ask for essential missing information and give conservative medical guidance")
    public SkillResult safeGuidance(@ToolParam(name="query", description="Current medical question") String query) { return invoke("safe_medical_guidance", query); }

    private SkillResult invoke(String capability, String query) {
        Object principal = context.get("security.principal");
        String runId = String.valueOf(context.getOrDefault("runId", sessionId));
        if (!(principal instanceof AppPrincipal user) || !permissions.canExecute(user, agentId, capability, runId).allowed()) {
            throw new SecurityException("AGENT_CAPABILITY_GRANT_MISSING");
        }
        if (query == null || query.isBlank() || query.length() > 4000) throw new IllegalArgumentException("query must be 1..4000 characters");
        int call = requestedCalls.incrementAndGet();
        if (call > maxSkillCalls) {
            log.warn("[FALLBACK] component=TOOL agent={} reason=max_skill_calls_reached limit={}", agentId, maxSkillCalls);
            throw new ToolCallLimitReached();
        }
        invokedCapabilities.add(capability);
        String key = capability + "\u0000" + normalize(query);
        SkillResult cached = cache.get(key);
        if (cached != null) {
            log.info("[DEDUP] component=TOOL agent={} capability={} reason=duplicate_query_cache_hit", agentId, capability);
            return cached;
        }
        try {
            log.info("[TOOL] agent={} capability={} query={} call={}/{}", agentId, capability, compact(query), call, maxSkillCalls);
            SkillResult result = registry.invoke(capability, new SkillRequest(query, sessionId, context));
            cache.putIfAbsent(key, result);
            if ("RELIABLE_RAG_EVIDENCE".equals(String.valueOf(result.metadata().get("evidenceStatus")))) {
                reliableEvidence = result;
                context.put("rag.evidence.available", true);
            }
            log.info("[TOOL] agent={} capability={} success={} metadata={}", agentId, capability, result.success(), result.metadata());
            return result;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("LLM_TOOL_EXECUTION_FAILED", failure);
        }
    }

    List<String> invokedCapabilities() { return List.copyOf(invokedCapabilities); }
    SkillResult reliableEvidence() { return reliableEvidence; }

    private String normalize(String value) { return value.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT); }
    private String compact(String value) { String normalized = normalize(value); return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "…"; }
    private static final class ToolCallLimitReached extends RuntimeException { }
}
