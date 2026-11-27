package com.mediflow.agent.controller.patient.appointment;

import com.mediflow.agent.agent.runtime.Principal;
import com.mediflow.agent.service.appointment.notification.AppointmentNotifications;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/appointment-notifications")
public class PortalAppointmentNotificationsController {
    private final AppointmentNotifications notifications;

    public PortalAppointmentNotificationsController(AppointmentNotifications notifications) {
        this.notifications = notifications;
    }

    public record MarkRead(@NotNull AppointmentNotifications.Kind kind) {}

    @GetMapping
    public AppointmentNotifications.Inbox inbox(@AuthenticationPrincipal(expression = "patient") Principal patient,
                                                @RequestParam(defaultValue = "1") int page) {
        return notifications.inbox(patient.userId(), page);
    }

    @PostMapping("/{appointmentNo}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal(expression = "patient") Principal patient,
                                     @PathVariable String appointmentNo, @Valid @RequestBody MarkRead request) {
        return notifications.markRead(patient.userId(), appointmentNo, request.kind())
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
