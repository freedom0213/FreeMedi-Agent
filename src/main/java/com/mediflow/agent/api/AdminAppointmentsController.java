package com.mediflow.agent.api;

import com.mediflow.agent.appointment.AppointmentQueryService;
import com.mediflow.agent.appointment.AppointmentQueryService.AdminAppointment;
import com.mediflow.agent.appointment.AppointmentQueryService.AdminFilters;
import com.mediflow.agent.appointment.AppointmentQueryService.Channel;
import com.mediflow.agent.appointment.AppointmentQueryService.Page;
import com.mediflow.agent.appointment.AppointmentQueryService.Status;
import com.mediflow.agent.appointment.AppointmentWriteService;
import com.mediflow.agent.config.AdminSecurityConfiguration.AdminAccount;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@Profile("mysql")
public class AdminAppointmentsController {
    private final AppointmentQueryService appointments;

    private final AppointmentWriteService writes;

    public AdminAppointmentsController(AppointmentQueryService appointments, AppointmentWriteService writes) {
        this.appointments = appointments;
        this.writes = writes;
    }

    @PostMapping("/api/v1/admin/appointments/{appointmentNo}/check-in")
    public ResponseEntity<AppointmentWriteService.LifecycleResult> checkIn(
            @PathVariable String appointmentNo, @AuthenticationPrincipal AdminAccount account) {
        var result = writes.checkIn(appointmentNo, Long.parseLong(account.adminId()));
        return ResponseEntity.status("NOT_FOUND".equals(result.code()) ? 404 : result.success() ? 200 : 409).body(result);
    }

    @GetMapping("/api/v1/admin/appointments/{appointmentNo}/operations")
    public ResponseEntity<AppointmentQueryService.AppointmentOperations> operations(
            @PathVariable String appointmentNo, @RequestParam(defaultValue = "1") int page) {
        return appointments.appointmentOperations(appointmentNo, page)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/v1/admin/appointments")
    public Page<AdminAppointment> list(
            @RequestParam(required = false) String appointmentNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Positive Long departmentId,
            @RequestParam(required = false) @Positive Long doctorId,
            @RequestParam(required = false) @Positive Long scheduleId,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "1") int page) {
        String number = appointmentNo == null || appointmentNo.isBlank() ? null : appointmentNo.strip();
        return appointments.adminAppointments(new AdminFilters(number, from, to, departmentId, doctorId,
                scheduleId, channel, status), page);
    }
}
