package com.mediflow.agent.service.appointment.lifecycle;

import com.mediflow.agent.mapper.appointment.AppointmentLockMapper;
import com.mediflow.agent.service.appointment.query.SlotQueryService;
import com.mediflow.agent.security.account.AdminAccess.Scope;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Service
@Profile("mysql")
public class VisitCompletionService {
    public enum Outcome { CONFIRMED, UNCONFIRMED }
    private record Visit(long id, long patient, long department, LocalDate date) {}
    private record Review(long id, Outcome outcome) {}

    private final JdbcTemplate jdbc;
    private final AppointmentLockMapper locks;
    private final Clock clock;

    public VisitCompletionService(JdbcTemplate jdbc, Clock clock, AppointmentLockMapper locks) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.clock = clock;
    }

    @Transactional
    public void complete(String number, long operator, Scope scope) {
        var visit = visit(number, scope);
        String status = lock(visit);
        Instant now = clock.instant();
        if (!visit.date().equals(SlotQueryService.hospitalDate(now))) {
            throw conflict("只可登记当日预约的就诊结果。");
        }
        if (!"CHECKED_IN".equals(status)) throw conflict("只有已签到的预约可以登记已就诊，请刷新核对。");
        jdbc.update("UPDATE appointment SET status='VISITED', visited_at=? WHERE id=?", Timestamp.from(now), visit.id());
        audit(visit, operator, "APPOINTMENT_VISITED", now);
    }

    @Transactional
    public void review(String number, long operator, Scope scope, Outcome outcome, String reason, Long expectedReviewId) {
        var visit = visit(number, scope);
        String status = lock(visit);
        Instant now = clock.instant();
        boolean originallyVisited = "VISITED".equals(status);
        if (!originallyVisited && !("CHECKED_IN".equals(status) && visit.date().isBefore(SlotQueryService.hospitalDate(now)))) {
            throw conflict("就诊流程结束后才可复核结果。");
        }
        var last = jdbc.query("""
                SELECT id,outcome FROM appointment_visit_review WHERE appointment_id=? ORDER BY id DESC LIMIT 1 FOR UPDATE
                """, (row, i) -> new Review(row.getLong(1), Outcome.valueOf(row.getString(2))), visit.id())
                .stream().findFirst().orElse(null);
        Long currentReviewId = last == null ? null : last.id();
        if (!Objects.equals(currentReviewId, expectedReviewId)) throw conflict("复核记录已变化，请刷新后重新核对。");
        boolean confirmed = last == null ? originallyVisited : last.outcome() == Outcome.CONFIRMED;
        if (confirmed == (outcome == Outcome.CONFIRMED)) throw conflict("复核结果与当前结果相同。");
        String operation = audit(visit, operator, "APPOINTMENT_VISIT_REVIEW", now);
        jdbc.update("""
                INSERT INTO appointment_visit_review(appointment_id,audit_operation_no,outcome,reason,reviewed_at)
                VALUES (?,?,?,?,?)
                """, visit.id(), operation, outcome.name(), reason.strip(), Timestamp.from(now));
    }

    private Visit visit(String number, Scope scope) {
        var visit = jdbc.query("""
                SELECT a.id,a.patient_id,a.department_id,sc.schedule_date
                FROM appointment a JOIN appointment_slot s ON s.id=a.slot_id
                JOIN doctor_schedule sc ON sc.id=s.schedule_id WHERE a.appointment_no=?
                """, (row, i) -> new Visit(row.getLong("id"), row.getLong("patient_id"), row.getLong("department_id"),
                row.getObject("schedule_date", LocalDate.class)),
                number).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这笔预约。"));
        scope.require(visit.department());
        return visit;
    }

    private String lock(Visit visit) {
        locks.lockPatient(visit.patient());
        return jdbc.queryForObject("SELECT status FROM appointment WHERE id=? FOR UPDATE", String.class, visit.id());
    }

    private String audit(Visit visit, long operator, String action, Instant now) {
        String operation = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO operation_audit_log(operation_no,patient_id,operator_type,operator_id,
                    operation_type,target_type,target_id,result_status,created_at)
                VALUES (?,?,'ADMIN',?,?,'APPOINTMENT',?,'SUCCEEDED',?)
                """, operation, visit.patient(), operator, action, visit.id(), Timestamp.from(now));
        return operation;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
