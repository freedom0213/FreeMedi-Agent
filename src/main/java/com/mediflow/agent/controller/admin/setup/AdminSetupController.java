package com.mediflow.agent.controller.admin.setup;

import com.mediflow.agent.service.admin.catalog.HospitalCatalogImport;
import com.mediflow.agent.security.config.AdminSecurityConfiguration.AdminAccount;
import com.mediflow.agent.service.admin.setup.AdminSetupService;
import com.mediflow.agent.service.admin.setup.AdminSetupService.Activation;
import com.mediflow.agent.service.admin.setup.AdminSetupService.Checks;
import com.mediflow.agent.service.admin.setup.AdminSetupService.Setup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/setup")
public class AdminSetupController {
    private final AdminSetupService setup;

    public AdminSetupController(AdminSetupService setup) {
        this.setup = setup;
    }

    public record Activate(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 100) String contact,
                           @Min(1) @Max(90) int scheduleDays, @Min(1) @Max(90) int patientDays,
                           @NotNull LocalTime cancellationCutoff, @Min(0) @Max(120) int checkInLead) {}
    public record Import(@NotNull HospitalCatalogImport.Kind kind, @NotBlank @Size(max = 128000) String csv,
                         String token) {}

    @GetMapping
    public Setup read() {
        return setup.read();
    }

    @GetMapping("/checks")
    public Checks previewChecks(@RequestParam @Min(1) @Max(90) int patientDays) {
        return setup.checks(patientDays);
    }

    @PutMapping("/activate")
    public Setup activate(@AuthenticationPrincipal AdminAccount operator, @Valid @RequestBody Activate input) {
        return setup.activate(Long.parseLong(operator.adminId()), new Activation(input.name(), input.contact(),
                input.scheduleDays(), input.patientDays(), input.cancellationCutoff(), input.checkInLead()));
    }

    @PostMapping("/import/preview")
    public HospitalCatalogImport.Preview preview(@Valid @RequestBody Import input) {
        return setup.previewImport(input.kind(), input.csv());
    }

    @PostMapping("/import/commit")
    public int commit(@Valid @RequestBody Import input) {
        return setup.commitImport(input.kind(), input.csv(), input.token());
    }
}
