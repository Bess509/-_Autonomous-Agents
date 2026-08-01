package com.medix.permission;

import com.medix.security.AppPrincipal;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    public record Decision(boolean allowed, String reasonCode, String userId, String agentId, String capabilityId) {}
    public record Audit(Instant at, String actor, String action, String resource, String decision, String reason, String runId) {}
    public record McpServer(UUID id, String name, String transport, String endpoint, boolean enabled) {}

    private static final Map<String, Set<String>> HARNESS = Map.of(
            "consultation_agent", Set.of("search_knowledge", "recommend_lifestyle", "assess_risk", "safe_medical_guidance"),
            "diagnostic_agent", Set.of("assess_risk", "analyze_symptoms", "disease_code", "safe_medical_guidance"),
            "research_agent", Set.of("clinical_guideline", "deep_research")
    );

    private final JdbcTemplate jdbc;

    public PermissionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Decision canUseAgent(AppPrincipal user, String agent, String runId) {
        boolean known = count("SELECT count(*) FROM agent_definitions WHERE id = ? AND enabled", agent) == 1;
        boolean allowed = known && count("""
                SELECT count(*) FROM user_agent_grants
                WHERE user_id = ? AND agent_id = ? AND action = 'USE'
                """, user.id(), agent) == 1;
        String reason = !known ? "UNKNOWN_AGENT" : allowed ? "EXPLICIT_GRANT" : "USER_AGENT_GRANT_MISSING";
        if (!allowed) audit(user, "USE_AGENT", "AGENT", agent, "DENY", reason, runId);
        return new Decision(allowed, reason, user.id().toString(), agent, null);
    }

    public Decision canExecute(AppPrincipal user, String agent, String capability, String runId) {
        Decision agentDecision = canUseAgent(user, agent, runId);
        if (!agentDecision.allowed()) {
            return new Decision(false, agentDecision.reasonCode(), user.id().toString(), agent, capability);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT type, enabled FROM capabilities WHERE id = ?", capability);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled"))) {
            audit(user, "EXECUTE_CAPABILITY", "CAPABILITY", capability, "DENY", "UNKNOWN_OR_DISABLED_CAPABILITY", runId);
            return new Decision(false, "UNKNOWN_OR_DISABLED_CAPABILITY", user.id().toString(), agent, capability);
        }
        String type = String.valueOf(rows.getFirst().get("type"));
        if ("SKILL".equals(type) && !HARNESS.getOrDefault(agent, Set.of()).contains(capability)) {
            audit(user, "EXECUTE_CAPABILITY", "CAPABILITY", capability, "DENY", "HARNESS_BOUNDARY", runId);
            return new Decision(false, "HARNESS_BOUNDARY", user.id().toString(), agent, capability);
        }
        String action = "MCP_RESOURCE".equals(type) ? "READ" : "EXECUTE";
        boolean allowed = count("""
                SELECT count(*) FROM agent_capability_grants
                WHERE agent_id = ? AND capability_id = ? AND action = ?
                """, agent, capability, action) == 1;
        String reason = allowed ? "EXPLICIT_GRANT" : "AGENT_CAPABILITY_GRANT_MISSING";
        if (!allowed) audit(user, action + "_CAPABILITY", "CAPABILITY", capability, "DENY", reason, runId);
        return new Decision(allowed, reason, user.id().toString(), agent, capability);
    }

    public Set<String> agents(AppPrincipal user) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT g.agent_id FROM user_agent_grants g
                JOIN agent_definitions a ON a.id = g.agent_id AND a.enabled
                WHERE g.user_id = ? AND g.action = 'USE'
                """, String.class, user.id()));
    }

    public Map<String, Set<String>> matrix() {
        Map<String, Set<String>> result = new TreeMap<>();
        jdbc.queryForList("SELECT id FROM agent_definitions WHERE enabled ORDER BY id", String.class)
                .forEach(agent -> {
                    List<Map<String, Object>> grants = jdbc.queryForList("""
                            SELECT c.id, c.type
                            FROM agent_capability_grants g
                            JOIN capabilities c ON c.id = g.capability_id AND c.enabled
                            WHERE g.agent_id = ?
                              AND ((c.type = 'MCP_RESOURCE' AND g.action = 'READ')
                                   OR (c.type <> 'MCP_RESOURCE' AND g.action = 'EXECUTE'))
                            ORDER BY c.id
                            """, agent);
                    Set<String> effective = grants.stream()
                            .filter(grant -> !"SKILL".equals(String.valueOf(grant.get("type")))
                                    || HARNESS.getOrDefault(agent, Set.of()).contains(String.valueOf(grant.get("id"))))
                            .map(grant -> String.valueOf(grant.get("id")))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    result.put(agent, effective);
                });
        return result;
    }

    public Map<UUID, Set<String>> userAgentMatrix() {
        Map<UUID, Set<String>> result = new LinkedHashMap<>();
        jdbc.queryForList("SELECT id FROM app_users ORDER BY username", UUID.class)
                .forEach(userId -> result.put(userId, Set.copyOf(jdbc.queryForList("""
                        SELECT g.agent_id FROM user_agent_grants g
                        JOIN agent_definitions a ON a.id = g.agent_id AND a.enabled
                        WHERE g.user_id = ? AND g.action = 'USE' ORDER BY g.agent_id
                        """, String.class, userId))));
        return result;
    }

    public List<Map<String, Object>> capabilities() {
        return jdbc.queryForList("SELECT id, type, display_name, enabled FROM capabilities ORDER BY type, id");
    }

    public List<Audit> audits() {
        return jdbc.query("""
                SELECT l.created_at, COALESCE(u.username, 'system') actor, l.action,
                       CONCAT(COALESCE(l.resource_type,''), ':', COALESCE(l.resource_id,'')) resource,
                       l.decision, l.reason, l.run_id
                FROM permission_audit_logs l LEFT JOIN app_users u ON u.id = l.actor_user_id
                ORDER BY l.created_at DESC LIMIT 200
                """, (rs, row) -> new Audit(rs.getTimestamp("created_at").toInstant(), rs.getString("actor"),
                rs.getString("action"), rs.getString("resource"), rs.getString("decision"),
                rs.getString("reason"), rs.getString("run_id")));
    }

    public void grantAgent(AppPrincipal actor, UUID userId, String agent, boolean grant) {
        if (count("SELECT count(*) FROM agent_definitions WHERE id = ? AND enabled", agent) != 1) {
            throw new IllegalArgumentException("Unknown or disabled agent");
        }
        if (grant) {
            jdbc.update("""
                    INSERT INTO user_agent_grants(user_id, agent_id, action, granted_by)
                    VALUES (?, ?, 'USE', ?) ON CONFLICT (user_id, agent_id, action) DO NOTHING
                    """, userId, agent, actor.id());
        } else {
            jdbc.update("DELETE FROM user_agent_grants WHERE user_id = ? AND agent_id = ? AND action = 'USE'", userId, agent);
        }
        audit(actor, grant ? "GRANT_AGENT" : "REVOKE_AGENT", "USER_AGENT", userId + "/" + agent,
                "ALLOW", "ADMIN_CHANGE", null);
    }

    public void grantCapability(AppPrincipal actor, String agent, String capability, boolean grant) {
        if (count("SELECT count(*) FROM agent_definitions WHERE id = ? AND enabled", agent) != 1) {
            throw new IllegalArgumentException("Unknown or disabled agent");
        }
        List<String> types = jdbc.queryForList("SELECT type FROM capabilities WHERE id = ? AND enabled", String.class, capability);
        if (types.isEmpty()) throw new IllegalArgumentException("Unknown or disabled capability");
        String type = types.getFirst();
        if ("SKILL".equals(type) && !HARNESS.getOrDefault(agent, Set.of()).contains(capability)) {
            throw new IllegalArgumentException("Harness boundary forbids capability");
        }
        String action = "MCP_RESOURCE".equals(type) ? "READ" : "EXECUTE";
        if (grant) {
            jdbc.update("""
                    INSERT INTO agent_capability_grants(agent_id, capability_id, action, granted_by)
                    VALUES (?, ?, ?, ?) ON CONFLICT (agent_id, capability_id, action) DO NOTHING
                    """, agent, capability, action, actor.id());
        } else {
            jdbc.update("DELETE FROM agent_capability_grants WHERE agent_id = ? AND capability_id = ? AND action = ?",
                    agent, capability, action);
        }
        audit(actor, grant ? "GRANT_CAPABILITY" : "REVOKE_CAPABILITY", "AGENT_CAPABILITY", agent + "/" + capability,
                "ALLOW", "ADMIN_CHANGE", null);
    }

    public List<McpServer> mcpServers() {
        return jdbc.query("SELECT id, name, transport, endpoint, enabled FROM mcp_servers ORDER BY name",
                (rs, row) -> new McpServer(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("transport"), rs.getString("endpoint"), rs.getBoolean("enabled")));
    }

    public McpServer registerMcp(AppPrincipal actor, String name, String transport, String endpoint) {
        validateMcpEndpoint(endpoint);
        if (!Set.of("SSE", "STREAMABLE_HTTP").contains(transport)) {
            throw new IllegalArgumentException("Unsupported MCP transport");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO mcp_servers(id, name, transport, endpoint, enabled) VALUES (?, ?, ?, ?, false)",
                id, name, transport, endpoint);
        audit(actor, "REGISTER_MCP", "MCP_SERVER", id.toString(), "ALLOW", "ADMIN_CHANGE", null);
        return new McpServer(id, name, transport, endpoint, false);
    }

    private void validateMcpEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("MCP endpoint must use HTTPS");
            }
            InetAddress address = InetAddress.getByName(uri.getHost());
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("Private or local MCP endpoints are forbidden");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid MCP endpoint", exception);
        }
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void audit(AppPrincipal actor, String action, String resourceType, String resourceId,
                       String decision, String reason, String runId) {
        jdbc.update("""
                INSERT INTO permission_audit_logs(actor_user_id, action, resource_type, resource_id, decision, reason, run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, actor.id(), action, resourceType, resourceId, decision, reason, runId);
    }
}
