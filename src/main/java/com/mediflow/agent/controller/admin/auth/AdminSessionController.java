package com.mediflow.agent.controller.admin.auth;

import com.mediflow.agent.security.config.AdminSecurityConfiguration.AdminAccount;
import com.mediflow.agent.security.account.AdminAccess;
import com.mediflow.agent.service.appointment.policy.HospitalRules;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/auth")
@Profile("mysql")
public class AdminSessionController {
    private final AdminAccess access;
    private final HospitalRules rules;

    public AdminSessionController(AdminAccess access, HospitalRules rules) {
        this.access = access;
        this.rules = rules;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token, @AuthenticationPrincipal AdminAccount account) {
        return new CsrfResponse(token.getHeaderName(), token.getToken(),
                account == null ? null : account.adminId());
    }

    @GetMapping("/session")
    public AdminSession session(@AuthenticationPrincipal AdminAccount account) {
        if (account.mustChangePassword()) {
            return new AdminSession(account.adminId(), account.displayName(), List.of(), null, true);
        }
        var permissions = access.permissions(account);
        return new AdminSession(account.adminId(), account.displayName(), permissions.grants(),
                permissions.has(AdminAccess.Role.SCHEDULER) ? rules.active() : null, false);
    }

    public record AdminSession(String adminId, String displayName, List<AdminAccess.Grant> grants,
                               HospitalRules.Rules scheduleRules, boolean mustChangePassword) {}

    public record CsrfResponse(String headerName, String token, String adminId) {}
}
