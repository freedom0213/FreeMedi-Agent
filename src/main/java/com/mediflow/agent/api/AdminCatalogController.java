package com.mediflow.agent.api;

import com.mediflow.agent.appointment.AdminCatalogService;
import com.mediflow.agent.appointment.AdminCatalogService.CatalogOption;
import com.mediflow.agent.appointment.AdminCatalogService.CatalogProblem;
import com.mediflow.agent.appointment.AdminCatalogService.Department;
import com.mediflow.agent.appointment.AdminCatalogService.Doctor;
import com.mediflow.agent.appointment.AdminCatalogService.Page;
import com.mediflow.agent.appointment.AdminCatalogService.Period;
import com.mediflow.agent.appointment.AdminCatalogService.Schedule;
import com.mediflow.agent.appointment.AdminCatalogService.ScheduleDetail;
import com.mediflow.agent.config.AdminAccess;
import com.mediflow.agent.config.AdminAccess.Role;
import com.mediflow.agent.config.AdminSecurityConfiguration.AdminAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/catalog")
public class AdminCatalogController {
    private final AdminCatalogService catalog;
    private final AdminAccess access;

    public AdminCatalogController(AdminCatalogService catalog, AdminAccess access) {
        this.catalog = catalog;
        this.access = access;
    }

    @GetMapping("/departments")
    public Page<Department> departments(@RequestParam(required = false) @Size(max = 100) String q,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "1") int page) {
        return catalog.departments(q, status, page);
    }

    @GetMapping("/departments/options")
    public List<CatalogOption> departmentOptions(@AuthenticationPrincipal AdminAccount admin) {
        var grants = access.permissions(admin);
        return grants.has(Role.SYSTEM_ADMIN) ? catalog.departmentOptions()
                : catalog.departmentOptions(grants.scope(Role.SCHEDULER, Role.TRIAGE, Role.VISIT_SUPERVISOR));
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public void createDepartment(@Valid @RequestBody DepartmentCreate input) {
        catalog.createDepartment(input.code(), input.name(), input.description());
    }

    @PutMapping("/departments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void editDepartment(@PathVariable long id, @Valid @RequestBody DepartmentEdit input) {
        catalog.editDepartment(id, input.name(), input.description());
    }

    @PutMapping("/departments/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void departmentStatus(@PathVariable long id, @Valid @RequestBody ActiveInput input) {
        catalog.setDepartmentActive(id, input.active());
    }

    @GetMapping("/doctors")
    public Page<Doctor> doctors(@RequestParam(required = false) @Size(max = 100) String q,
                                @RequestParam(required = false) Long departmentId,
                                @RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "1") int page) {
        return catalog.doctors(q, departmentId, status, page);
    }

    @GetMapping("/doctors/options")
    public List<CatalogOption> doctorOptions(@AuthenticationPrincipal AdminAccount admin, @RequestParam long departmentId) {
        var grants = access.permissions(admin);
        if (!grants.has(Role.SYSTEM_ADMIN)) grants.scope(Role.SCHEDULER, Role.TRIAGE, Role.VISIT_SUPERVISOR).require(departmentId);
        return catalog.doctorOptions(departmentId);
    }

    @PostMapping("/doctors")
    @ResponseStatus(HttpStatus.CREATED)
    public void createDoctor(@Valid @RequestBody DoctorCreate input) {
        catalog.createDoctor(input.departmentId(), input.name(), input.title(), input.specialty(), input.introduction());
    }

    @PutMapping("/doctors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void editDoctor(@PathVariable long id, @Valid @RequestBody DoctorEdit input) {
        catalog.editDoctor(id, input.name(), input.title(), input.specialty(), input.introduction());
    }

    @PutMapping("/doctors/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void doctorStatus(@PathVariable long id, @Valid @RequestBody ActiveInput input) {
        catalog.setDoctorActive(id, input.active());
    }

    @GetMapping("/schedules")
    public Page<Schedule> schedules(@AuthenticationPrincipal AdminAccount admin, @RequestParam(required = false) Long departmentId,
                                    @RequestParam(required = false) Long doctorId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") int page) {
        return catalog.schedules(departmentId, doctorId, date, from, to, status, page, access.permissions(admin).scope(Role.SCHEDULER));
    }

    @GetMapping("/schedules/{id}")
    public ScheduleDetail schedule(@AuthenticationPrincipal AdminAccount admin, @PathVariable long id) {
        access.permissions(admin).scope(Role.SCHEDULER).require(catalog.departmentForSchedule(id));
        return catalog.schedule(id);
    }

    @PostMapping("/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public void createSchedule(@AuthenticationPrincipal AdminAccount admin, @Valid @RequestBody ScheduleInput input) {
        access.permissions(admin).scope(Role.SCHEDULER).require(input.departmentId());
        catalog.createSchedule(input.departmentId(), input.doctorId(), input.date(), input.period(),
                input.start(), input.minutes(), input.count());
    }

    @PostMapping("/schedules/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeSchedule(@AuthenticationPrincipal AdminAccount admin, @PathVariable long id) {
        access.permissions(admin).scope(Role.SCHEDULER).require(catalog.departmentForSchedule(id));
        catalog.closeSchedule(id);
    }

    @ExceptionHandler(CatalogProblem.class)
    ResponseEntity<Map<String, String>> catalogProblem(CatalogProblem error) {
        return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
    }

    public record DepartmentCreate(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,48}") String code,
                                   @NotBlank @Size(max = 100) String name, @Size(max = 3000) String description) {}
    public record DepartmentEdit(@NotBlank @Size(max = 100) String name, @Size(max = 3000) String description) {}
    public record DoctorCreate(@NotNull @Positive Long departmentId, @NotBlank @Size(max = 100) String name,
                               @Size(max = 80) String title, @Size(max = 500) String specialty,
                               @Size(max = 3000) String introduction) {}
    public record DoctorEdit(@NotBlank @Size(max = 100) String name, @Size(max = 80) String title,
                             @Size(max = 500) String specialty, @Size(max = 3000) String introduction) {}
    public record ScheduleInput(@NotNull @Positive Long departmentId, @NotNull @Positive Long doctorId,
                                @NotNull LocalDate date, @NotNull Period period, @NotNull LocalTime start,
                                @NotNull @Min(1) @Max(120) Integer minutes, @NotNull @Min(1) @Max(50) Integer count) {}
    public record ActiveInput(@NotNull Boolean active) {}
}
