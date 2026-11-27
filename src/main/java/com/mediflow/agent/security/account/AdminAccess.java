package com.mediflow.agent.security.account;

import com.mediflow.agent.security.config.AdminSecurityConfiguration.AdminAccount;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Profile("mysql")
public class AdminAccess {
    public enum Role { SUPER_ADMIN, SYSTEM_ADMIN, SCHEDULER, TRIAGE, KNOWLEDGE, VISIT_SUPERVISOR }

    public record Grant(Role role, Long departmentId) {}

    public record Scope(boolean all, Set<Long> departments) {
        public boolean allows(long department) { return all || departments.contains(department); }

        public void require(long department) {
            if (!allows(department)) throw new AccessDeniedException("超出负责科室范围。");
        }

        public String predicate(String column, List<Object> args) {
            if (all) return "1=1";
            if (departments.isEmpty()) return "1=0";
            args.addAll(departments);
            return column + " IN (" + departments.stream().map(ignored -> "?").collect(Collectors.joining(",")) + ")";
        }
    }

    public record Permissions(List<Grant> grants) {
        public boolean superuser() { return grants.stream().anyMatch(g -> g.role() == Role.SUPER_ADMIN); }

        public boolean has(Role... roles) {
            return superuser() || grants.stream().anyMatch(g -> Arrays.asList(roles).contains(g.role()));
        }

        public Scope scope(Role... roles) {
            return new Scope(superuser(), grants.stream().filter(g -> g.departmentId() != null
                    && Arrays.asList(roles).contains(g.role())).map(Grant::departmentId).collect(Collectors.toUnmodifiableSet()));
        }
    }

    private final JdbcTemplate jdbc;

    public AdminAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Permissions permissions(AdminAccount account) { return permissions(Long.parseLong(account.adminId())); }

    public Permissions permissions(long id) {
        return grants(id, true);
    }

    public Permissions storedPermissions(long id) {
        return grants(id, false);
    }

    private Permissions grants(long id, boolean activeOnly) {
        return new Permissions(jdbc.query("""
                SELECT g.role_code, g.department_id FROM admin_grant g JOIN sys_user u ON u.id = g.admin_id
                WHERE u.id = ? AND u.role = 'ADMIN' AND (? = FALSE OR u.status = 'ACTIVE')
                ORDER BY g.role_code, g.department_id
                """, (row, i) -> new Grant(Role.valueOf(row.getString(1)), row.getObject(2, Long.class)), id, activeOnly));
    }

    public AuthorizationManager<RequestAuthorizationContext> allow(Role... roles) {
        return (authentication, context) -> new AuthorizationDecision(authentication.get().getPrincipal() instanceof AdminAccount account
                && permissions(account).has(roles));
    }
}
