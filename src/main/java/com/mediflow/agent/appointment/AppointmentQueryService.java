package com.mediflow.agent.appointment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Reads a patient's own appointments for the portal.
 *
 * <p>Every method takes the patient id and filters on it, so a caller cannot look up someone else's
 * booking even by knowing the appointment number.
 */
@Service
@Profile("mysql")
public final class AppointmentQueryService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public record AppointmentView(String appointmentNo, String status, String bookedVia, Instant bookedAt,
                                  long slotId, String appointmentDate, String clinicPeriod, String timeRange,
                                  long departmentId, String departmentName,
                                  long doctorId, String doctorName, String doctorTitle) {
    }

    private static final String BASE_SELECT = """
            SELECT a.appointment_no, a.status, a.booked_via, a.booked_at, a.slot_id,
                   ds.schedule_date, ds.clinic_period, s.start_at, s.end_at,
                   a.department_id, d.name AS department_name,
                   a.doctor_id, dr.name AS doctor_name, dr.title AS doctor_title
            FROM appointment a
            JOIN appointment_slot s ON s.id = a.slot_id
            JOIN doctor_schedule ds ON ds.id = s.schedule_id
            JOIN department d ON d.id = a.department_id
            JOIN doctor dr ON dr.id = a.doctor_id
            """;

    private static final RowMapper<AppointmentView> MAPPER = (result, rowNum) -> new AppointmentView(
            result.getString("appointment_no"),
            result.getString("status"),
            result.getString("booked_via"),
            result.getTimestamp("booked_at").toInstant(),
            result.getLong("slot_id"),
            result.getDate("schedule_date").toLocalDate().toString(),
            result.getString("clinic_period"),
            range(result.getTimestamp("start_at"), result.getTimestamp("end_at")),
            result.getLong("department_id"),
            result.getString("department_name"),
            result.getLong("doctor_id"),
            result.getString("doctor_name"),
            result.getString("doctor_title"));

    private final JdbcTemplate jdbc;

    public AppointmentQueryService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Most recent booking first. The limit is clamped rather than rejected: it is paging, not input. */
    public List<AppointmentView> listForPatient(long patientId, int limit) {
        return jdbc.query(BASE_SELECT + " WHERE a.patient_id = ? ORDER BY a.booked_at DESC, a.id DESC LIMIT ?",
                MAPPER, patientId, Math.min(Math.max(limit, 1), MAX_LIMIT));
    }

    public Optional<AppointmentView> findForPatient(String appointmentNo, long patientId) {
        if (appointmentNo == null || appointmentNo.isBlank() || patientId <= 0) {
            return Optional.empty();
        }
        return jdbc.query(BASE_SELECT + " WHERE a.appointment_no = ? AND a.patient_id = ?",
                MAPPER, appointmentNo.trim(), patientId).stream().findFirst();
    }

    private static String range(Timestamp start, Timestamp end) {
        return SlotQueryService.formatTime(start) + "-" + SlotQueryService.formatTime(end);
    }
}
