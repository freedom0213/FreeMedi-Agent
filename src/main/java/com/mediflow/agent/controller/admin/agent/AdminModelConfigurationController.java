package com.mediflow.agent.controller.admin.agent;

import com.mediflow.agent.security.config.AdminSecurityConfiguration.AdminAccount;
import com.mediflow.agent.agent.settings.AgentSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/agent/model-config")
public class AdminModelConfigurationController {
    private final AgentSettings settings;

    public AdminModelConfigurationController(AgentSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public AgentSettings.ModelView modelConfiguration() { return settings.modelView(); }

    @PostMapping("/models")
    public AgentSettings.ModelView save(@AuthenticationPrincipal AdminAccount admin,
                                        @Valid @RequestBody ModelInput input) {
        settings.saveModel(input.profileId() == null ? null : input.profileId().toString(),
                input.expectedId(), input.label(), input.baseUrl(), input.modelName(), input.apiKey(),
                Long.parseLong(admin.adminId()));
        return settings.modelView();
    }

    @PostMapping("/models/{id}/test")
    public AgentSettings.ModelView test(@AuthenticationPrincipal AdminAccount admin, @PathVariable long id) {
        settings.testModel(id, Long.parseLong(admin.adminId()));
        return settings.modelView();
    }

    @PostMapping("/activate")
    public AgentSettings.ModelView activate(@AuthenticationPrincipal AdminAccount admin,
                                            @Valid @RequestBody Activation input) {
        settings.activate(input.id(), input.expectedRevision(), Long.parseLong(admin.adminId()));
        return settings.modelView();
    }

    @PutMapping("/policy")
    public AgentSettings.ModelView policy(@AuthenticationPrincipal AdminAccount admin,
                                          @Valid @RequestBody Policy input) {
        settings.updatePolicy(input.modelTimeoutMs(), input.toolTimeoutMs(), input.requestTimeoutMs(),
                input.expectedRevision(), Long.parseLong(admin.adminId()));
        return settings.modelView();
    }

    public record ModelInput(UUID profileId, @NotNull @Min(0) Long expectedId,
                             @NotNull @Size(max = 100) String label, @NotNull @Size(max = 500) String baseUrl,
                             @NotNull @Size(max = 150) String modelName, @Size(max = 512) String apiKey) {}
    public record Activation(@Positive Long id, @NotNull @Min(0) Long expectedRevision) {}
    public record Policy(@NotNull @Positive Long modelTimeoutMs, @NotNull @Positive Long toolTimeoutMs,
                         @NotNull @Positive Long requestTimeoutMs, @NotNull @Min(0) Long expectedRevision) {}
}
