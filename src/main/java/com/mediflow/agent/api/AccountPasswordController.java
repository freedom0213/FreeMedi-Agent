package com.mediflow.agent.api;

import com.mediflow.agent.config.AccountCredentials;
import com.mediflow.agent.config.SessionAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
public class AccountPasswordController {
    private final AccountCredentials credentials;

    public AccountPasswordController(AccountCredentials credentials) {
        this.credentials = credentials;
    }

    public record Change(@NotBlank @Size(max = 72) String currentPassword, @NotBlank @Size(max = 72) String newPassword) {}

    @PostMapping("/api/v1/admin/auth/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adminPassword(@AuthenticationPrincipal SessionAccount principal, @Valid @RequestBody Change change) {
        credentials.changePassword(principal, "ADMIN", change.currentPassword(), change.newPassword());
    }

    @PostMapping("/api/v1/auth/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void patientPassword(@AuthenticationPrincipal SessionAccount principal, @Valid @RequestBody Change change) {
        credentials.changePassword(principal, "PATIENT", change.currentPassword(), change.newPassword());
    }
}
