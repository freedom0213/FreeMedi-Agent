package com.mediflow.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.config.AccountCredentials;
import com.mediflow.agent.config.AccountCredentials.Action;
import com.mediflow.agent.config.AdminAccess;
import com.mediflow.agent.config.AdminAccess.Grant;
import com.mediflow.agent.config.AdminAccess.Role;
import com.mediflow.agent.config.AdminSecurityConfiguration.AdminAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/staff")
public class AdminStaffController {
    private final JdbcTemplate jdbc;
    private final AdminAccess access;
    private final PasswordEncoder passwords;
    private final ObjectMapper json;
    private final AccountCredentials credentials;

    public AdminStaffController(JdbcTemplate jdbc, AdminAccess access, PasswordEncoder passwords, ObjectMapper json,
                                AccountCredentials credentials) {
        this.jdbc = jdbc;
        this.access = access;
        this.passwords = passwords;
        this.json = json;
        this.credentials = credentials;
    }

    public record Staff(long id, String username, String displayName, String status, List<Grant> grants,
                        Instant temporaryPasswordExpiresAt) {}
    public record GrantInput(@NotNull Role role, Long departmentId) {}
    public record NewStaff(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 100) String displayName,
                           @NotBlank @Size(min = 10, max = 72) String password,
                           @NotEmpty @Size(max = 200) List<@NotNull @Valid GrantInput> grants) {}
    public record Changes(@NotNull @Size(max = 200) List<@NotNull @Valid GrantInput> grants) {}
    public record StateChange(@NotNull Boolean active) {}
    public record SecurityEvent(long id, String operator, Action action, Instant occurredAt) {}
    public record SecurityHistory(List<SecurityEvent> events, Long nextBefore) {}

    @GetMapping
    public List<Staff> list() {
        return jdbc.query("""
                SELECT id, username, display_name, status, temporary_password_expires_at
                FROM sys_user WHERE role='ADMIN' ORDER BY id
                """, (row, i) -> {
            var expires = row.getTimestamp("temporary_password_expires_at");
            return new Staff(row.getLong("id"), row.getString("username"), row.getString("display_name"),
                    row.getString("status"), access.storedPermissions(row.getLong("id")).grants(),
                    expires == null ? null : expires.toInstant());
        });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public void create(@AuthenticationPrincipal AdminAccount operator, @Valid @RequestBody NewStaff input) {
        if (!input.username().matches("[A-Za-z0-9._-]{1,64}") || input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("请使用有效的登录账号和密码。");
        }
        var grants = validate(operator, input.grants());
        try {
            jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,status) VALUES (?, ?, ?, 'ADMIN', 'ACTIVE')",
                    input.username(), passwords.encode(input.password()), input.displayName().strip());
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "登录账号已存在。");
        }
        long id = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, input.username());
        save(operator, id, List.of(), grants);
    }

    @PutMapping("/{id}/grants")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void update(@AuthenticationPrincipal AdminAccount operator, @PathVariable long id, @Valid @RequestBody Changes input) {
        lockManaged(operator, id);
        var previous = access.storedPermissions(id);
        save(operator, id, previous.grants(), validate(operator, input.grants()));
    }

    @PutMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void status(@AuthenticationPrincipal AdminAccount operator, @PathVariable long id, @Valid @RequestBody StateChange input) {
        String current = lockManaged(operator, id);
        String target = input.active() ? "ACTIVE" : "DISABLED";
        if (target.equals(current)) return;
        jdbc.update("UPDATE sys_user SET status=?, credential_version=credential_version+1 WHERE id=?", target, id);
        credentials.record(id, operator.accountId(), input.active() ? Action.ENABLED : Action.DISABLED);
    }

    @PostMapping("/{id}/password-reset")
    @Transactional
    public ResponseEntity<AccountCredentials.TemporaryPassword> passwordReset(@AuthenticationPrincipal AdminAccount operator,
                                                                               @PathVariable long id) {
        if (!"ACTIVE".equals(lockManaged(operator, id))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先恢复账号，再重置密码。");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(credentials.resetStaffPassword(id, operator.accountId()));
    }

    @GetMapping("/{id}/security-history")
    @Transactional(readOnly = true)
    public SecurityHistory history(@AuthenticationPrincipal AdminAccount operator, @PathVariable long id,
                                   @RequestParam(required = false) @Positive Long before) {
        staffStatus(id, false);
        if (id != operator.accountId()) requireManage(operator, id);
        var events = jdbc.query("""
                SELECT e.id, u.display_name, e.action, e.occurred_at
                FROM account_security_event e JOIN sys_user u ON u.id=e.actor_id
                WHERE e.account_id=? AND (? IS NULL OR e.id<?) ORDER BY e.id DESC LIMIT 21
                """, (row, i) -> new SecurityEvent(row.getLong(1), row.getString(2), Action.valueOf(row.getString(3)),
                row.getTimestamp(4).toInstant()), id, before, before);
        return events.size() == 21 ? new SecurityHistory(events.subList(0, 20), events.get(19).id())
                : new SecurityHistory(events, null);
    }

    private String staffStatus(long id, boolean lock) {
        return jdbc.queryForList("SELECT status FROM sys_user WHERE id = ? AND role='ADMIN'" + (lock ? " FOR UPDATE" : ""),
                        String.class, id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String lockManaged(AdminAccount operator, long id) {
        String status = staffStatus(id, true);
        requireManage(operator, id);
        return status;
    }

    private void requireManage(AdminAccount operator, long id) {
        var target = access.storedPermissions(id);
        if (id == operator.accountId() || target.superuser()
                || (target.has(Role.SYSTEM_ADMIN, Role.VISIT_SUPERVISOR) && !access.permissions(operator).superuser())) {
            throw new AccessDeniedException("当前账号没有管理此账号的权限。");
        }
    }

    private List<Grant> validate(AdminAccount operator, List<GrantInput> inputs) {
        boolean superuser = access.permissions(operator).superuser();
        return inputs.stream().map(input -> {
            if (input.role() == Role.SUPER_ADMIN
                    || ((input.role() == Role.SYSTEM_ADMIN || input.role() == Role.VISIT_SUPERVISOR) && !superuser)) {
                throw new AccessDeniedException("无法授予该岗位。");
            }
            boolean scoped = input.role() == Role.SCHEDULER || input.role() == Role.TRIAGE || input.role() == Role.VISIT_SUPERVISOR;
            if (scoped != (input.departmentId() != null)) throw new IllegalArgumentException("请为业务岗位指定科室。");
            if (scoped && jdbc.queryForList("SELECT id FROM department WHERE id = ?", Long.class, input.departmentId()).isEmpty()) {
                throw new IllegalArgumentException("授权科室不存在。");
            }
            return new Grant(input.role(), input.departmentId());
        }).distinct().toList();
    }

    private void save(AdminAccount operator, long id, List<Grant> before, List<Grant> after) {
        jdbc.update("DELETE FROM admin_grant WHERE admin_id=?", id);
        for (var grant : after) {
            jdbc.update("INSERT INTO admin_grant(admin_id,role_code,department_id) VALUES (?,?,?)",
                    id, grant.role().name(), grant.departmentId());
        }
        jdbc.update("INSERT INTO admin_grant_event(operator_id,staff_id,grants_before,grants_after) VALUES (?,?,?,?)",
                Long.parseLong(operator.adminId()), id, json.valueToTree(before).toString(), json.valueToTree(after).toString());
    }
}
