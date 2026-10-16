package com.mediflow.agent.appointment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.mediflow.agent.agent.PlanStatus;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
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

    public AppointmentWriteService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public BookingResult book(BookingCommand command) {
        try {
            return transactions.execute(status -> bookInTransaction(command));
        } catch (DuplicateKeyException duplicate) {
            return transactions.execute(status -> resolveDuplicate(command, duplicate));
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
                return BookingResult.failure("PLAN_NOT_FOUND", "预约方案不存在或不属于当前用户");
            }
            BookingResult existing = findExisting(command, plan.id(), null);
            if (existing != null) {
                return existing;
            }
            if (!PlanStatus.CONFIRMED.name().equals(plan.status())) {
                return BookingResult.failure("PLAN_NOT_CONFIRMED", "预约方案尚未完成确认");
            }
            if (!plan.expiresAt().isAfter(Instant.now())) {
                return BookingResult.failure("PLAN_EXPIRED", "预约方案已过期，请重新查询号源");
            }
            SlotRow slot = findSlotForUpdate(plan.slotId());
            if (slot == null || slot.doctorId() != plan.doctorId()
                    || slot.departmentId() != plan.departmentId()) {
                return BookingResult.failure("SLOT_INVALID", "预约方案对应的号源已失效");
            }
            return reserveAndWrite(command, slot, plan.id(), plan.version());
        }

        BookingResult existing = findExisting(command, null, command.slotId());
        if (existing != null) {
            return existing;
        }
        SlotRow slot = findSlotForUpdate(command.slotId());
        if (slot == null) {
            return BookingResult.failure("SLOT_NOT_FOUND", "号源不存在或已被关闭");
        }
        return reserveAndWrite(command, slot, null, 0);
    }

    /** Reserves the already locked slot and writes the appointment inside the caller's transaction. */
    private BookingResult reserveAndWrite(BookingCommand command, SlotRow slot,
                                          Long planRowId, int confirmedVersion) {
        if (!"AVAILABLE".equals(slot.status())) {
            return BookingResult.failure("SLOT_UNAVAILABLE", "号源已被占用");
        }
        int reserved = jdbc.update("""
                UPDATE appointment_slot SET status = 'RESERVED', version = version + 1
                WHERE id = ? AND status = 'AVAILABLE'
                """, slot.id());
        if (reserved != 1) {
            return BookingResult.failure("SLOT_UNAVAILABLE", "号源已被占用");
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
        jdbc.update("""
                INSERT INTO operation_audit_log(operation_no, patient_id, operator_type, operator_id,
                                                operation_type, target_type, target_id, plan_id,
                                                idempotency_key, result_status, result_code)
                VALUES (?, ?, ?, ?, 'APPOINTMENT_CREATE', 'APPOINTMENT', ?, ?, ?, 'SUCCEEDED', NULL)
                """, "AUD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                command.actor().userId(), command.actor().type(), command.actor().userId(),
                appointmentId, planRowId, command.idempotencyKey());
        return BookingResult.created(appointmentNo);
    }

    private BookingResult resolveDuplicate(BookingCommand command, DuplicateKeyException duplicate) {
        Long planRowId = null;
        Long slotId = command.slotId();
        if (command.plan() != null) {
            PlanRow plan = findPlanForUpdate(command);
            if (plan == null) {
                return BookingResult.failure("PLAN_NOT_FOUND", "预约方案不存在或不属于当前用户");
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
            return BookingResult.failure("IDEMPOTENCY_CONFLICT", "幂等键已用于其他预约方案");
        }
        if (planRowId == null && slotId != null && row.slotId() != slotId) {
            return BookingResult.failure("IDEMPOTENCY_CONFLICT", "幂等键已用于其他号源");
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
