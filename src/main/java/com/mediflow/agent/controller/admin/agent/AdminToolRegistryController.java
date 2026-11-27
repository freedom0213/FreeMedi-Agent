package com.mediflow.agent.controller.admin.agent;

import com.mediflow.agent.security.config.AdminSecurityConfiguration.AdminAccount;
import com.mediflow.agent.agent.settings.AgentSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/agent/tools")
public class AdminToolRegistryController {
    private final AgentSettings settings;

    public AdminToolRegistryController(AgentSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public List<AgentSettings.ToolView> tools() { return settings.toolView(); }

    @PutMapping("/{name}")
    public List<AgentSettings.ToolView> update(@AuthenticationPrincipal AdminAccount admin, @PathVariable String name,
                                              @Valid @RequestBody ToolInput input) {
        settings.updateTool(name, input.enabled(), input.timeoutMs(), input.expectedRevision(), Long.parseLong(admin.adminId()));
        return settings.toolView();
    }

    public record ToolInput(@NotNull Boolean enabled, @Positive Long timeoutMs, @NotNull @Min(0) Long expectedRevision) {}
}
