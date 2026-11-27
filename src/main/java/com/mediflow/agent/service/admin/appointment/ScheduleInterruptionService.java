package com.mediflow.agent.service.admin.appointment;

import com.mediflow.agent.mapper.appointment.AppointmentLockMapper;
import com.mediflow.agent.service.appointment.query.SlotQueryService;
import com.mediflow.agent.security.account.AdminAccess.Scope;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Profile("mysql")
public class ScheduleInterruptionService {
    public enum Outcome { SUBSTITUTE, FOLLOW_UP, REBOOK }
    public record Impact(String appointmentNo, String timeRange) {}
    public record Preview(String token, Instant firstStart, List<Impact> appointments) {}
    public record Result(String reason, Instant stoppedAt, int affected) {}

    private record Path(long department, long doctor, String status) {}
    private record Visit(long id, long patient, String number, String status, Timestamp start, Timestamp end, long department) {}
    private record Existing(String requestId, String token, String reason, Instant time, int affected) {}

    private final JdbcTemplate jdbc;
    private final AppointmentLockMapper locks;
    private final Clock clock;

    public ScheduleInterruptionService(JdbcTemplate jdbc, Clock clock, AppointmentLockMapper locks) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(long schedule, Scope scope) {
        var path = path(schedule);
        scope.require(path.department());
        return preview(schedule, path.status(), visits(schedule, false), firstStart(schedule));
    }

