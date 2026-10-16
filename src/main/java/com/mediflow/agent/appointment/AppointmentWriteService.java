package com.mediflow.agent.appointment;

import java.time.Instant;
import java.util.List;
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

        static BookingResult failure(String code, String message) {
            return new BookingResult(Outcome.REJECTED, null, code, message);
        }
    }

    public record VerificationResult(boolean success, String message) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    /**
     * Used only when the booking transaction is already rolling back, so the failure record cannot
     * share it. Best effort: it must never replace the original error.
     */
    private final TransactionTemplate failureAuditTransactions;

    public AppointmentWriteService(DataSource dataSource, PlatformTransactionManager transactionManager) {
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

    public VerificationResult verify(String appointmentNo, long patientId) {
        if (appointmentNo == null || appointmentNo.isBlank() || patientId <= 0) {
            return new VerificationResult(false, "预约核验参数无效");
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM appointment
                WHERE appointment_no = ? AND patient_id = ? AND status = 'BOOKED'
                """, Integer.class, appointmentNo, patientId);
        return count != null && count == 1
                ? new VerificationResult(true, "预约核验成功")
                : new VerificationResult(false, "未找到预约记录");
    }

    private BookingResult bookInTransaction(BookingCommand command) {
        if (command.plan() != null) {
            PlanRow plan = findPlanForUpdate(command);
            if (plan == null) {
                return reject(command, "PLAN_NOT_FOUND", "预约方案不存在或不属于当前用户", null, null);
            }
            BookingResult existing = findExisting(command, plan.id(), null);
            if (existing != null) {
                return existing;
            }
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
        if (!"AVAILABLE".equals(slot.status())) {
            return reject(command, "SLOT_UNAVAILABLE", "号源已被占用", planRowId, slot.id());
        }
        int reserved = jdbc.update("""
                UPDATE appointment_slot SET status = 'RESERVED', version = version + 1
                WHERE id = ? AND status = 'AVAILABLE'
                """, slot.id());
        if (reserved != 1) {
            return reject(command, "SLOT_UNAVAILABLE", "号源已被占用", planRowId, slot.id());
        }

        String appointmentNo = "APT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
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
                SELECT appointment_no, plan_id, slot_id
                FROM appointment
                WHERE patient_id = ? AND idempotency_key = ?
                """, (result, rowNum) -> new ExistingRow(
                result.getString("appointment_no"), result.getLong("plan_id"), result.getLong("slot_id")),
                command.actor().userId(), command.idempotencyKey());
        if (existing.isEmpty()) {
            return null;
        }
        ExistingRow row = existing.get(0);
        if (planRowId != null && row.planId() != planRowId) {
            return reject(command, "IDEMPOTENCY_CONFLICT", "幂等键已用于其他预约方案", planRowId, null);
        }
        if (planRowId == null && slotId != null && row.slotId() != slotId) {
            return reject(command, "IDEMPOTENCY_CONFLICT", "幂等键已用于其他号源", null, slotId);
        }
        return BookingResult.existing(row.appointmentNo());
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
        return jdbc.query("""
                SELECT s.id, s.status, ds.doctor_id, d.department_id
                FROM appointment_slot s
                JOIN doctor_schedule ds ON ds.id = s.schedule_id
                JOIN doctor d ON d.id = ds.doctor_id
                WHERE s.id = ?
                FOR UPDATE
                """, (result, rowNum) -> new SlotRow(
                result.getLong("id"), result.getString("status"),
                result.getLong("doctor_id"), result.getLong("department_id")), slotId)
                .stream().findFirst().orElse(null);
    }

    private record PlanRow(long id, long patientId, long departmentId, long doctorId, long slotId,
                           int version, String status, Instant expiresAt) {
    }

    private record SlotRow(long id, String status, long doctorId, long departmentId) {
    }

    private record ExistingRow(String appointmentNo, long planId, long slotId) {
    }
}
