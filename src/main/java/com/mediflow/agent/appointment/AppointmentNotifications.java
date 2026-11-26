package com.mediflow.agent.appointment;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private record Counts(long total, long unread) {}

    private static final int PAGE_SIZE = 5;
    private static final String NOTICES = """
            FROM appointment a
            JOIN interrupted_appointment i ON i.appointment_id = a.id
            JOIN schedule_interruption sc ON sc.schedule_id = i.schedule_id
            JOIN (
                SELECT 'STOPPED' AS kind
                UNION ALL SELECT 'ARRANGEMENT'
            ) event ON event.kind = 'STOPPED' OR i.arranged_at IS NOT NULL
            LEFT JOIN appointment_notice_receipt r ON r.appointment_id = a.id AND r.notice_kind = event.kind
            """;
    private final JdbcTemplate jdbc;

    public AppointmentNotifications(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Inbox inbox(long patientId, int requestedPage) {
        if (requestedPage < 1) throw new IllegalArgumentException("页码必须大于 0。");
        var counts = jdbc.queryForObject("SELECT COUNT(*), COUNT(*) - COUNT(r.read_at) " + NOTICES + " WHERE a.patient_id = ?",
                (row, index) -> new Counts(row.getLong(1), row.getLong(2)), patientId);
        int totalPages = Math.toIntExact((counts.total() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(requestedPage, Math.max(1, totalPages));
        var items = counts.total() == 0 ? List.<Notice>of() : jdbc.query("""
                SELECT a.appointment_no, event.kind,
                       CASE WHEN event.kind = 'STOPPED' THEN sc.occurred_at ELSE i.arranged_at END AS occurred_at,
                       sched.schedule_date, d.name AS department_name, dr.name AS doctor_name, r.read_at IS NULL AS unread
                """ + NOTICES + """
                JOIN doctor_schedule sched ON sched.id = i.schedule_id
                JOIN department d ON d.id = a.department_id
                JOIN doctor dr ON dr.id = a.doctor_id
                WHERE a.patient_id = ?
                ORDER BY unread DESC, occurred_at DESC, a.id DESC, event.kind
                LIMIT ? OFFSET ?
                """, (row, index) -> new Notice(row.getString("appointment_no"), Kind.valueOf(row.getString("kind")),
                row.getTimestamp("occurred_at").toInstant(), row.getObject("schedule_date", LocalDate.class),
                row.getString("department_name"), row.getString("doctor_name"), row.getBoolean("unread")),
                patientId, PAGE_SIZE, (page - 1L) * PAGE_SIZE);
        return new Inbox(items, counts.unread(), page, totalPages);
    }

    @Transactional
    public boolean markRead(long patientId, String appointmentNo, Kind kind) {
        var appointmentIds = jdbc.queryForList("""
                SELECT a.id FROM appointment a
                JOIN interrupted_appointment i ON i.appointment_id = a.id
                WHERE a.patient_id = ? AND a.appointment_no = ? AND (? = 'STOPPED' OR i.arranged_at IS NOT NULL)
                """, Long.class, patientId, appointmentNo, kind.name());
        if (appointmentIds.isEmpty()) return false;
        jdbc.update("""
                INSERT INTO appointment_notice_receipt (appointment_id, notice_kind, read_at)
                VALUES (?, ?, UTC_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE read_at = appointment_notice_receipt.read_at
                """, appointmentIds.get(0), kind.name());
        return true;
    }
}
