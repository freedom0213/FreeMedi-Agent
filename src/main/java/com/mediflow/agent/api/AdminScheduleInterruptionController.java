package com.mediflow.agent.api;

import com.mediflow.agent.appointment.ScheduleInterruptionService;
import com.mediflow.agent.config.AdminAccess;
import com.mediflow.agent.config.AdminAccess.Role;
import com.mediflow.agent.config.AdminSecurityConfiguration.AdminAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Profile("mysql")
public class AdminScheduleInterruptionController {
    private final AdminAccess access;
    private final ScheduleInterruptionService stops;

    public AdminScheduleInterruptionController(AdminAccess access, ScheduleInterruptionService stops) {
        this.access = access;
        this.stops = stops;
    }

    @GetMapping("/api/v1/admin/catalog/schedules/{id}/interruption/preview")
    public ScheduleInterruptionService.Preview preview(@AuthenticationPrincipal AdminAccount admin, @PathVariable long id) {
        return stops.preview(id, access.permissions(admin).scope(Role.SCHEDULER));
    }

    @GetMapping("/api/v1/admin/catalog/schedules/{id}/interruption")
    public ScheduleInterruptionService.Result result(@AuthenticationPrincipal AdminAccount admin, @PathVariable long id) {
        return stops.result(id, access.permissions(admin).scope(Role.SCHEDULER));
    }

    @PostMapping("/api/v1/admin/catalog/schedules/{id}/interruption")
    public ScheduleInterruptionService.Result stop(@AuthenticationPrincipal AdminAccount admin, @PathVariable long id,
                                                   @Valid @RequestBody Confirm input) {
        return stops.stop(id, access.permissions(admin).scope(Role.SCHEDULER), Long.parseLong(admin.adminId()),
                input.requestId(), input.token(), input.reason());
    }

    @PutMapping("/api/v1/admin/appointments/{number}/arrangement")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void arrange(@AuthenticationPrincipal AdminAccount admin, @PathVariable String number, @Valid @RequestBody Arrange input) {
        stops.arrange(number, access.permissions(admin).scope(Role.SCHEDULER), Long.parseLong(admin.adminId()),
                input.outcome(), input.note());
    }

    public record Confirm(@NotNull UUID requestId, @NotNull @Pattern(regexp="[a-f0-9]{64}") String token,
                          @NotBlank @Size(max=240) String reason) {}
    public record Arrange(@NotNull ScheduleInterruptionService.Outcome outcome, @NotBlank @Size(max=400) String note) {}
}
