package com.mediflow.agent.controller.admin.appointment;

import com.mediflow.agent.service.admin.appointment.AppointmentOperationsDashboard;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@Profile("mysql")
public class AdminAppointmentOperationsController {
    private final AppointmentOperationsDashboard dashboard;

    public AdminAppointmentOperationsController(AppointmentOperationsDashboard dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/admin/observability/appointments")
    public AppointmentOperationsDashboard.Dashboard summarize(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long departmentId) {
        return dashboard.summarize(from, to, departmentId);
    }
}