    public Result result(long schedule, Scope scope) {
        scope.require(path(schedule).department());
        var stopped = existing(schedule, false);
        if (stopped == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该排班没有停诊记录。");
        return result(stopped);
    }

    @Transactional
    public Result stop(long schedule, Scope scope, long operator, UUID requestId, String token, String reason) {
        var path = path(schedule);
        scope.require(path.department());
        // Keep patient locks ahead of inventory locks, including the foreign keys used by audit inserts.
        var patients = visits(schedule, false).stream().map(Visit::patient).distinct().sorted().toList();
        patients.forEach(locks::lockPatient);
        locks.lockDepartment(path.department());
        locks.lockDoctor(path.department(), path.doctor());
        String status = locks.lockSchedule(path.doctor(), schedule);
        var existing = existing(schedule, true);
        if (existing != null) {
            if (existing.requestId().equals(requestId.toString()) && existing.token().equals(token) && existing.reason().equals(reason.strip())) {
                return result(existing);
            }
            throw conflict("该排班已办理停诊，请查看处理记录。");
        }
        var current = visits(schedule, true);
        var confirmation = preview(schedule, status, current, firstStart(schedule));
        if (!confirmation.token().equals(token) || current.stream().anyMatch(v -> !patients.contains(v.patient()))) {
            throw conflict("受影响预约已变更，请重新预览名单后确认。");
        }
        var stoppedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("INSERT INTO schedule_interruption(schedule_id,request_id,preview_token,public_reason,operator_id,occurred_at,affected_count) "
                + "VALUES (?,?,?,?,?,?,?)", schedule, requestId.toString(), token, reason.strip(), operator, Timestamp.from(stoppedAt), current.size());
        jdbc.update("UPDATE doctor_schedule SET status='CLOSED' WHERE id=?", schedule);
        jdbc.update("UPDATE appointment_slot SET status='CLOSED', version=version+1 WHERE schedule_id=? AND status<>'CLOSED'", schedule);
        for (var visit : current) {
            jdbc.update("UPDATE appointment SET status='INTERRUPTED' WHERE id=?", visit.id());
            jdbc.update("INSERT INTO interrupted_appointment(appointment_id,schedule_id) VALUES (?,?)", visit.id(), schedule);
            audit(operator, visit, "APPOINTMENT_INTERRUPTED");
        }
        return new Result(reason.strip(), stoppedAt, current.size());
    }

    @Transactional
    public void arrange(String number, Scope scope, long operator, Outcome outcome, String note) {
        var visit = jdbc.query("""
                SELECT a.id,a.patient_id,a.appointment_no,a.status,s.start_at,s.end_at,a.department_id
                FROM appointment a JOIN appointment_slot s ON s.id=a.slot_id
                JOIN interrupted_appointment i ON i.appointment_id=a.id WHERE a.appointment_no=?
                """, this::visit, number).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到待处理预约。"));
        scope.require(visit.department());
        locks.lockPatient(visit.patient());
        int updated = jdbc.update("""
                UPDATE interrupted_appointment SET outcome=?, arrangement=?, arranged_by=?, arranged_at=UTC_TIMESTAMP(3)
                WHERE appointment_id=? AND outcome IS NULL
                """, outcome.name(), note.strip(), operator, visit.id());
        if (updated == 0) {
            if (jdbc.queryForList("""
                    SELECT appointment_id FROM interrupted_appointment WHERE appointment_id=? AND outcome=? AND arrangement=? FOR UPDATE
                    """, Long.class, visit.id(), outcome.name(), note.strip()).isEmpty()) {
                throw conflict("该预约已登记处理结果，请刷新核对。");
            }
            return;
        }
        audit(operator, visit, "APPOINTMENT_ARRANGED");
    }

    private Path path(long schedule) {
        return jdbc.query("""
                SELECT d.department_id,d.id,s.status FROM doctor_schedule s JOIN doctor d ON d.id=s.doctor_id WHERE s.id=?
                """, (r, i) -> new Path(r.getLong(1), r.getLong(2), r.getString(3)), schedule).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到排班。"));
    }

    private List<Visit> visits(long schedule, boolean lock) {
        return jdbc.query("""
                SELECT a.id,a.patient_id,a.appointment_no,a.status,s.start_at,s.end_at,a.department_id
                FROM appointment a JOIN appointment_slot s ON s.id=a.slot_id
                WHERE s.schedule_id=? AND a.status IN ('BOOKED','CHECKED_IN','VISITED') ORDER BY a.id
                """ + (lock ? " FOR UPDATE" : ""), this::visit, schedule);
    }

    private Visit visit(java.sql.ResultSet r, int index) throws java.sql.SQLException {
        return new Visit(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getTimestamp(5), r.getTimestamp(6), r.getLong(7));
    }

    private Instant firstStart(long schedule) {
        return jdbc.query("SELECT start_at FROM appointment_slot WHERE schedule_id=? ORDER BY start_at LIMIT 1",
                (r, i) -> r.getTimestamp(1).toInstant(), schedule).stream().findFirst()
                .orElseThrow(() -> conflict("该排班没有号源。"));
    }

    private Preview preview(long schedule, String status, List<Visit> visits, Instant first) {
        if (!"OPEN".equals(status)) throw conflict("仅可办理开放排班的停诊。");
        if (!clock.instant().isBefore(first)) throw conflict("排班已经开始，请联系门诊人工处理。");
        if (visits.stream().anyMatch(v -> !"BOOKED".equals(v.status()))) throw conflict("已有患者签到，请联系门诊人工处理。");
        String token = digest(schedule + ":" + first + ":" + visits.stream().map(Visit::id).toList());
        return new Preview(token, first, visits.stream().map(v -> new Impact(v.number(),
                SlotQueryService.formatTime(v.start()) + "-" + SlotQueryService.formatTime(v.end()))).toList());
    }

    private Existing existing(long schedule, boolean lock) {
        return jdbc.query("""
                SELECT request_id,preview_token,public_reason,occurred_at,affected_count FROM schedule_interruption WHERE schedule_id=?
                """ + (lock ? " FOR UPDATE" : ""),
                (r, i) -> new Existing(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant(), r.getInt(5)), schedule)
                .stream().findFirst().orElse(null);
    }

    private Result result(Existing stopped) {
        return new Result(stopped.reason(), stopped.time(), stopped.affected());
    }

    private void audit(long operator, Visit visit, String action) {
        jdbc.update("""
                INSERT INTO operation_audit_log(operation_no,patient_id,operator_type,operator_id,operation_type,target_type,target_id,result_status)
                VALUES (?,?,'ADMIN',?,?,'APPOINTMENT',?,'SUCCEEDED')
                """, UUID.randomUUID().toString(), visit.patient(), operator, action, visit.id());
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
