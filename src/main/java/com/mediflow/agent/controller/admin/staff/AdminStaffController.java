package com.mediflow.agent.controller.admin.staff;

import com.mediflow.agent.security.account.AccountCredentials;
import com.mediflow.agent.security.account.AdminAccess.Grant;
import com.mediflow.agent.security.account.AdminAccess.Role;
import com.mediflow.agent.security.config.AdminSecurityConfiguration.AdminAccount;
import com.mediflow.agent.service.admin.staff.AdminStaffService;
import com.mediflow.agent.service.admin.staff.AdminStaffService.SecurityHistory;
import com.mediflow.agent.service.admin.staff.AdminStaffService.Staff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/staff")
public class AdminStaffController {
    private final AdminStaffService staff;

    public AdminStaffController(AdminStaffService staff) {
        this.staff = staff;
    }

    public record GrantInput(@NotNull Role role, Long departmentId) {}
    public record NewStaff(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 100) String displayName,
                           @NotBlank @Size(min = 10, max = 72) String password,
                           @NotEmpty @Size(max = 200) List<@NotNull @Valid GrantInput> grants) {}
    public record Changes(@NotNull @Size(max = 200) List<@NotNull @Valid GrantInput> grants) {}
    public record StateChange(@NotNull Boolean active) {}

    @GetMapping
    public List<Staff> list() {
        return staff.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void create(@AuthenticationPrincipal AdminAccount operator, @Valid @RequestBody NewStaff input) {
        staff.create(operator, input.username(), input.displayName(), input.password(), grants(input.grants()));
    }

    @PutMapping("/{id}/grants")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@AuthenticationPrincipal AdminAccount operator, @PathVariable long id, @Valid @RequestBody Changes input) {
        staff.updateGrants(operator, id, grants(input.grants()));
    }

    @PutMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void status(@AuthenticationPrincipal AdminAccount operator, @PathVariable long id, @Valid @RequestBody StateChange input) {
        staff.setActive(operator, id, input.active());
    }

    @PostMapping("/{id}/password-reset")
    public ResponseEntity<AccountCredentials.TemporaryPassword> passwordReset(@AuthenticationPrincipal AdminAccount operator,
                                                                               @PathVariable long id) {
        var result = staff.resetPassword(operator, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/{id}/security-history")
    public SecurityHistory history(@AuthenticationPrincipal AdminAccount operator, @PathVariable long id,
                                   @RequestParam(required = false) @Positive Long before) {
        return staff.history(operator, id, before);
    }

    private static List<Grant> grants(List<GrantInput> inputs) {
        return inputs.stream().map(input -> new Grant(input.role(), input.departmentId())).toList();
    }
}
