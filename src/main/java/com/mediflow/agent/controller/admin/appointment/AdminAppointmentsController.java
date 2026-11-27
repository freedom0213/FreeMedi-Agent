package com.mediflow.agent.controller.admin.appointment;

import com.mediflow.agent.service.appointment.query.AppointmentQueryService;
import com.mediflow.agent.service.appointment.query.AppointmentQueryService.AdminAppointment;
import com.mediflow.agent.service.appointment.query.AppointmentQueryService.AdminFilters;
import com.mediflow.agent.service.appointment.query.AppointmentQueryService.Channel;
import com.mediflow.agent.service.appointment.query.AppointmentQueryService.Page;
import com.mediflow.agent.service.appointment.query.AppointmentQueryService.Status;
import com.mediflow.agent.service.appointment.query.AppointmentQueryService.RegisterAccess;
import com.mediflow.agent.service.appointment.query.AppointmentQueryService.VisitDesk;
import com.mediflow.agent.service.appointment.lifecycle.AppointmentWriteService;
import com.mediflow.agent.service.appointment.lifecycle.VisitCompletionService;
import com.mediflow.agent.security.config.AdminSecurityConfiguration.AdminAccount;
import com.mediflow.agent.security.account.AdminAccess;
import com.mediflow.agent.security.account.AdminAccess.Role;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.LocalDate;

@RestController
@Profile("mysql")
public class AdminAppointmentsController {
    private final AppointmentQueryService appointments;

    private final AppointmentWriteService writes;
    private final AdminAccess access;
    private final VisitCompletionService visits;

    public AdminAppointmentsController(AppointmentQueryService appointments, AppointmentWriteService writes, AdminAccess access,
                                       VisitCompletionService visits) {
        this.appointments = appointments;
        this.writes = writes;
        this.access = access;
        this.visits = visits;
    }

    @PostMapping("/api/v1/admin/appointments/{appointmentNo}/check-in")
    public ResponseEntity<AppointmentWriteService.LifecycleResult> checkIn(
            @PathVariable String appointmentNo, @AuthenticationPrincipal AdminAccount account) {
        var grants = access.permissions(account);
        if (!grants.superuser() && !appointments.mayCheckIn(appointmentNo, register(grants))) {
            throw new AccessDeniedException("仅可办理授权科室的当日预约。");
        }
        var result = writes.checkIn(appointmentNo, Long.parseLong(account.adminId()));
        return ResponseEntity.status("NOT_FOUND".equals(result.code()) ? 404 : result.success() ? 200 : 409).body(result);
    }

    @GetMapping("/api/v1/admin/appointments/{appointmentNo}/operations")
    public ResponseEntity<AppointmentQueryService.AppointmentOperations> operations(
            @AuthenticationPrincipal AdminAccount account, @PathVariable String appointmentNo, @RequestParam(defaultValue = "1") int page) {
        return appointments.appointmentOperations(appointmentNo, page, register(access.permissions(account)))
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/v1/admin/appointments/{appointmentNo}/visit-completion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@PathVariable String appointmentNo, @AuthenticationPrincipal AdminAccount account) {
        visits.complete(appointmentNo, account.accountId(), access.permissions(account).scope(Role.TRIAGE));
    }

    public record VisitReview(@NotNull VisitCompletionService.Outcome outcome, @NotBlank @Size(max = 400) String reason,
                              @Positive Long expectedReviewId) {}

    @PostMapping("/api/v1/admin/appointments/{appointmentNo}/visit-review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@PathVariable String appointmentNo, @AuthenticationPrincipal AdminAccount account, @Valid @RequestBody VisitReview input) {
        visits.review(appointmentNo, account.accountId(), access.permissions(account).scope(Role.VISIT_SUPERVISOR),
                input.outcome(), input.reason(), input.expectedReviewId());
    }

    public record PendingArrangements(long count) {}

    @GetMapping("/api/v1/admin/appointments/interruption-pending/count")
    public PendingArrangements pendingArrangements(@AuthenticationPrincipal AdminAccount account) {
        var permissions = access.permissions(account);
        return new PendingArrangements(appointments.countPendingArrangements(permissions.scope(Role.SCHEDULER)));
    }

    @GetMapping("/api/v1/admin/appointments")
    public Page<AdminAppointment> list(
            @AuthenticationPrincipal AdminAccount account,
            @RequestParam(required = false) String appointmentNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long departmentId,
            @RequestParam(required = false) @Positive Long doctorId,
            @RequestParam(required = false) @Positive Long scheduleId,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "false") boolean pendingArrangement,
            @RequestParam(required = false) VisitDesk visitDesk,
            @RequestParam(defaultValue = "1") int page) {
        var permissions = access.permissions(account);
        if (pendingArrangement && !permissions.has(Role.SCHEDULER)) {
            throw new AccessDeniedException("仅排班岗位可查看停诊待办。");
        }
        if ((visitDesk == VisitDesk.TODAY && !permissions.has(Role.TRIAGE))
                || (visitDesk == VisitDesk.REVIEW && !permissions.has(Role.VISIT_SUPERVISOR))) {
            throw new AccessDeniedException("当前岗位无法访问此就诊工作台。");
        }
        String number = appointmentNo == null || appointmentNo.isBlank() ? null : appointmentNo.strip();
        return appointments.adminAppointments(new AdminFilters(number, from, to, departmentId, doctorId,
                scheduleId, channel, status, pendingArrangement, visitDesk), page, register(permissions));
    }

    private RegisterAccess register(AdminAccess.Permissions permissions) {
        return new RegisterAccess(permissions.scope(Role.SCHEDULER), permissions.scope(Role.TRIAGE),
                permissions.scope(Role.VISIT_SUPERVISOR), appointments.operationalDate());
    }
}
