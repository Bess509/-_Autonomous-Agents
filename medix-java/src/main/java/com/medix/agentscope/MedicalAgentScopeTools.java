package com.medix.agentscope;

import com.medix.skill.SkillRegistry;
import com.medix.skill.SkillRequest;
import com.medix.skill.SkillResult;
import com.medix.permission.PermissionService;
import com.medix.security.AppPrincipal;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Request-scoped tool object: authorization is re-evaluated immediately before every invocation. */
final class MedicalAgentScopeTools {
    private final String agentId;
    private final String sessionId;
    private final Map<String, Object> context;
    private final SkillRegistry registry;
    private final PermissionService permissions;
    private final List<String> invokedCapabilities = new CopyOnWriteArrayList<>();

    MedicalAgentScopeTools(String agentId, String sessionId, Map<String, Object> context, SkillRegistry registry,
                           PermissionService permissions) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.context = context;
        this.registry = registry;
        this.permissions = permissions;
    }

    @Tool(name="analyze_symptoms", description="Analyze reported symptoms without making a diagnosis")
    public SkillResult analyze(@ToolParam(name="query", description="Symptoms to analyze") String query) { return invoke("analyze_symptoms", query); }
    @Tool(name="assess_risk", description="Assess urgency and emergency warning signs")
    public SkillResult risk(@ToolParam(name="query", description="Symptoms and context") String query) { return invoke("assess_risk", query); }
    @Tool(name="search_knowledge", description="Search reviewed medical knowledge")
    public SkillResult search(@ToolParam(name="query", description="Medical knowledge query") String query) { return invoke("search_knowledge", query); }
    @Tool(name="clinical_guideline", description="Retrieve reviewed clinical guideline evidence")
    public SkillResult guideline(@ToolParam(name="query", description="Guideline question") String query) { return invoke("clinical_guideline", query); }
    @Tool(name="recommend_lifestyle", description="Provide low-risk lifestyle guidance")
    public SkillResult lifestyle(@ToolParam(name="query", description="Lifestyle question") String query) { return invoke("recommend_lifestyle", query); }
    @Tool(name="disease_code", description="Look up a disease classification code")
    public SkillResult code(@ToolParam(name="query", description="Disease name") String query) { return invoke("disease_code", query); }
    @Tool(name="deep_research", description="Research medical evidence and uncertainty")
    public SkillResult research(@ToolParam(name="query", description="Research question") String query) { return invoke("deep_research", query); }

    private SkillResult invoke(String capability, String query) {
        Object principal = context.get("security.principal");
        String runId = String.valueOf(context.getOrDefault("runId", sessionId));
        if (!(principal instanceof AppPrincipal user)
                || !permissions.canExecute(user, agentId, capability, runId).allowed()) {
            throw new SecurityException("AGENT_CAPABILITY_GRANT_MISSING");
        }
        if (query == null || query.isBlank() || query.length() > 4000) throw new IllegalArgumentException("query must be 1..4000 characters");
        invokedCapabilities.add(capability);
        try {
            return registry.invoke(capability, new SkillRequest(query, sessionId, context));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("LLM_TOOL_EXECUTION_FAILED");
        }
    }

    List<String> invokedCapabilities() {
        return List.copyOf(invokedCapabilities);
    }

}
