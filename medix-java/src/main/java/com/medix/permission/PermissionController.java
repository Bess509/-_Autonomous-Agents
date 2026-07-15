package com.medix.permission;

import com.medix.security.AppPrincipal;
import com.medix.security.IdentityService;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class PermissionController {
    private final PermissionService permissions;
    private final IdentityService identities;
    public record McpRegistration(String name, String transport, String endpoint) {}
    public PermissionController(PermissionService permissions, IdentityService identities){this.permissions=permissions;this.identities=identities;}
    @GetMapping("/api/v1/me/agents") public Object agents(Authentication a){AppPrincipal p=(AppPrincipal)a.getPrincipal();return permissions.agents(p).stream().sorted().map(id->Map.of("id",id,"capabilities",permissions.matrix().getOrDefault(id,Set.of()))).toList();}
    @GetMapping("/api/v1/admin/permissions/matrix") public Object matrix(){return permissions.matrix();}
    @GetMapping("/api/v1/admin/capabilities") public Object capabilities(){return permissions.capabilities();}
    @GetMapping("/api/v1/admin/users") public Object users(){
        Map<UUID,Set<String>> grants=permissions.userAgentMatrix();
        return identities.users().stream().map(user->Map.of("id",user.id(),"username",user.username(),
                "displayName",user.displayName(),"roles",user.roles(),"agents",grants.getOrDefault(user.id(),Set.of()))).toList();
    }
    @GetMapping("/api/v1/admin/audit") public Object audit(){return permissions.audits();}
    @PutMapping("/api/v1/admin/users/{userId}/agents/{agentId}") public void grantUser(@PathVariable UUID userId,@PathVariable String agentId,Authentication a){permissions.grantAgent((AppPrincipal)a.getPrincipal(),userId,agentId,true);}
    @DeleteMapping("/api/v1/admin/users/{userId}/agents/{agentId}") public void revokeUser(@PathVariable UUID userId,@PathVariable String agentId,Authentication a){permissions.grantAgent((AppPrincipal)a.getPrincipal(),userId,agentId,false);}
    @PutMapping("/api/v1/admin/agents/{agentId}/capabilities/{capabilityId}") public void grantCap(@PathVariable String agentId,@PathVariable String capabilityId,Authentication a){permissions.grantCapability((AppPrincipal)a.getPrincipal(),agentId,capabilityId,true);}
    @DeleteMapping("/api/v1/admin/agents/{agentId}/capabilities/{capabilityId}") public void revokeCap(@PathVariable String agentId,@PathVariable String capabilityId,Authentication a){permissions.grantCapability((AppPrincipal)a.getPrincipal(),agentId,capabilityId,false);}
    @GetMapping("/api/v1/admin/mcp-servers") public Object mcp(){return permissions.mcpServers();}
    @PostMapping("/api/v1/admin/mcp-servers") public Object registerMcp(@RequestBody McpRegistration request,Authentication a){
        return permissions.registerMcp((AppPrincipal)a.getPrincipal(),request.name(),request.transport(),request.endpoint());
    }
}
