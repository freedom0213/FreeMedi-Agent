package com.mediflow.agent.api;

import com.mediflow.agent.config.PatientAccount;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("mysql")
public class PatientSessionController {
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token, @AuthenticationPrincipal PatientAccount account) {
        return new CsrfResponse(token.getHeaderName(), token.getToken(),
                account == null ? null : Long.toString(account.getPatient().userId()));
    }

    @GetMapping("/session")
    public PatientSession session(@AuthenticationPrincipal PatientAccount account) {
        return new PatientSession(Long.toString(account.getPatient().userId()), account.getDisplayName());
    }

    public record PatientSession(String patientId, String displayName) { }

    public record CsrfResponse(String headerName, String token, String patientId) { }
}
