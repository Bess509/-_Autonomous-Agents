package com.medix.security;

import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    public record Account(AppPrincipal principal, String passwordHash, boolean enabled) {}
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    @Value("${MEDIX_BOOTSTRAP_ADMIN_USERNAME:admin}") private String adminUsername;
    @Value("${MEDIX_BOOTSTRAP_ADMIN_PASSWORD:admin-change-me}") private String adminPassword;
    @Value("${MEDIX_BOOTSTRAP_USER_PASSWORD:user-change-me}") private String userPassword;
    @Value("${medix.security.production:false}") private boolean production;

    public IdentityService(PasswordEncoder encoder, JdbcTemplate jdbc) {
        this.encoder = encoder;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initialize() {
        if (production && "admin-change-me".equals(adminPassword)) {
            throw new IllegalStateException("MEDIX_BOOTSTRAP_ADMIN_PASSWORD must be set in production");
        }
        addIfMissing(adminUsername, adminPassword, "系统管理员", Set.of("ADMIN", "USER"));
        addIfMissing("demo", userPassword, "演示用户", Set.of("USER"));
        seedAgentGrants();
    }

    private void addIfMissing(String username, String password, String name, Set<String> roles) {
        UUID id = UUID.nameUUIDFromBytes(username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO app_users(id, username, password_hash, display_name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE') ON CONFLICT (username) DO NOTHING
                """, id, username, encoder.encode(password), name);
        roles.forEach(role -> jdbc.update("""
                INSERT INTO user_roles(user_id, role) VALUES (?, ?)
                ON CONFLICT (user_id, role) DO NOTHING
                """, id, role));
    }

    private void seedAgentGrants() {
        find(adminUsername).ifPresent(admin -> List.of("consultation_agent", "diagnostic_agent", "research_agent")
                .forEach(agent -> grantAgent(admin.id(), agent, admin.id())));
        find("demo").ifPresent(demo -> grantAgent(demo.id(), "consultation_agent", find(adminUsername).map(AppPrincipal::id).orElse(null)));
    }

    private void grantAgent(UUID userId, String agentId, UUID grantedBy) {
        jdbc.update("""
                INSERT INTO user_agent_grants(user_id, agent_id, action, granted_by)
                VALUES (?, ?, 'USE', ?) ON CONFLICT (user_id, agent_id, action) DO NOTHING
                """, userId, agentId, grantedBy);
    }

    public Optional<AppPrincipal> authenticate(String username, String password) {
        Account account = account(username).orElse(null);
        return account != null && account.enabled() && encoder.matches(password == null ? "" : password, account.passwordHash())
                ? Optional.of(account.principal())
                : Optional.empty();
    }

    @Transactional
    public AppPrincipal register(String requestedUsername, String password, String requestedDisplayName) {
        String username = normalizeUsername(requestedUsername);
        String displayName = requestedDisplayName == null ? "" : requestedDisplayName.trim();
        validatePassword(password);
        if (displayName.length() < 2 || displayName.length() > 50) {
            throw new IllegalArgumentException("显示名称需为 2–50 个字符");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO app_users(id, username, password_hash, display_name, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, id, username, encoder.encode(password), displayName);
        } catch (DuplicateKeyException duplicate) {
            throw new UsernameTakenException();
        }
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'USER')", id);
        grantAgent(id, "consultation_agent", null);
        return find(username).orElseThrow(() -> new IllegalStateException("REGISTERED_USER_NOT_FOUND"));
    }

    @Transactional
    public boolean changePassword(AppPrincipal principal, String currentPassword, String newPassword) {
        if (principal == null) return false;
        validatePassword(newPassword);
        Account account = account(principal.username()).orElse(null);
        if (account == null || !account.enabled() || !encoder.matches(currentPassword == null ? "" : currentPassword,
                account.passwordHash())) return false;
        if (encoder.matches(newPassword, account.passwordHash())) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        return jdbc.update("UPDATE app_users SET password_hash = ?, updated_at = now() WHERE id = ?",
                encoder.encode(newPassword), principal.id()) == 1;
    }

    private String normalizeUsername(String value) {
        String username = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!username.matches("[a-z0-9_]{3,40}")) {
            throw new IllegalArgumentException("账号需为 3–40 位小写字母、数字或下划线");
        }
        return username;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new IllegalArgumentException("密码需为 8–72 位，并同时包含字母和数字");
        }
    }

    public static class UsernameTakenException extends RuntimeException {}

    public Optional<AppPrincipal> find(String username) {
        return account(username).filter(Account::enabled).map(Account::principal);
    }

    public List<AppPrincipal> users() {
        return jdbc.query("SELECT id, username, display_name, status FROM app_users ORDER BY username",
                (rs, row) -> principal(rs));
    }

    private Optional<Account> account(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return jdbc.query("SELECT id, username, password_hash, display_name, status FROM app_users WHERE username = ?",
                (rs, row) -> new Account(principal(rs), rs.getString("password_hash"), "ACTIVE".equals(rs.getString("status"))), username)
                .stream().findFirst();
    }

    private AppPrincipal principal(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Set<String> roles = Set.copyOf(jdbc.queryForList("SELECT role FROM user_roles WHERE user_id = ?", String.class, id));
        return new AppPrincipal(id, rs.getString("username"), rs.getString("display_name"), roles);
    }
}
