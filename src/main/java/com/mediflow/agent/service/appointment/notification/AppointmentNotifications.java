package com.mediflow.agent.service.appointment.notification;

import com.mediflow.agent.mapper.appointment.AppointmentNotificationMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@Profile("mysql")
public class AppointmentNotifications {
    public enum Kind { STOPPED, ARRANGEMENT }

    public record Notice(String appointmentNo, Kind kind, Instant occurredAt, LocalDate appointmentDate,
                         String departmentName, String doctorName, boolean unread) {}
    public record Inbox(List<Notice> items, long unreadCount, int page, int totalPages) {}

    private static final int PAGE_SIZE = 5;
    private final AppointmentNotificationMapper mapper;

    public AppointmentNotifications(AppointmentNotificationMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Inbox inbox(long patientId, int requestedPage) {
        if (requestedPage < 1) throw new IllegalArgumentException("页码必须大于 0。");
        var counts = mapper.count(patientId);
        int totalPages = Math.toIntExact((counts.total() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(requestedPage, Math.max(1, totalPages));
        var items = counts.total() == 0 ? List.<Notice>of() : mapper.list(patientId, PAGE_SIZE, (page - 1L) * PAGE_SIZE)
                .stream().map(row -> new Notice(row.appointmentNo(), Kind.valueOf(row.kind()), row.occurredAt(),
                        row.appointmentDate(), row.departmentName(), row.doctorName(), row.unread())).toList();
        return new Inbox(items, counts.unread(), page, totalPages);
    }

    @Transactional
    public boolean markRead(long patientId, String appointmentNo, Kind kind) {
        var appointmentIds = mapper.findAppointmentIds(patientId, appointmentNo, kind.name());
        if (appointmentIds.isEmpty()) return false;
        mapper.markRead(appointmentIds.get(0), kind.name());
        return true;
    }
}
