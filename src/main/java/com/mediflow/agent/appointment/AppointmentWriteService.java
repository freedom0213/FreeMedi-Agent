package com.mediflow.agent.appointment;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.mediflow.agent.agent.PlanStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single write entry point for appointments.
 *
 * <p>Both the agent confirmation flow and the ordinary portal booking flow call this class, so slot
 * locking, idempotency and auditing cannot be bypassed by adding another code path. It deliberately
 * knows nothing about agent tasks: an agent caller passes a {@link PlanClaim} whose plan row is
 * validated inside the same transaction that reserves the slot.
 *
 * <p>Doctor and department are always derived from the locked database rows, never from the caller,
 * so a request cannot book a slot that does not belong to the claimed doctor or department.
 */
@Service
@Profile("mysql")
public final class AppointmentWriteService {
    private static final Logger logger = LoggerFactory.getLogger(AppointmentWriteService.class);

    /** Where a booking came from. Recorded on the appointment row and used by audit. */
    public enum Source { AGENT, PORTAL }

    /** Who is booking. Mirrors the agent layer's principal without depending on it. */
    public record Actor(long userId, String type) {
        public Actor {
            if (userId <= 0 || type == null || type.isBlank()) {
                throw new IllegalArgumentException("booking actor is incomplete");
            }
        }
    }

    /** An agent plan the caller believes is confirmable; revalidated under lock in the transaction. */
    public record PlanClaim(String planNo, int version) {
        public PlanClaim {
            if (planNo == null || planNo.isBlank() || version <= 0) {
                throw new IllegalArgumentException("plan claim is incomplete");
            }
        }
    }

    /**
     * Exactly one target must be supplied: {@code plan} on the agent path, or {@code slotId} on the
     * portal path.
     */
    public record BookingCommand(Actor actor, String idempotencyKey, Source source,
                                 Long slotId, PlanClaim plan) {
        public BookingCommand {
            if (actor == null || idempotencyKey == null || idempotencyKey.isBlank() || source == null) {
                throw new IllegalArgumentException("booking command is incomplete");
            }
            if ((plan == null) == (slotId == null)) {
                throw new IllegalArgumentException("booking command needs exactly one target");
            }
        }

        public static BookingCommand forPlan(Actor actor, String idempotencyKey, String planNo, int version) {
            return new BookingCommand(actor, idempotencyKey, Source.AGENT, null, new PlanClaim(planNo, version));
        }

        public static BookingCommand forSlot(Actor actor, String idempotencyKey, long slotId) {
            return new BookingCommand(actor, idempotencyKey, Source.PORTAL, slotId, null);
        }
    }

    public record BookingResult(Outcome outcome, String appointmentNo, String code, String message) {
        public enum Outcome { CREATED, EXISTING, REJECTED }

        public boolean success() {
            return outcome != Outcome.REJECTED;
        }

        static BookingResult created(String appointmentNo) {
            return new BookingResult(Outcome.CREATED, appointmentNo, null, "预约已创建");
        }

        static BookingResult existing(String appointmentNo) {
            return new BookingResult(Outcome.EXISTING, appointmentNo, null, "预约已存在，已返回原预约");
        }

        static BookingResult replay(String appointmentNo, String status) {
            return switch (status) {
                case "CANCELLED" -> new BookingResult(Outcome.REJECTED, appointmentNo, "APPOINTMENT_CANCELLED", "原预约已取消。");
                case "RESCHEDULED" -> new BookingResult(Outcome.REJECTED, appointmentNo, "APPOINTMENT_RESCHEDULED",
                        "原预约已改约，可从原预约详情查看新预约。");
                case "INTERRUPTED" -> new BookingResult(Outcome.REJECTED, appointmentNo, "APPOINTMENT_INTERRUPTED",
                        "原排班已停诊，请查看预约详情中的院方安排。");
                default -> existing(appointmentNo);
            };
        }

        static BookingResult failure(String code, String message) {
            return new BookingResult(Outcome.REJECTED, null, code, message);
        }
    }

    public record VerificationResult(boolean success, String message, String code) {
    }

    public record RescheduleCommand(long patientId, String appointmentNo, long slotId, String idempotencyKey) {
    }

    public record LifecycleResult(boolean success, String code, String message) {
        static LifecycleResult accepted(String message) {
            return new LifecycleResult(true, null, message);
        }

