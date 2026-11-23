package com.mediflow.agent.api;

import com.mediflow.agent.config.AdminSecurityConfiguration.AdminAccount;
import com.mediflow.agent.config.AgentPromptSettings;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/agent/prompt-templates")
public class AdminPromptTemplateController {
    private final AgentPromptSettings prompts;

    public AdminPromptTemplateController(AgentPromptSettings prompts) { this.prompts = prompts; }

    @GetMapping
    public AgentPromptSettings.View view() { return prompts.view(); }

    @PostMapping("/versions")
    public AgentPromptSettings.View save(@AuthenticationPrincipal AdminAccount admin, @Valid @RequestBody Draft draft) {
        prompts.save(draft.triage(), draft.selection(), draft.note(), Long.parseLong(admin.adminId()));
        return prompts.view();
    }

    @PostMapping("/versions/{id}/trial")
    public AgentPromptSettings.View trial(@AuthenticationPrincipal AdminAccount admin, @PathVariable long id) {
        prompts.trial(id, Long.parseLong(admin.adminId()));
        return prompts.view();
    }

    @PostMapping("/activate")
    public AgentPromptSettings.View activate(@AuthenticationPrincipal AdminAccount admin, @Valid @RequestBody Activation input) {
        prompts.activate(input.id(), input.expectedRevision(), Long.parseLong(admin.adminId()));
        return prompts.view();
    }

    public record Draft(@NotNull @Size(max = 400) String triage, @NotNull @Size(max = 400) String selection,
                        @NotNull @Size(max = 160) String note) {}
    public record Activation(@Positive Long id, @NotNull @Min(0) Long expectedRevision) {}
}
