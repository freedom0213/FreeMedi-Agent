package com.mediflow.agent.config;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
@Profile("mysql")
public class AccountCredentials {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AccountCredentials(JdbcTemplate jdbc, PasswordEncoder passwords, Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.clock = clock;
    }

    record State(boolean active, long version, Instant temporaryExpiresAt) {}
    private record PasswordRow(State state, String hash) {}
    public record TemporaryPassword(String password, Instant expiresAt) {}
    public enum Action { DISABLED, ENABLED, PASSWORD_RESET, PASSWORD_CHANGED }

    State state(long id, String role) {
        return jdbc.query("""
                SELECT status, credential_version, temporary_password_expires_at
                FROM sys_user WHERE id = ? AND role = ?
                """, (row, index) -> state(row), id, role).stream().findFirst().orElse(null);
    }

    private static State state(java.sql.ResultSet row) throws java.sql.SQLException {
        Timestamp expires = row.getTimestamp("temporary_password_expires_at");
        return new State("ACTIVE".equals(row.getString("status")), row.getLong("credential_version"),
                expires == null ? null : expires.toInstant());
    }

    boolean loginAllowed(boolean active, Timestamp expires) {
        return active && (expires == null || clock.instant().isBefore(expires.toInstant()));
    }

    boolean valid(State state, SessionAccount principal) {
        return state != null && state.active() && state.version() == principal.credentialVersion()
                && (state.temporaryExpiresAt() == null || clock.instant().isBefore(state.temporaryExpiresAt()));
    }

    @Transactional
    public void changePassword(SessionAccount principal, String role, String currentPassword, String newPassword) {
        validateNewPassword(newPassword);
        var row = jdbc.query("""
                SELECT status, credential_version, temporary_password_expires_at, password_hash
                FROM sys_user WHERE id = ? AND role = ? FOR UPDATE
                """, (record, index) -> new PasswordRow(state(record), record.getString("password_hash")),
                principal.accountId(), role).stream().findFirst().orElse(null);
        if (row == null || !valid(row.state(), principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号会话已失效，请重新登录。");
        }
        if (utf8Length(currentPassword) > 72 || !passwords.matches(currentPassword, row.hash())) {
            throw new IllegalArgumentException("当前密码不正确。");
        }
        if (currentPassword.equals(newPassword)) throw new IllegalArgumentException("新密码须和当前密码不同。");
        setPassword(principal.accountId(), newPassword, null);
        record(principal.accountId(), principal.accountId(), Action.PASSWORD_CHANGED);
    }

    @Transactional
    public TemporaryPassword resetStaffPassword(long id, long operator) {
        byte[] entropy = new byte[24];
        random.nextBytes(entropy);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Instant expires = clock.instant().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        setPassword(id, password, Timestamp.from(expires));
        record(id, operator, Action.PASSWORD_RESET);
        return new TemporaryPassword(password, expires);
    }

    private void setPassword(long id, String password, Timestamp expires) {
        jdbc.update("""
                UPDATE sys_user SET password_hash = ?, temporary_password_expires_at = ?,
                    credential_version = credential_version + 1 WHERE id = ?
                """, passwords.encode(password), expires, id);
    }

    public void record(long account, long actor, Action action) {
        jdbc.update("INSERT INTO account_security_event(account_id, actor_id, action) VALUES (?, ?, ?)",
                account, actor, action.name());
    }

    private static void validateNewPassword(String password) {
        if (password.codePointCount(0, password.length()) < 12 || utf8Length(password) > 72
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("新密码至少 12 个字符，最多 72 个 UTF-8 字节，请勿使用控制字符。");
        }
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
