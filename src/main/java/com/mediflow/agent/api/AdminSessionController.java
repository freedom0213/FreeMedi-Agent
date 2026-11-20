package com.mediflow.agent.api;

import com.mediflow.agent.config.AdminSecurityConfiguration.AdminAccount;
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
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token, @AuthenticationPrincipal AdminAccount account) {
        return new CsrfResponse(token.getHeaderName(), token.getToken(),
                account == null ? null : account.adminId());
    }

    @GetMapping("/session")
    public AdminSession session(@AuthenticationPrincipal AdminAccount account) {
        return new AdminSession(account.adminId(), account.displayName());
    }

    public record AdminSession(String adminId, String displayName) {}

    public record CsrfResponse(String headerName, String token, String adminId) {}
}
