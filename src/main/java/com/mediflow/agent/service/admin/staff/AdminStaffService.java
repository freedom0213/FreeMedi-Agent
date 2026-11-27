package com.mediflow.agent.service.admin.staff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.security.account.AccountCredentials;
import com.mediflow.agent.security.account.AccountCredentials.Action;
import com.mediflow.agent.security.account.AdminAccess;
import com.mediflow.agent.security.account.AdminAccess.Grant;
import com.mediflow.agent.security.account.AdminAccess.Role;
import com.mediflow.agent.security.config.AdminSecurityConfiguration.AdminAccount;
import com.mediflow.agent.mapper.admin.staff.AdminStaffMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Service
@Profile("mysql")
public class AdminStaffService {
    private final AdminStaffMapper mapper;
    private final AdminAccess access;
    private final PasswordEncoder passwords;
    private final ObjectMapper json;
    private final AccountCredentials credentials;

    public AdminStaffService(AdminStaffMapper mapper, AdminAccess access, PasswordEncoder passwords, ObjectMapper json,
                                AccountCredentials credentials) {
        this.mapper = mapper;
        this.access = access;
        this.passwords = passwords;
        this.json = json;
        this.credentials = credentials;
    }

    public record Staff(long id, String username, String displayName, String status, List<Grant> grants,
                        Instant temporaryPasswordExpiresAt) {}
    public record SecurityEvent(long id, String operator, Action action, Instant occurredAt) {}
    public record SecurityHistory(List<SecurityEvent> events, Long nextBefore) {}

    public List<Staff> list() {
        return mapper.list().stream()
                .map(row -> new Staff(row.id(), row.username(), row.displayName(), row.status(),
                        access.storedPermissions(row.id()).grants(), row.temporaryPasswordExpiresAt()))
                .toList();
    }

    @Transactional
    public void create(AdminAccount operator, String username, String displayName, String password, List<Grant> requestedGrants) {
        if (!username.matches("[A-Za-z0-9._-]{1,64}") || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("请使用有效的登录账号和密码。");
        }
        var grants = validate(operator, requestedGrants);
        try {
            mapper.insert(username, displayName.strip(), passwords.encode(password));
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "登录账号已存在。");
        }
        save(operator, mapper.accountId(username), List.of(), grants);
    }

    @Transactional
    public void updateGrants(AdminAccount operator, long id, List<Grant> requestedGrants) {
        lockManaged(operator, id);
        var previous = access.storedPermissions(id);
        save(operator, id, previous.grants(), validate(operator, requestedGrants));
    }

    @Transactional
    public void setActive(AdminAccount operator, long id, boolean active) {
        String current = lockManaged(operator, id);
        String target = active ? "ACTIVE" : "DISABLED";
        if (target.equals(current)) return;
        mapper.updateStatus(id, target);
        credentials.record(id, operator.accountId(), active ? Action.ENABLED : Action.DISABLED);
    }

    @Transactional
    public AccountCredentials.TemporaryPassword resetPassword(AdminAccount operator, long id) {
        if (!"ACTIVE".equals(lockManaged(operator, id))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先恢复账号，再重置密码。");
        }
        return credentials.resetStaffPassword(id, operator.accountId());
    }

    @Transactional(readOnly = true)
    public SecurityHistory history(AdminAccount operator, long id, Long before) {
        staffStatus(id, false);
        if (id != operator.accountId()) requireManage(operator, id);
        var events = mapper.securityEvents(id, before).stream()
                .map(row -> new SecurityEvent(row.id(), row.operator(), Action.valueOf(row.action()), row.occurredAt()))
                .toList();
        return events.size() == 21 ? new SecurityHistory(events.subList(0, 20), events.get(19).id())
                : new SecurityHistory(events, null);
    }

    private String staffStatus(long id, boolean lock) {
        return (lock ? mapper.lockStatus(id) : mapper.findStatus(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
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

    private List<Grant> validate(AdminAccount operator, List<Grant> inputs) {
        boolean superuser = access.permissions(operator).superuser();
        return inputs.stream().map(input -> {
            if (input.role() == Role.SUPER_ADMIN
                    || ((input.role() == Role.SYSTEM_ADMIN || input.role() == Role.VISIT_SUPERVISOR) && !superuser)) {
                throw new AccessDeniedException("无法授予该岗位。");
            }
            boolean scoped = input.role() == Role.SCHEDULER || input.role() == Role.TRIAGE || input.role() == Role.VISIT_SUPERVISOR;
            if (scoped != (input.departmentId() != null)) throw new IllegalArgumentException("请为业务岗位指定科室。");
            if (scoped && !mapper.departmentExists(input.departmentId())) {
                throw new IllegalArgumentException("授权科室不存在。");
            }
            return input;
        }).distinct().toList();
    }

    private void save(AdminAccount operator, long id, List<Grant> before, List<Grant> after) {
        mapper.deleteGrants(id);
        for (var grant : after) {
            mapper.insertGrant(id, grant.role().name(), grant.departmentId());
        }
        mapper.recordGrantChange(Long.parseLong(operator.adminId()), id, json.valueToTree(before).toString(),
                json.valueToTree(after).toString());
    }
}
