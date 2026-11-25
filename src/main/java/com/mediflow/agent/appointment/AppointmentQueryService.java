package com.mediflow.agent.appointment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Shared read model for patient-owned appointments and the administrator appointment register. */
@Service
@Profile("mysql")
public class AppointmentQueryService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public record AppointmentView(String appointmentNo, String status, String bookedVia, Instant bookedAt,
                                  long slotId, String appointmentDate, String clinicPeriod, String timeRange,
                                  long departmentId, String departmentName,
                                  long doctorId, String doctorName, String doctorTitle, Instant cancellationDeadline,
                                  Instant checkInOpensAt, Instant checkedInAt, Instant cancelledAt,
                                  boolean canCancel, boolean canCheckIn,
                                  String originalAppointmentNo, String replacementAppointmentNo) {
    }

    public enum Status { BOOKED, CHECKED_IN, CANCELLED, EXPIRED, RESCHEDULED }
    public enum Channel { PORTAL, AGENT }

    public record Page<T>(List<T> items, int page, int totalCount, int totalPages) {}
    public record PatientOverview(AppointmentView next, Page<AppointmentView> upcoming, Page<AppointmentView> history) {}
    public record AdminAppointment(long scheduleId, AppointmentView appointment) {}
    public record AdminFilters(String appointmentNo, LocalDate from, LocalDate to,
                               Long departmentId, Long doctorId, Long scheduleId, Channel channel, Status status) {}
    public record AppointmentOperation(Instant time, String action, String operatorType, String operatorName,
                                       String result, String reason) {}
    public record AppointmentOperations(String appointmentNo, String originalAppointmentNo, String replacementAppointmentNo,
                                        Page<AppointmentOperation> events) {}

    private static final String COLUMNS = """
            SELECT a.appointment_no, a.status, a.booked_via, a.booked_at, a.slot_id, ds.id AS schedule_id,
                   ds.schedule_date, ds.clinic_period, s.start_at, s.end_at, a.checked_in_at, a.cancelled_at,
                   ds.status AS schedule_status, s.status AS slot_status, d.status AS department_status, dr.status AS doctor_status,
                   a.department_id, d.name AS department_name,
                   a.doctor_id, dr.name AS doctor_name, dr.title AS doctor_title,
                   original.appointment_no AS original_no, replacement.appointment_no AS replacement_no
            """;
    private static final String TABLES = """
            FROM appointment a
            JOIN appointment_slot s ON s.id = a.slot_id
            JOIN doctor_schedule ds ON ds.id = s.schedule_id
            JOIN department d ON d.id = a.department_id
            JOIN doctor dr ON dr.id = a.doctor_id
            LEFT JOIN appointment original ON original.id = a.rescheduled_from_id AND original.patient_id = a.patient_id
            LEFT JOIN appointment replacement ON replacement.rescheduled_from_id = a.id AND replacement.patient_id = a.patient_id
            """;

    private static final String BASE_SELECT = COLUMNS + TABLES;
    private static final String PENDING_VISIT = "(a.status IN ('BOOKED', 'CHECKED_IN') AND "
            + "CASE WHEN a.status = 'CHECKED_IN' THEN s.end_at ELSE s.start_at END > ?)";
    private static final String UPCOMING = " WHERE a.patient_id = ? AND " + PENDING_VISIT;
    private static final String HISTORY = " WHERE a.patient_id = ? AND NOT " + PENDING_VISIT;
    private static final String NEWEST = " ORDER BY a.booked_at DESC, a.id DESC";

    private AppointmentView mapAppointment(ResultSet result, Instant now) throws SQLException {
        var date = result.getObject("schedule_date", LocalDate.class);
        var deadline = AppointmentTiming.cancellationDeadline(date);
        var early = AppointmentTiming.checkInOpensAt(result.getTimestamp("start_at").toInstant());
        var end = result.getTimestamp("end_at").toInstant();
        var status = result.getString("status");
        // Expiry is derived at read time so existing appointments age without a scheduled write.
        if ("BOOKED".equals(status) && !now.isBefore(end)) status = Status.EXPIRED.name();
        boolean booked = "BOOKED".equals(status);
        return new AppointmentView(
                result.getString("appointment_no"),
                status,
                result.getString("booked_via"),
                result.getTimestamp("booked_at").toInstant(),
                result.getLong("slot_id"),
                date.toString(),
                result.getString("clinic_period"),
                range(result.getTimestamp("start_at"), result.getTimestamp("end_at")),
                result.getLong("department_id"),
                result.getString("department_name"),
                result.getLong("doctor_id"),
                result.getString("doctor_name"),
                result.getString("doctor_title"),
                deadline, early, instant(result.getTimestamp("checked_in_at")), instant(result.getTimestamp("cancelled_at")),
                booked && now.isBefore(deadline),
                booked && !now.isBefore(early) && now.isBefore(end)
                        && "ACTIVE".equals(result.getString("department_status")) && "ACTIVE".equals(result.getString("doctor_status"))
                        && "OPEN".equals(result.getString("schedule_status")) && "RESERVED".equals(result.getString("slot_status")),
                result.getString("original_no"), result.getString("replacement_no"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private final JdbcTemplate jdbc;

    private final Clock clock;

    public AppointmentQueryService(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    /** Most recent booking first. The limit is clamped rather than rejected: it is paging, not input. */
    public List<AppointmentView> listForPatient(long patientId, int limit) {
        var now = clock.instant();
        return jdbc.query(BASE_SELECT + " WHERE a.patient_id = ?" + NEWEST + " LIMIT ?",
                (row, index) -> mapAppointment(row, now), patientId, Math.min(Math.max(limit, 1), MAX_LIMIT));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PatientOverview patientOverview(long patientId, int upcomingPage, int historyPage) {
        var now = clock.instant();
        var scope = List.<Object>of(patientId, Timestamp.from(now));
        RowMapper<AppointmentView> mapper = (row, index) -> mapAppointment(row, now);
        String byVisit = " ORDER BY s.start_at, a.id";
        var next = jdbc.query(BASE_SELECT + UPCOMING + byVisit + " LIMIT 1", mapper, scope.toArray())
                .stream().findFirst().orElse(null);
        var upcoming = page(UPCOMING, scope, byVisit, upcomingPage, next == null ? 0 : 1, mapper);
        var history = page(HISTORY, scope, " ORDER BY s.start_at DESC, a.id DESC", historyPage, 0, mapper);
        return new PatientOverview(next, upcoming, history);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AdminAppointment> adminAppointments(AdminFilters filters, int requestedPage) {
        if (filters.from() != null && filters.to() != null && filters.from().isAfter(filters.to())) {
            throw new IllegalArgumentException("就诊开始日期不能晚于结束日期。");
        }
        if (filters.appointmentNo() != null && filters.appointmentNo().length() > 64) {
            throw new IllegalArgumentException("预约编号最多 64 个字符。");
        }
        var params = new ArrayList<Object>();
        var where = new StringBuilder(" WHERE 1=1");
        filter(where, params, "a.appointment_no = ?", filters.appointmentNo());
        filter(where, params, "ds.schedule_date >= ?", filters.from());
        filter(where, params, "ds.schedule_date <= ?", filters.to());
        filter(where, params, "a.department_id = ?", filters.departmentId());
        filter(where, params, "a.doctor_id = ?", filters.doctorId());
        filter(where, params, "ds.id = ?", filters.scheduleId());
        filter(where, params, "a.booked_via = ?", filters.channel() == null ? null : filters.channel().name());
        var now = clock.instant();
        var status = filters.status();
        if (status == Status.BOOKED || status == Status.EXPIRED) {
            filter(where, params, "a.status = ?", Status.BOOKED.name());
            filter(where, params, status == Status.EXPIRED ? "s.end_at <= ?" : "s.end_at > ?", Timestamp.from(now));
        } else {
            filter(where, params, "a.status = ?", status == null ? null : status.name());
        }
        return page(where.toString(), params, NEWEST, requestedPage, 0,
                (row, index) -> new AdminAppointment(row.getLong("schedule_id"), mapAppointment(row, now)));
    }

    private static void filter(StringBuilder where, List<Object> params, String condition, Object value) {
        if (value == null) return;
        where.append(" AND ").append(condition);
        params.add(value);
    }

    private <T> Page<T> page(String where, List<Object> scope, String order, int requestedPage,
                             int skip, RowMapper<T> mapper) {
        if (requestedPage < 1) throw new IllegalArgumentException("页码必须大于 0。");
        int count = jdbc.queryForObject("SELECT COUNT(*) " + TABLES + where, Integer.class, scope.toArray()) - skip;
        int totalPages = (int) ((count + (long) DEFAULT_LIMIT - 1) / DEFAULT_LIMIT);
        int page = Math.min(requestedPage, Math.max(1, totalPages));
        if (count == 0) return new Page<>(List.of(), page, 0, 0);
        var params = new ArrayList<>(scope);
        params.add(DEFAULT_LIMIT);
        params.add((long) (page - 1) * DEFAULT_LIMIT + skip);
        var items = jdbc.query(BASE_SELECT + where + order + " LIMIT ? OFFSET ?", mapper, params.toArray());
        return new Page<>(items, page, count, totalPages);
    }

    public Optional<AppointmentView> findForPatient(String appointmentNo, long patientId) {
        if (appointmentNo == null || appointmentNo.isBlank() || patientId <= 0) {
            return Optional.empty();
        }
        var now = clock.instant();
        return jdbc.query(BASE_SELECT + " WHERE a.appointment_no = ? AND a.patient_id = ?",
                (row, index) -> mapAppointment(row, now), appointmentNo.trim(), patientId).stream().findFirst();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<AppointmentOperations> appointmentOperations(String appointmentNo, int requestedPage) {
        if (requestedPage < 1) throw new IllegalArgumentException("页码必须大于 0。");
        var appointment = jdbc.query("""
                SELECT a.id, a.rescheduled_from_id, a.appointment_no, original.appointment_no AS original_no,
                       replacement.appointment_no AS replacement_no
                FROM appointment a
                LEFT JOIN appointment original ON original.id = a.rescheduled_from_id
                LEFT JOIN appointment replacement ON replacement.rescheduled_from_id = a.id
                WHERE a.appointment_no = ?
                """, (row, index) -> new OperationTarget(row.getLong("id"), row.getObject("rescheduled_from_id", Long.class),
                row.getString("appointment_no"), row.getString("original_no"), row.getString("replacement_no")),
                appointmentNo).stream().findFirst();
        if (appointment.isEmpty()) return Optional.empty();
        var target = appointment.get();
        String events = """
                FROM operation_audit_log audit
                LEFT JOIN sys_user staff ON audit.operator_type = 'ADMIN' AND staff.id = audit.operator_id
                WHERE audit.target_type = 'APPOINTMENT'
                  AND (audit.target_id = ? OR (audit.target_id = ?
                       AND audit.operation_type = 'APPOINTMENT_RESCHEDULE' AND audit.result_status = 'SUCCEEDED'))
                """;
        int count = jdbc.queryForObject("SELECT COUNT(*) " + events, Integer.class, target.id(), target.originalId());
        int totalPages = (int) ((count + (long) DEFAULT_LIMIT - 1) / DEFAULT_LIMIT);
        int page = Math.min(requestedPage, Math.max(1, totalPages));
        var items = jdbc.query("""
                SELECT audit.created_at, audit.operation_type, audit.operator_type, staff.display_name,
                       audit.result_status, audit.result_code
                """ + events + " ORDER BY audit.created_at DESC, audit.id DESC LIMIT ? OFFSET ?",
                (row, index) -> new AppointmentOperation(row.getTimestamp("created_at").toInstant(),
                        row.getString("operation_type"), row.getString("operator_type"), row.getString("display_name"),
                        row.getString("result_status"), operationReason(row.getString("operation_type"), row.getString("result_code"))),
                target.id(), target.originalId(), DEFAULT_LIMIT, (long) (page - 1) * DEFAULT_LIMIT);
        return Optional.of(new AppointmentOperations(target.number(), target.originalNo(), target.replacementNo(),
                new Page<>(items, page, count, totalPages)));
    }

    private static String operationReason(String type, String code) {
        if (code == null) return null;
        return "APPOINTMENT_RESCHEDULE".equals(type) ? AppointmentWriteService.rescheduleRejection(code).message() : code;
    }

    private record OperationTarget(long id, Long originalId, String number, String originalNo, String replacementNo) {}

    /** Locks the patient before the appointment because creating the consultation also references the patient. */
    public boolean lockBookedForPatient(String appointmentNo, long patientId) {
        AppointmentAvailability.lockPatient(jdbc, patientId);
        return !jdbc.queryForList("""
                SELECT id FROM appointment
                WHERE appointment_no = ? AND patient_id = ? AND status IN ('BOOKED', 'CHECKED_IN')
                FOR UPDATE
                """, Long.class, appointmentNo, patientId).isEmpty();
    }

    private static String range(Timestamp start, Timestamp end) {
        return SlotQueryService.formatTime(start) + "-" + SlotQueryService.formatTime(end);
    }
}
