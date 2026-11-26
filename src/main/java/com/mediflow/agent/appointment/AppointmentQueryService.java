package com.mediflow.agent.appointment;

import com.mediflow.agent.config.AdminAccess.Scope;
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
                                  Instant checkInOpensAt, Instant checkedInAt, Instant cancelledAt, Instant appointmentEndsAt,
                                  boolean canCancel, boolean canCheckIn,
                                  String originalAppointmentNo, String replacementAppointmentNo, Interruption interruption,
                                  VisitProgress visitProgress) {
    }

    public record Interruption(String reason, Instant at, String outcome, String arrangement) {}
    public record VisitProgress(Instant completedAt, VisitCompletionService.Outcome reviewedOutcome, Instant reviewedAt) {}
    public enum Status { BOOKED, CHECKED_IN, VISITED, VISIT_UNCONFIRMED, CANCELLED, EXPIRED, RESCHEDULED, INTERRUPTED }
    public enum Channel { PORTAL, AGENT }
    public enum VisitDesk { TODAY, REVIEW }

    public record Page<T>(List<T> items, int page, int totalCount, int totalPages) {}
    public record PatientOverview(AppointmentView next, Page<AppointmentView> upcoming, Page<AppointmentView> history,
                                  List<AppointmentView> interrupted) {}
    public record AdminAppointment(long scheduleId, AppointmentView appointment, String patientName, boolean checkInAllowed,
                                   boolean visitCompletionAllowed, boolean visitReviewAllowed, Long visitReviewId) {}
    public record AdminFilters(String appointmentNo, LocalDate from, LocalDate to,
                               Long departmentId, Long doctorId, Long scheduleId, Channel channel, Status status,
                               boolean pendingArrangement, VisitDesk visitDesk) {}
    public record AppointmentOperation(Instant time, String action, String operatorType, String operatorName,
                                       String result, String reason) {}
    public record AppointmentOperations(String appointmentNo, String originalAppointmentNo, String replacementAppointmentNo,
                                        Page<AppointmentOperation> events) {}

    public record RegisterAccess(Scope scheduler, Scope triage, Scope reviewer, LocalDate today) {
        String predicate(List<Object> args) {
            String scheduled = scheduler.predicate("a.department_id", args);
            String checked = todayPredicate(args);
            String reviewed = reviewPredicate(args);
            return "(" + scheduled + " OR (" + checked + ") OR (" + reviewed + "))";
        }

        String todayPredicate(List<Object> args) {
            String scope = triage.predicate("a.department_id", args);
            args.add(today);
            return scope + " AND ds.schedule_date=?";
        }

        String reviewPredicate(List<Object> args) {
            String scope = reviewer.predicate("a.department_id", args);
            args.add(today);
            return scope + " AND " + CLOSED_VISIT;
        }

        boolean canTriage(long department, LocalDate date) {
            return triage.all() || (date.equals(today) && triage.allows(department));
        }
    }

    private static final String CLOSED_VISIT = "(a.status='VISITED' OR (a.status='CHECKED_IN' AND ds.schedule_date<?))";
    private static final String CONFIRMED_VISIT = "(review.outcome='CONFIRMED' OR (a.status='VISITED' AND review.id IS NULL))";
    private static final String COLUMNS = """
            SELECT a.appointment_no, a.status, a.booked_via, a.booked_at, a.slot_id, ds.id AS schedule_id,
                   ds.schedule_date, ds.clinic_period, s.start_at, s.end_at, a.checked_in_at, a.cancelled_at,
                   ds.status AS schedule_status, s.status AS slot_status, d.status AS department_status, dr.status AS doctor_status,
                   a.department_id, d.name AS department_name,
                   a.doctor_id, dr.name AS doctor_name, dr.title AS doctor_title,
                   original.appointment_no AS original_no, replacement.appointment_no AS replacement_no,
                   stop.public_reason, stop.occurred_at AS stopped_at, impact.outcome, impact.arrangement,
                   patient.display_name AS patient_display_name, a.visited_at,
                   review.id AS visit_review_id, review.outcome AS reviewed_outcome, review.reviewed_at
            """;
    private static final String TABLES = """
            FROM appointment a
            JOIN appointment_slot s ON s.id = a.slot_id
            JOIN doctor_schedule ds ON ds.id = s.schedule_id
            JOIN department d ON d.id = a.department_id
            JOIN doctor dr ON dr.id = a.doctor_id
            JOIN sys_user patient ON patient.id=a.patient_id
            LEFT JOIN appointment original ON original.id = a.rescheduled_from_id AND original.patient_id = a.patient_id
            LEFT JOIN appointment replacement ON replacement.rescheduled_from_id = a.id AND replacement.patient_id = a.patient_id
            LEFT JOIN interrupted_appointment impact ON impact.appointment_id=a.id
            LEFT JOIN schedule_interruption stop ON stop.schedule_id=impact.schedule_id
            LEFT JOIN appointment_visit_review review ON review.id=(
                SELECT MAX(r.id) FROM appointment_visit_review r WHERE r.appointment_id=a.id
            )
            """;

    private static final String BASE_SELECT = COLUMNS + TABLES;
    private static final String PENDING_ARRANGEMENT = "a.status = 'INTERRUPTED' AND impact.appointment_id IS NOT NULL AND impact.outcome IS NULL";
    private static final String PENDING_VISIT = "((a.status='BOOKED' AND s.end_at>?) "
            + "OR (a.status='CHECKED_IN' AND ds.schedule_date>=?))";
    private static final String UPCOMING = " WHERE a.patient_id = ? AND " + PENDING_VISIT;
    private static final String HISTORY = " WHERE a.patient_id = ? AND NOT " + PENDING_VISIT;
    private static final String NEWEST = " ORDER BY a.booked_at DESC, a.id DESC";

    private AppointmentView mapAppointment(ResultSet result, Instant now, HospitalRules.Rules policy) throws SQLException {
        var date = result.getObject("schedule_date", LocalDate.class);
        var deadline = policy.cancelBefore(date);
        var early = policy.checkInFrom(result.getTimestamp("start_at").toInstant());
        var end = result.getTimestamp("end_at").toInstant();
        var status = result.getString("status");
        // Expiry is derived at read time so existing appointments age without a scheduled write.
        if ("BOOKED".equals(status) && !now.isBefore(end)) status = Status.EXPIRED.name();
        if ("CHECKED_IN".equals(status) && date.isBefore(SlotQueryService.hospitalDate(now))) status = Status.VISIT_UNCONFIRMED.name();
        boolean booked = "BOOKED".equals(status);
        var reviewed = result.getString("reviewed_outcome");
        var visitedAt = instant(result.getTimestamp("visited_at"));
        var progress = visitedAt == null && reviewed == null ? null : new VisitProgress(visitedAt,
                reviewed == null ? null : VisitCompletionService.Outcome.valueOf(reviewed), instant(result.getTimestamp("reviewed_at")));
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
                deadline, early, instant(result.getTimestamp("checked_in_at")), instant(result.getTimestamp("cancelled_at")), end,
                booked && now.isBefore(deadline),
                booked && !now.isBefore(early) && now.isBefore(end)
                        && "ACTIVE".equals(result.getString("department_status")) && "ACTIVE".equals(result.getString("doctor_status"))
                        && "OPEN".equals(result.getString("schedule_status")) && "RESERVED".equals(result.getString("slot_status")),
                result.getString("original_no"), result.getString("replacement_no"),
                result.getString("public_reason") == null ? null : new Interruption(result.getString("public_reason"),
                        instant(result.getTimestamp("stopped_at")), result.getString("outcome"), result.getString("arrangement")),
                progress);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private final JdbcTemplate jdbc;

    private final Clock clock;
    private final HospitalRules rules;

    public AppointmentQueryService(DataSource dataSource, Clock clock) {
        this(dataSource, clock, new HospitalRules(new JdbcTemplate(dataSource)));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AppointmentQueryService(DataSource dataSource, Clock clock, HospitalRules rules) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
        this.rules = rules;
    }

    /** Most recent booking first. The limit is clamped rather than rejected: it is paging, not input. */
    public List<AppointmentView> listForPatient(long patientId, int limit) {
        var now = clock.instant();
        var policy = rules.active();
        return jdbc.query(BASE_SELECT + " WHERE a.patient_id = ?" + NEWEST + " LIMIT ?",
                (row, index) -> mapAppointment(row, now, policy), patientId, Math.min(Math.max(limit, 1), MAX_LIMIT));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PatientOverview patientOverview(long patientId, int upcomingPage, int historyPage) {
        var now = clock.instant();
        var policy = rules.active();
        var scope = List.<Object>of(patientId, Timestamp.from(now), SlotQueryService.hospitalDate(now));
        RowMapper<AppointmentView> mapper = (row, index) -> mapAppointment(row, now, policy);
        String byVisit = " ORDER BY s.start_at, a.id";
        var next = jdbc.query(BASE_SELECT + UPCOMING + byVisit + " LIMIT 1", mapper, scope.toArray())
                .stream().findFirst().orElse(null);
        var upcoming = page(UPCOMING, scope, byVisit, upcomingPage, next == null ? 0 : 1, mapper);
        var history = page(HISTORY, scope, " ORDER BY s.start_at DESC, a.id DESC", historyPage, 0, mapper);
        var interrupted = jdbc.query(BASE_SELECT + """
                 WHERE a.patient_id=? AND a.status='INTERRUPTED' AND ds.schedule_date>=?
                 ORDER BY ds.schedule_date,s.start_at,a.id LIMIT 20
                """, mapper, patientId, SlotQueryService.hospitalDate(now));
        return new PatientOverview(next, upcoming, history, interrupted);
    }

    public LocalDate operationalDate() { return SlotQueryService.hospitalDate(clock.instant()); }

    public long countPendingArrangements(Scope scheduler) {
        var params = new ArrayList<Object>();
        var scope = scheduler.predicate("a.department_id", params);
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM interrupted_appointment impact JOIN appointment a ON a.id = impact.appointment_id
                WHERE
                """ + PENDING_ARRANGEMENT + " AND " + scope, Long.class, params.toArray());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AdminAppointment> adminAppointments(AdminFilters filters, int requestedPage, RegisterAccess access) {
        if (filters.from() != null && filters.to() != null && filters.from().isAfter(filters.to())) {
            throw new IllegalArgumentException("就诊开始日期不能晚于结束日期。");
        }
        if (filters.appointmentNo() != null && filters.appointmentNo().length() > 64) {
            throw new IllegalArgumentException("预约编号最多 64 个字符。");
        }
        var params = new ArrayList<Object>();
        var where = new StringBuilder(" WHERE ");
        if (filters.visitDesk() == VisitDesk.TODAY) {
            where.append(access.todayPredicate(params));
        } else if (filters.visitDesk() == VisitDesk.REVIEW) {
            where.append(access.reviewPredicate(params));
        } else if (filters.pendingArrangement()) {
            where.append(PENDING_ARRANGEMENT).append(" AND ").append(access.scheduler().predicate("a.department_id", params));
        } else {
            where.append(access.predicate(params));
        }
        filter(where, params, "a.appointment_no = ?", filters.appointmentNo());
        filter(where, params, "ds.schedule_date >= ?", filters.from());
        filter(where, params, "ds.schedule_date <= ?", filters.to());
        filter(where, params, "a.department_id = ?", filters.departmentId());
        filter(where, params, "a.doctor_id = ?", filters.doctorId());
        filter(where, params, "ds.id = ?", filters.scheduleId());
        filter(where, params, "a.booked_via = ?", filters.channel() == null ? null : filters.channel().name());
        var now = clock.instant();
        var policy = rules.active();
        var status = filters.visitDesk() == null && filters.pendingArrangement() ? null : filters.status();
        if (status == Status.BOOKED || status == Status.EXPIRED) {
            filter(where, params, "a.status = ?", Status.BOOKED.name());
            filter(where, params, status == Status.EXPIRED ? "s.end_at <= ?" : "s.end_at > ?", Timestamp.from(now));
        } else if (status == Status.CHECKED_IN) {
            where.append(" AND a.status='CHECKED_IN' AND ds.schedule_date>=?");
            params.add(access.today());
        } else if (status == Status.VISITED) {
            where.append(" AND ").append(CONFIRMED_VISIT);
        } else if (status == Status.VISIT_UNCONFIRMED) {
            where.append(" AND ").append(CLOSED_VISIT).append(" AND NOT COALESCE(").append(CONFIRMED_VISIT).append(",FALSE)");
            params.add(access.today());
        } else {
            filter(where, params, "a.status = ?", status == null ? null : status.name());
        }
        return page(where.toString(), params, NEWEST, requestedPage, 0, (row, index) -> {
            var a = mapAppointment(row, now, policy);
            var date = row.getObject("schedule_date", LocalDate.class);
            boolean canTriage = access.canTriage(a.departmentId(), date);
            boolean canReview = access.reviewer().allows(a.departmentId())
                    && (Status.VISITED.name().equals(a.status()) || Status.VISIT_UNCONFIRMED.name().equals(a.status()));
            return new AdminAppointment(row.getLong("schedule_id"), a, canTriage || canReview ? row.getString("patient_display_name") : null,
                    canTriage && a.canCheckIn(), canTriage && date.equals(access.today()) && Status.CHECKED_IN.name().equals(a.status()),
                    canReview, canReview ? row.getObject("visit_review_id", Long.class) : null);
        });
    }

    public boolean mayCheckIn(String appointmentNo, RegisterAccess access) {
        return jdbc.query("""
                SELECT a.department_id,ds.schedule_date FROM appointment a JOIN appointment_slot s ON s.id=a.slot_id
                JOIN doctor_schedule ds ON ds.id=s.schedule_id WHERE a.appointment_no=?
                """, (row, index) -> access.canTriage(row.getLong(1), row.getObject(2, LocalDate.class)), appointmentNo)
                .stream().anyMatch(Boolean::booleanValue);
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
        var policy = rules.active();
        return jdbc.query(BASE_SELECT + " WHERE a.appointment_no = ? AND a.patient_id = ?",
                (row, index) -> mapAppointment(row, now, policy), appointmentNo.trim(), patientId).stream().findFirst();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<AppointmentOperations> appointmentOperations(String appointmentNo, int requestedPage, RegisterAccess access) {
        if (requestedPage < 1) throw new IllegalArgumentException("页码必须大于 0。");
        var args = new ArrayList<Object>();
        args.add(appointmentNo);
        String permitted = access.predicate(args);
        var appointment = jdbc.query("""
                SELECT a.id, a.rescheduled_from_id, a.appointment_no, original.appointment_no AS original_no,
                       replacement.appointment_no AS replacement_no
                """ + TABLES + " WHERE a.appointment_no=? AND " + permitted,
                (row, index) -> new OperationTarget(row.getLong("id"), row.getObject("rescheduled_from_id", Long.class),
                row.getString("appointment_no"), row.getString("original_no"), row.getString("replacement_no")),
                args.toArray()).stream().findFirst();
        if (appointment.isEmpty()) return Optional.empty();
        var target = appointment.get();
        String events = """
                FROM operation_audit_log audit
                LEFT JOIN sys_user staff ON audit.operator_type = 'ADMIN' AND staff.id = audit.operator_id
                LEFT JOIN interrupted_appointment impact ON impact.appointment_id=audit.target_id
                LEFT JOIN schedule_interruption stop ON stop.schedule_id=impact.schedule_id
                LEFT JOIN appointment_visit_review review ON review.audit_operation_no=audit.operation_no
                WHERE audit.target_type = 'APPOINTMENT'
                  AND (audit.target_id = ? OR (audit.target_id = ?
                       AND audit.operation_type = 'APPOINTMENT_RESCHEDULE' AND audit.result_status = 'SUCCEEDED'))
                """;
        int count = jdbc.queryForObject("SELECT COUNT(*) " + events, Integer.class, target.id(), target.originalId());
        int totalPages = (int) ((count + (long) DEFAULT_LIMIT - 1) / DEFAULT_LIMIT);
        int page = Math.min(requestedPage, Math.max(1, totalPages));
        var items = jdbc.query("""
                SELECT audit.created_at, audit.operation_type, audit.operator_type, staff.display_name,
                       audit.result_status, audit.result_code,
                       CASE WHEN audit.operation_type='APPOINTMENT_INTERRUPTED' THEN stop.public_reason
                            WHEN audit.operation_type='APPOINTMENT_ARRANGED' THEN impact.arrangement
                            WHEN audit.operation_type='APPOINTMENT_VISIT_REVIEW' THEN
                                CONCAT(IF(review.outcome='CONFIRMED','确认已就诊','结果待核实'), ' · ', review.reason)
                       END AS public_note
                """ + events + " ORDER BY audit.created_at DESC, audit.id DESC LIMIT ? OFFSET ?",
                (row, index) -> new AppointmentOperation(row.getTimestamp("created_at").toInstant(),
                        row.getString("operation_type"), row.getString("operator_type"), row.getString("display_name"),
                        row.getString("result_status"), row.getString("public_note") != null ? row.getString("public_note")
                                : operationReason(row.getString("operation_type"), row.getString("result_code"))),
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