        static LifecycleResult refused(String code, String message) {
            return new LifecycleResult(false, code, message);
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final HospitalRules rules;
    private final TransactionTemplate transactions;
    /**
     * Used only when the booking transaction is already rolling back, so the failure record cannot
     * share it. Best effort: it must never replace the original error.
     */
    private final TransactionTemplate failureAuditTransactions;

    public AppointmentWriteService(DataSource dataSource, PlatformTransactionManager transactionManager, Clock clock) {
        this(dataSource, transactionManager, clock, new HospitalRules(new JdbcTemplate(dataSource)));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AppointmentWriteService(DataSource dataSource, PlatformTransactionManager transactionManager, Clock clock, HospitalRules rules) {
        this.clock = clock;
        this.rules = rules;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.failureAuditTransactions = new TransactionTemplate(transactionManager);
        this.failureAuditTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs the booking and records the outcome.
     *
     * <p>A business refusal is a normal result, not an error: {@link #bookInTransaction} returns it
     * and the transaction commits, so a refusal is audited inside that same transaction. Only an
     * unexpected exception rolls the transaction back, and that path needs its own transaction to be
     * able to leave a record at all.
     */
    public BookingResult book(BookingCommand command) {
        try {
            return transactions.execute(status -> bookInTransaction(command));
        } catch (DuplicateKeyException duplicate) {
            return transactions.execute(status -> resolveDuplicate(command, duplicate));
        } catch (RuntimeException unexpected) {
            auditUnexpectedFailure(command, unexpected);
            throw unexpected;
        }
    }

    public LifecycleResult cancel(String appointmentNo, long patientId) {
        return transactions.execute(tx -> changeVisit(appointmentNo, patientId, patientId, false));
    }

    public LifecycleResult checkIn(String appointmentNo, long administratorId) {
        return transactions.execute(tx -> changeVisit(appointmentNo, null, administratorId, true));
    }

    private LifecycleResult changeVisit(String appointmentNo, Long owner, long actor, boolean checkIn) {
        var target = findVisit(appointmentNo, owner);
        if (target == null) return LifecycleResult.refused("NOT_FOUND", "找不到这笔预约。");

        // Match the booking and closing order. Read mutable status only after locking the inventory.
        AppointmentAvailability.lockPatient(jdbc, target.patientId());
        SlotPath path = findSlotPath(target.slotId());
        String department = AppointmentAvailability.lockDepartment(jdbc, path.departmentId());
        String doctor = AppointmentAvailability.lockDoctor(jdbc, path.departmentId(), path.doctorId());
        String schedule = AppointmentAvailability.lockSchedule(jdbc, path.doctorId(), path.scheduleId());
        var slot = lockVisitSlot(target.slotId());
        String status = lockVisitStatus(target.id());
        var now = clock.instant();
        boolean clinicOpen = "ACTIVE".equals(department) && "ACTIVE".equals(doctor) && "OPEN".equals(schedule);

        if (checkIn) {
            if ("CHECKED_IN".equals(status)) return LifecycleResult.accepted("此预约已签到。");
            if (!"BOOKED".equals(status) || !clinicOpen || !"RESERVED".equals(slot.status())) {
                return LifecycleResult.refused("CHECK_IN_UNAVAILABLE", "当前预约无法签到，请核对预约和排班状态。");
            }
            var rule = rules.active();
            if (now.isBefore(rule.checkInFrom(slot.start())) || !now.isBefore(slot.end())) {
                return LifecycleResult.refused("CHECK_IN_OUTSIDE_WINDOW", "签到时间为预约开始前 " +
                        rule.checkInLead() + " 分钟至预约时段结束前。");
            }
            jdbc.update("UPDATE appointment SET status = 'CHECKED_IN', checked_in_at = ? WHERE id = ?",
                    Timestamp.from(now), target.id());
        } else {
            if ("CANCELLED".equals(status)) return LifecycleResult.accepted("此预约已取消。");
            if (!"BOOKED".equals(status)) {
                return LifecycleResult.refused("CANCEL_UNAVAILABLE", "当前预约无法线上取消，请联系分诊台。");
            }
            if (!now.isBefore(rules.active().cancelBefore(slot.date()))) {
                return LifecycleResult.refused("CANCEL_CUTOFF_PASSED", "已过线上取消时间，请联系分诊台。");
            }
            jdbc.update("UPDATE appointment SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                    Timestamp.from(now), target.id());
            releaseVacantSlot(target.slotId(), slot, clinicOpen, now);
        }
        jdbc.update("""
                INSERT INTO operation_audit_log(operation_no, patient_id, operator_type, operator_id,
                    operation_type, target_type, target_id, result_status)
                VALUES (?, ?, ?, ?, ?, 'APPOINTMENT', ?, 'SUCCEEDED')
                """, "AUD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(), target.patientId(),
                checkIn ? "ADMIN" : "PATIENT", actor, checkIn ? "APPOINTMENT_CHECK_IN" : "APPOINTMENT_CANCEL", target.id());
        return LifecycleResult.accepted(checkIn ? "签到成功。" : "预约已取消。");
    }

    private VisitTarget findVisit(String number, Long patientId) {
        String select = "SELECT id, patient_id, slot_id, booked_via FROM appointment WHERE appointment_no = ?";
        return jdbc.query(select + (patientId == null ? "" : " AND patient_id = ?"),
                (row, index) -> new VisitTarget(row.getLong("id"), row.getLong("patient_id"), row.getLong("slot_id"),
                        row.getString("booked_via")),
                patientId == null ? new Object[]{number} : new Object[]{number, patientId}).stream().findFirst().orElse(null);
    }

    private VisitSlot lockVisitSlot(long slotId) {
        return jdbc.queryForObject("""
                SELECT s.status, s.start_at, s.end_at, ds.schedule_date FROM appointment_slot s
                JOIN doctor_schedule ds ON ds.id = s.schedule_id WHERE s.id = ? FOR UPDATE
                """, (row, index) -> new VisitSlot(row.getString("status"), row.getTimestamp("start_at").toInstant(),
                row.getTimestamp("end_at").toInstant(), row.getObject("schedule_date", LocalDate.class)), slotId);
    }

    private String lockVisitStatus(long appointmentId) {
        return jdbc.queryForObject("SELECT status FROM appointment WHERE id = ? FOR UPDATE", String.class, appointmentId);
    }

    private void releaseVacantSlot(long slotId, VisitSlot slot, boolean clinicOpen, Instant now) {
        if ("RESERVED".equals(slot.status()) && jdbc.queryForList("""
                SELECT id FROM appointment WHERE slot_id = ? AND status IN ('BOOKED', 'CHECKED_IN', 'VISITED') FOR UPDATE
                """, Long.class, slotId).isEmpty()) {
            jdbc.update("UPDATE appointment_slot SET status = ?, version = version + 1 WHERE id = ?",
                    clinicOpen && now.isBefore(slot.start()) ? "AVAILABLE" : "CLOSED", slotId);
        }
    }

    private record VisitTarget(long id, long patientId, long slotId, String bookedVia) {}
    private record VisitSlot(String status, Instant start, Instant end, LocalDate date) {}

    public BookingResult reschedule(RescheduleCommand command) {
        try {
            return transactions.execute(tx -> rescheduleInTransaction(command));
        } catch (RuntimeException failure) {
            try {
                failureAuditTransactions.executeWithoutResult(tx -> {
                    var original = findVisit(command.appointmentNo(), command.patientId());
                    if (original != null) auditReschedule(command, original.id(), "FAILED", "INTERNAL_ERROR", null);
                });
            } catch (RuntimeException auditFailure) {
                logger.warn("Could not audit failed reschedule: patient={} appointment={}",
                        command.patientId(), command.appointmentNo(), auditFailure);
            }
            throw failure;
        }
    }

    public Optional<BookingResult> lookupReschedule(RescheduleCommand command) {
        var original = findVisit(command.appointmentNo(), command.patientId());
        return original == null ? Optional.of(BookingResult.failure("NOT_FOUND", "找不到这笔预约。"))
                : recordedReschedule(command, original.id());
    }

    private BookingResult rescheduleInTransaction(RescheduleCommand command) {
        AppointmentAvailability.lockPatient(jdbc, command.patientId());
        var original = findVisit(command.appointmentNo(), command.patientId());
        if (original == null) return BookingResult.failure("NOT_FOUND", "找不到这笔预约。");
        var previous = recordedReschedule(command, original.id());
        if (previous.isPresent()) return previous.get();

        var oldPath = findSlotPath(original.slotId());
        var newPath = findSlotPath(command.slotId());
        String department = AppointmentAvailability.lockDepartment(jdbc, oldPath.departmentId());
        String doctor = AppointmentAvailability.lockDoctor(jdbc, oldPath.departmentId(), oldPath.doctorId());
        String oldSchedule = AppointmentAvailability.lockSchedule(jdbc, oldPath.doctorId(), oldPath.scheduleId());
        boolean sameDoctor = newPath != null && oldPath.doctorId() == newPath.doctorId()
                && oldPath.departmentId() == newPath.departmentId();
        // Both schedules are protected by the same doctor lock, also taken by booking and schedule closure.
        String newSchedule = sameDoctor ? AppointmentAvailability.lockSchedule(jdbc, oldPath.doctorId(), newPath.scheduleId()) : null;
        var oldSlot = lockVisitSlot(original.slotId());
        var newSlot = "OPEN".equals(newSchedule) && command.slotId() != original.slotId()
                ? lockVisitSlot(command.slotId()) : null;
        String status = lockVisitStatus(original.id());
        Instant now = clock.instant();
        if (!"BOOKED".equals(status) || !"RESERVED".equals(oldSlot.status()) || !now.isBefore(oldSlot.end())) {
            return rejectReschedule(command, original.id(), "ORIGINAL_UNAVAILABLE");
        }
        if (!now.isBefore(rules.active().cancelBefore(oldSlot.date()))) {
            return rejectReschedule(command, original.id(), "RESCHEDULE_CUTOFF_PASSED");
        }
        if (command.slotId() == original.slotId()) return rejectReschedule(command, original.id(), "SAME_SLOT");
        if (newPath != null && !sameDoctor) return rejectReschedule(command, original.id(), "DIFFERENT_DOCTOR");
        var today = SlotQueryService.hospitalDate(now);
        if (newSlot == null || !"ACTIVE".equals(department) || !"ACTIVE".equals(doctor)
                || newSlot.date().isBefore(today) || newSlot.date().isAfter(rules.active().patientEnd(today))
                || !now.isBefore(newSlot.start())) {
            return rejectReschedule(command, original.id(), "SLOT_NOT_FOUND");
        }
        if (!reserveSlot(command.slotId())) return rejectReschedule(command, original.id(), "SLOT_UNAVAILABLE");

        String replacement = newAppointmentNumber();
        jdbc.update("""
                INSERT INTO appointment(appointment_no, patient_id, department_id, doctor_id, slot_id,
                                        booked_via, status, idempotency_key, confirmed_version, booked_at, rescheduled_from_id)
                VALUES (?, ?, ?, ?, ?, ?, 'BOOKED', ?, 0, ?, ?)
                """, replacement, command.patientId(), oldPath.departmentId(), oldPath.doctorId(),
                command.slotId(), original.bookedVia(), "RESCHEDULE-" + UUID.randomUUID(), Timestamp.from(now), original.id());
        jdbc.update("UPDATE appointment SET status = 'RESCHEDULED' WHERE id = ?", original.id());
        releaseVacantSlot(original.slotId(), oldSlot, "OPEN".equals(oldSchedule), now);
        auditReschedule(command, original.id(), "SUCCEEDED", null, command.idempotencyKey());
        return new BookingResult(BookingResult.Outcome.CREATED, replacement, null, "改约成功。");
    }

    private Optional<BookingResult> recordedReschedule(RescheduleCommand command, long originalId) {
        return jdbc.query("""
                SELECT audit.target_id = ? AND audit.request_digest = SHA2(CONCAT(?, ':', ?), 256) AS matches_request,
                       audit.result_code, replacement.appointment_no
                FROM operation_audit_log audit
                LEFT JOIN appointment replacement ON replacement.rescheduled_from_id = audit.target_id
                WHERE audit.patient_id = ? AND audit.operation_type = 'APPOINTMENT_RESCHEDULE'
                  AND audit.idempotency_key = ?
                ORDER BY audit.id LIMIT 1
                """, (row, index) -> {
                    if (!row.getBoolean("matches_request")) return keyConflict();
                    String code = row.getString("result_code");
                    return code != null ? rescheduleRejection(code)
                            : new BookingResult(BookingResult.Outcome.EXISTING, row.getString("appointment_no"), null, "已核验这次改约。");
                }, originalId, originalId, command.slotId(), command.patientId(), command.idempotencyKey()).stream().findFirst();
    }

    private BookingResult rejectReschedule(RescheduleCommand command, long originalId, String code) {
        auditReschedule(command, originalId, "REJECTED", code, command.idempotencyKey());
        return rescheduleRejection(code);
    }

    static BookingResult rescheduleRejection(String code) {
        return BookingResult.failure(code, switch (code) {
            case "ORIGINAL_UNAVAILABLE" -> "原预约状态已变化，请返回详情核对。";
            case "RESCHEDULE_CUTOFF_PASSED" -> "已过线上改约时间。";
            case "SAME_SLOT" -> "请选择与原预约不同的时段。";
            case "DIFFERENT_DOCTOR" -> "请选择原科室、原医生的号源。";
            case "SLOT_UNAVAILABLE" -> "该时段已被预约，本次改约未变更原预约。";
            case "SLOT_NOT_FOUND" -> "所选时段已不可预约，本次改约未变更原预约。";
            default -> "系统处理失败，本次改约未变更原预约。";
        });
    }

    private void auditReschedule(RescheduleCommand command, long originalId, String result, String code, String key) {
        jdbc.update("""
                INSERT INTO operation_audit_log(operation_no, patient_id, operator_type, operator_id, operation_type,
                                                target_type, target_id, idempotency_key, result_status, result_code, request_digest)
                VALUES (?, ?, 'PATIENT', ?, 'APPOINTMENT_RESCHEDULE', 'APPOINTMENT', ?, ?, ?, ?, SHA2(CONCAT(?, ':', ?), 256))
                """, UUID.randomUUID().toString(), command.patientId(), command.patientId(), originalId, key,
                result, code, originalId, command.slotId());
    }

    public VerificationResult verify(String appointmentNo, long patientId) {
        if (appointmentNo == null || appointmentNo.isBlank() || patientId <= 0) {
            return new VerificationResult(false, "预约核验参数无效", null);
        }
        String status = jdbc.queryForList("""
                SELECT status FROM appointment WHERE appointment_no = ? AND patient_id = ?
                """, String.class, appointmentNo, patientId).stream().findFirst().orElse(null);
        if (status == null) return new VerificationResult(false, "未找到预约记录", null);
        if ("CANCELLED".equals(status)) return new VerificationResult(false, "原预约已取消。", "APPOINTMENT_CANCELLED");
        if ("RESCHEDULED".equals(status)) {
            return new VerificationResult(false, "原预约已改约，可从原预约详情查看新预约。", "APPOINTMENT_RESCHEDULED");
        }
        if ("INTERRUPTED".equals(status)) {
            return new VerificationResult(false, "原排班已停诊，请查看预约详情中的院方安排。", "APPOINTMENT_INTERRUPTED");
        }
        return "BOOKED".equals(status) || "CHECKED_IN".equals(status) || "VISITED".equals(status)
                ? new VerificationResult(true, "预约核验成功", null)
                : new VerificationResult(false, "当前预约状态无法核验", null);
    }

    /** Reads the outcome of the exact original intent. This path never reserves or audits a new attempt. */
    public Optional<BookingResult> lookup(BookingCommand command) {
        var original = jdbc.query("""
                SELECT a.appointment_no, a.booked_via, a.slot_id, p.plan_no, a.confirmed_version, a.status
                FROM appointment a LEFT JOIN agent_plan p ON p.id = a.plan_id
                WHERE a.patient_id = ? AND a.idempotency_key = ?
                """, (row, index) -> new IntentRow(row.getString("appointment_no"),
                row.getString("booked_via"), row.getLong("slot_id"), row.getString("plan_no"),
                row.getInt("confirmed_version"), null, row.getString("status")), command.actor().userId(), command.idempotencyKey());
        if (!original.isEmpty()) {
            IntentRow row = original.get(0);
            return Optional.of(row.matches(command) ? BookingResult.replay(row.appointmentNo(), row.status()) : keyConflict());
        }
        return Optional.ofNullable(recordedRejection(command));
    }

    /** Older Agent checkpoints may lack the key. Only an appointment attached to that exact plan qualifies. */
    public Optional<BookingResult> lookupPlan(long patientId, PlanClaim plan) {
        var rows = jdbc.query("""
                SELECT a.appointment_no, a.status FROM appointment a JOIN agent_plan p ON p.id = a.plan_id
                WHERE a.patient_id = ? AND a.booked_via = 'AGENT' AND p.plan_no = ?
                  AND a.confirmed_version = ? AND p.version = ?
                LIMIT 2
                """, (row, index) -> BookingResult.replay(row.getString("appointment_no"), row.getString("status")),
                patientId, plan.planNo(), plan.version(), plan.version());
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    private BookingResult recordedRejection(BookingCommand command) {
        return jdbc.query("""
                SELECT audit.target_id, audit.result_code, p.plan_no, p.version
                FROM operation_audit_log audit LEFT JOIN agent_plan p ON p.id = audit.plan_id
                WHERE audit.patient_id = ? AND audit.idempotency_key = ?
                  AND audit.operation_type = 'APPOINTMENT_CREATE' AND audit.result_status = 'REJECTED'
                  AND audit.result_code NOT IN ('IDEMPOTENCY_CONFLICT', 'PLAN_NOT_FOUND')
                ORDER BY audit.id LIMIT 1
                """, (row, index) -> new IntentRow(null, row.getString("plan_no") == null ? "PORTAL" : "AGENT",
                row.getLong("target_id"), row.getString("plan_no"), row.getInt("version"),
                row.getString("result_code"), null), command.actor().userId(), command.idempotencyKey()).stream()
                .findFirst().map(row -> row.matches(command)
                        ? BookingResult.failure(row.code(), "原预约未提交：" + rejectionMessage(row.code()))
                        : keyConflict()).orElse(null);
    }

    private static BookingResult keyConflict() {
        return BookingResult.failure("IDEMPOTENCY_CONFLICT", "原请求标识已用于其他预约方案或号源。");
    }

    private static String rejectionMessage(String code) {
        return switch (code) {
            case "SLOT_UNAVAILABLE" -> "号源已被占用，请重新选择。";
            case "SLOT_INVALID", "SLOT_NOT_FOUND" -> "号源已失效，请重新查询。";
            case "PLAN_EXPIRED" -> "方案已过期，请重新查询。";
            default -> "预约条件未通过校验。";
        };
    }

    private record IntentRow(String appointmentNo, String source, long slotId, String planNo, int version, String code, String status) {
        boolean matches(BookingCommand command) {
            return source.equals(command.source().name()) && (command.plan() == null
                    ? Objects.equals(slotId, command.slotId())
                    : command.plan().planNo().equals(planNo) && command.plan().version() == version);
        }
    }

    private BookingResult bookInTransaction(BookingCommand command) {
        // Same patient and key must observe the first outcome even when two different slots are sent.
        AppointmentAvailability.lockPatient(jdbc, command.actor().userId());
        if (command.plan() != null) {
            PlanRow plan = findPlanForUpdate(command);
            if (plan == null) {
                return reject(command, "PLAN_NOT_FOUND", "预约方案不存在或不属于当前用户", null, null);
            }
            BookingResult existing = findExisting(command, plan.id(), null);
            if (existing != null) {
                return existing;
            }
            BookingResult recorded = recordedRejection(command);
            if (recorded != null) return recorded;
            if (!PlanStatus.CONFIRMED.name().equals(plan.status())) {
                return reject(command, "PLAN_NOT_CONFIRMED", "预约方案尚未完成确认",
                        plan.id(), plan.slotId());
            }
            if (!plan.expiresAt().isAfter(Instant.now())) {
                return reject(command, "PLAN_EXPIRED", "预约方案已过期，请重新查询号源",
                        plan.id(), plan.slotId());
            }
            SlotRow slot = findSlotForUpdate(plan.slotId());
            if (slot == null || slot.doctorId() != plan.doctorId()
                    || slot.departmentId() != plan.departmentId()) {
                return reject(command, "SLOT_INVALID", "预约方案对应的号源已失效", plan.id(), plan.slotId());
            }
            return reserveAndWrite(command, slot, plan.id(), plan.version());
        }

        BookingResult existing = findExisting(command, null, command.slotId());
        if (existing != null) {
            return existing;
        }
        BookingResult recorded = recordedRejection(command);
        if (recorded != null) return recorded;
        SlotRow slot = findSlotForUpdate(command.slotId());
        if (slot == null) {
            return reject(command, "SLOT_NOT_FOUND", "号源不存在或已被关闭", null, command.slotId());
        }
        return reserveAndWrite(command, slot, null, 0);
    }

    /**
     * Returns a business refusal and records it, so a patient asking "why did my booking not go
     * through" can be answered. The record shares the caller's transaction, which commits because a
     * refusal is a result rather than an exception.
     */
    private BookingResult reject(BookingCommand command, String code, String message,
                                Long planRowId, Long slotId) {
        insertAudit(command, "REJECTED", code, slotId == null ? "APPOINTMENT" : "APPOINTMENT_SLOT",
                slotId, planRowId);
        return BookingResult.failure(code, message);
    }

    /** Reserves the already locked slot and writes the appointment inside the caller's transaction. */
    private BookingResult reserveAndWrite(BookingCommand command, SlotRow slot,
                                          Long planRowId, int confirmedVersion) {
        if (!"AVAILABLE".equals(slot.status()) || !reserveSlot(slot.id())) {
            return reject(command, "SLOT_UNAVAILABLE", "号源已被占用", planRowId, slot.id());
        }

        String appointmentNo = newAppointmentNumber();
        jdbc.update("""
                INSERT INTO appointment(appointment_no, patient_id, department_id, doctor_id, slot_id,
                                       plan_id, booked_via, status, idempotency_key, confirmed_version, booked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'BOOKED', ?, ?, UTC_TIMESTAMP(3))
                """, appointmentNo, command.actor().userId(), slot.departmentId(), slot.doctorId(),
                slot.id(), planRowId, command.source().name(), command.idempotencyKey(), confirmedVersion);
        Long appointmentId = jdbc.queryForObject(
                "SELECT id FROM appointment WHERE appointment_no = ?", Long.class, appointmentNo);
        if (planRowId != null) {
            jdbc.update("UPDATE agent_plan SET status = 'EXECUTED' WHERE id = ? AND status = 'CONFIRMED'",
                    planRowId);
        }
        insertAudit(command, "SUCCEEDED", null, "APPOINTMENT", appointmentId, planRowId);
        return BookingResult.created(appointmentNo);
    }

    private boolean reserveSlot(long slotId) {
        return jdbc.update("""
                UPDATE appointment_slot SET status = 'RESERVED', version = version + 1
                WHERE id = ? AND status = 'AVAILABLE'
                """, slotId) == 1;
    }

    private static String newAppointmentNumber() {
        return "APT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /**
     * Writes one audit row. {@code target} names what the operation acted on: the created appointment
     * on success, or the slot the patient tried to book when the operation was refused.
     */
    private void insertAudit(BookingCommand command, String resultStatus, String resultCode,
                             String targetType, Long targetId, Long planRowId) {
        jdbc.update("""
                INSERT INTO operation_audit_log(operation_no, patient_id, operator_type, operator_id,
                                                operation_type, target_type, target_id, plan_id,
                                                idempotency_key, result_status, result_code)
                VALUES (?, ?, ?, ?, 'APPOINTMENT_CREATE', ?, ?, ?, ?, ?, ?)
                """, "AUD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                command.actor().userId(), command.actor().type(), command.actor().userId(),
                targetType, targetId, planRowId, command.idempotencyKey(), resultStatus, resultCode);
    }

    /**
     * Records an unexpected failure in its own transaction, because the booking transaction is
     * already rolling back. The record is best effort: a problem while auditing must not replace the
     * original error the caller needs to see.
     */
    private void auditUnexpectedFailure(BookingCommand command, RuntimeException cause) {
        String target = command.slotId() == null ? "APPOINTMENT" : "APPOINTMENT_SLOT";
        try {
            failureAuditTransactions.executeWithoutResult(status -> insertAudit(command,
                    "FAILED", "INTERNAL_ERROR", target, command.slotId(), null));
        } catch (RuntimeException auditFailure) {
            // The audit row shares patient_id and idempotency_key with the appointment row, so a
            // failure caused by one of those columns cannot be audited either. The application log
            // is then the only record, which is why it carries the identifying fields.
            logger.warn("could not record a failed appointment attempt: status=FAILED code=INTERNAL_ERROR "
                            + "patient={} target={} targetId={} cause={}",
                    command.actor().userId(), target, command.slotId(), cause.toString(), auditFailure);
        }
    }

    private BookingResult resolveDuplicate(BookingCommand command, DuplicateKeyException duplicate) {
        Long planRowId = null;
        Long slotId = command.slotId();
        if (command.plan() != null) {
            PlanRow plan = findPlanForUpdate(command);
            if (plan == null) {
                return reject(command, "PLAN_NOT_FOUND", "预约方案不存在或不属于当前用户", null, null);
            }
            planRowId = plan.id();
            slotId = null;
        }
        BookingResult existing = findExisting(command, planRowId, slotId);
        if (existing == null) {
            throw duplicate;
        }
        return existing;
    }

    /**
     * Idempotent replay lookup. An agent booking is tied to its plan, a portal booking to its slot,
     * so reusing a key for a different target is reported instead of silently returning the first one.
     */
    private BookingResult findExisting(BookingCommand command, Long planRowId, Long slotId) {
        List<ExistingRow> existing = jdbc.query("""
                SELECT appointment_no, plan_id, slot_id, booked_via, status
                FROM appointment
                WHERE patient_id = ? AND idempotency_key = ?
                """, (result, rowNum) -> new ExistingRow(
                result.getString("appointment_no"), result.getLong("plan_id"), result.getLong("slot_id"),
                result.getString("booked_via"), result.getString("status")),
                command.actor().userId(), command.idempotencyKey());
        if (existing.isEmpty()) {
            return null;
        }
        ExistingRow row = existing.get(0);
        if (!command.source().name().equals(row.source())) return keyConflict();
        if (planRowId != null && row.planId() != planRowId) {
            return reject(command, "IDEMPOTENCY_CONFLICT", "幂等键已用于其他预约方案", planRowId, null);
        }
        if (planRowId == null && slotId != null && row.slotId() != slotId) {
            return reject(command, "IDEMPOTENCY_CONFLICT", "幂等键已用于其他号源", null, slotId);
        }
        return BookingResult.replay(row.appointmentNo(), row.status());
    }

    private PlanRow findPlanForUpdate(BookingCommand command) {
        return jdbc.query("""
                SELECT id, patient_id, department_id, doctor_id, slot_id, version, status, expires_at
                FROM agent_plan
                WHERE plan_no = ? AND patient_id = ? AND version = ?
                FOR UPDATE
                """, (result, rowNum) -> new PlanRow(
                result.getLong("id"), result.getLong("patient_id"), result.getLong("department_id"),
                result.getLong("doctor_id"), result.getLong("slot_id"), result.getInt("version"),
                result.getString("status"), result.getTimestamp("expires_at").toInstant()),
                command.plan().planNo(), command.actor().userId(), command.plan().version())
                .stream().findFirst().orElse(null);
    }

    private SlotRow findSlotForUpdate(Long slotId) {
        if (slotId == null) {
            return null;
        }
        if (!rules.bookingEnabled()) return null;
        var path = findSlotPath(slotId);
        if (path == null
                || !"ACTIVE".equals(AppointmentAvailability.lockDepartment(jdbc, path.departmentId()))
                || !"ACTIVE".equals(AppointmentAvailability.lockDoctor(jdbc, path.departmentId(), path.doctorId()))
                || !"OPEN".equals(AppointmentAvailability.lockSchedule(jdbc, path.doctorId(), path.scheduleId()))) {
            return null;
        }
        return jdbc.query("""
                SELECT id, status
                FROM appointment_slot
                WHERE id = ? AND schedule_id = ? AND start_at > UTC_TIMESTAMP(3)
                  AND EXISTS (SELECT 1 FROM doctor_schedule ds WHERE ds.id=appointment_slot.schedule_id AND ds.schedule_date<=?)
                FOR UPDATE
                """, (result, rowNum) -> new SlotRow(
                result.getLong("id"), result.getString("status"), path.doctorId(), path.departmentId()), slotId, path.scheduleId(),
                rules.active().patientEnd(SlotQueryService.hospitalDate(clock.instant())))
                .stream().findFirst().orElse(null);
    }

    private SlotPath findSlotPath(long slotId) {
        return jdbc.query("""
                SELECT ds.id AS schedule_id, ds.doctor_id, d.department_id
                FROM appointment_slot s
                JOIN doctor_schedule ds ON ds.id = s.schedule_id
                JOIN doctor d ON d.id = ds.doctor_id
                WHERE s.id = ?
                """, (row, index) -> new SlotPath(row.getLong("schedule_id"), row.getLong("doctor_id"),
                row.getLong("department_id")), slotId).stream().findFirst().orElse(null);
    }

    private record PlanRow(long id, long patientId, long departmentId, long doctorId, long slotId,
                           int version, String status, Instant expiresAt) {
    }

    private record SlotRow(long id, String status, long doctorId, long departmentId) {
    }

    private record SlotPath(long scheduleId, long doctorId, long departmentId) {}

    private record ExistingRow(String appointmentNo, long planId, long slotId, String source, String status) {
    }
}
