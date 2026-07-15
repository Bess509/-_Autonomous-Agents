package com.medix.security;

import java.util.Set;
import java.util.UUID;

public record AppPrincipal(UUID id, String username, String displayName, Set<String> roles) {
    public boolean admin() { return roles.contains("ADMIN"); }
}
