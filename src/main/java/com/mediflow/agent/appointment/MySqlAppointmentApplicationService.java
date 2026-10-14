package com.mediflow.agent.appointment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.mediflow.agent.agent.AppointmentApplicationService;
import com.mediflow.agent.agent.AppointmentExecutionResult;
import com.mediflow.agent.agent.AppointmentPlan;
import com.mediflow.agent.agent.AppointmentVerificationResult;
import com.mediflow.agent.agent.PlanStatus;
import com.mediflow.agent.agent.Principal;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes the confirmed appointment command; it is never exposed as an LLM tool. */
@Service
@Profile("mysql")
public final class MySqlAppointmentApplicationService implements AppointmentApplicationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MySqlAppointmentApplicationService(DataSource dataSource,
                                              PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public AppointmentExecutionResult createConfirmedAppointment(
            AppointmentPlan plan, Principal principal, String idempotencyKey) {
        if (plan == null || principal == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("appointment confirmation is incomplete");
        }
        try {
            return transactions.execute(status -> createInTransaction(plan, principal, idempotencyKey));
        } catch (DuplicateKeyException duplicate) {
            return transactions.execute(status -> resolveDuplicate(plan, principal, idempotencyKey, duplicate));
        }
    }

    @Override
    public AppointmentVerificationResult verify(String appointmentId, Principal principal) {
        if (appointmentId == null || appointmentId.isBlank() || principal == null) {
            return new AppointmentVerificationResult(false, "预约核验参数无效");
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM appointment
                WHERE appointment_no = ? AND patient_id = ? AND status = 'BOOKED'
                """, Integer.class, appointmentId, principal.userId());
        return count != null && count == 1
                ? new AppointmentVerificationResult(true, "预约核验成功")
                : new AppointmentVerificationResult(false, "未找到预约记录");
    }

    private AppointmentExecutionResult createInTransaction(
            AppointmentPlan plan, Principal principal, String idempotencyKey) {
        PlanRow savedPlan = findPlanForUpdate(plan, principal);
        if (savedPlan == null) {
            return AppointmentExecutionResult.failure("PLAN_NOT_FOUND", "预约方案不存在或不属于当前用户");
        }
        AppointmentExecutionResult existing = findExistingResult(plan, principal, idempotencyKey, savedPlan.id());
        if (existing != null) {
            return existing;
        }
        if (!PlanStatus.CONFIRMED.name().equals(savedPlan.status())) {
            return AppointmentExecutionResult.failure("PLAN_NOT_CONFIRMED", "预约方案尚未完成确认");
        }
        if (!savedPlan.expiresAt().isAfter(Instant.now())) {
            return AppointmentExecutionResult.failure("PLAN_EXPIRED", "预约方案已过期，请重新查询号源");
        }

        SlotRow slot = jdbc.query("""
                SELECT s.id, s.status, ds.doctor_id, d.department_id
                FROM appointment_slot s
                JOIN doctor_schedule ds ON ds.id = s.schedule_id
                JOIN doctor d ON d.id = ds.doctor_id
                WHERE s.id = ?
                FOR UPDATE
                """, (result, rowNum) -> new SlotRow(
                result.getLong("id"), result.getString("status"),
                result.getLong("doctor_id"), result.getLong("department_id")), savedPlan.slotId())
                .stream().findFirst().orElse(null);
        if (slot == null || slot.doctorId() != savedPlan.doctorId()
                || slot.departmentId() != savedPlan.departmentId()) {
            return AppointmentExecutionResult.failure("SLOT_INVALID", "预约方案对应的号源已失效");
        }
        if (!"AVAILABLE".equals(slot.status())) {
            return AppointmentExecutionResult.failure("SLOT_UNAVAILABLE", "号源已被占用");
        }
        int reserved = jdbc.update("""
                UPDATE appointment_slot SET status = 'RESERVED', version = version + 1
                WHERE id = ? AND status = 'AVAILABLE'
                """, savedPlan.slotId());
        if (reserved != 1) {
            return AppointmentExecutionResult.failure("SLOT_UNAVAILABLE", "号源已被占用");
        }

        String appointmentNo = "APT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        jdbc.update("""
                INSERT INTO appointment(appointment_no, patient_id, department_id, doctor_id, slot_id,
                                       plan_id, status, idempotency_key, confirmed_version, booked_at)
                VALUES (?, ?, ?, ?, ?, ?, 'BOOKED', ?, ?, UTC_TIMESTAMP(3))
                """, appointmentNo, principal.userId(), savedPlan.departmentId(), savedPlan.doctorId(),
                savedPlan.slotId(), savedPlan.id(), idempotencyKey, savedPlan.version());
        Long appointmentId = jdbc.queryForObject(
                "SELECT id FROM appointment WHERE appointment_no = ?", Long.class, appointmentNo);
        jdbc.update("UPDATE agent_plan SET status = 'EXECUTED' WHERE id = ? AND status = 'CONFIRMED'",
                savedPlan.id());
        jdbc.update("""
                INSERT INTO operation_audit_log(operation_no, patient_id, operator_type, operator_id,
                                                operation_type, target_type, target_id, plan_id,
                                                idempotency_key, result_status, result_code)
                VALUES (?, ?, ?, ?, 'APPOINTMENT_CREATE', 'APPOINTMENT', ?, ?, ?, 'SUCCEEDED', NULL)
                """, "AUD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                principal.userId(), principal.role(), principal.userId(), appointmentId, savedPlan.id(),
                idempotencyKey);
        return AppointmentExecutionResult.success(appointmentNo);
    }

    private AppointmentExecutionResult findExistingResult(
            AppointmentPlan plan, Principal principal, String idempotencyKey) {
        return transactions.execute(status -> findExistingResult(plan, principal, idempotencyKey, null));
    }

    private AppointmentExecutionResult resolveDuplicate(AppointmentPlan plan, Principal principal,
                                                        String idempotencyKey,
                                                        DuplicateKeyException duplicate) {
        PlanRow savedPlan = findPlanForUpdate(plan, principal);
        if (savedPlan == null) {
            return AppointmentExecutionResult.failure("PLAN_NOT_FOUND", "预约方案不存在或不属于当前用户");
        }
        AppointmentExecutionResult existing = findExistingResult(plan, principal, idempotencyKey, savedPlan.id());
        if (existing == null) {
            throw duplicate;
        }
        return existing;
    }

    private AppointmentExecutionResult findExistingResult(
            AppointmentPlan plan, Principal principal, String idempotencyKey, Long planRowId) {
        List<ExistingRow> existing = jdbc.query("""
                SELECT a.appointment_no, a.plan_id
                FROM appointment a
                WHERE a.patient_id = ? AND a.idempotency_key = ?
                """, (result, rowNum) -> new ExistingRow(
                result.getString("appointment_no"), result.getLong("plan_id")),
                principal.userId(), idempotencyKey);
        if (existing.isEmpty()) {
            return null;
        }
        ExistingRow row = existing.get(0);
        if (planRowId != null && row.planId() != planRowId) {
            return AppointmentExecutionResult.failure("IDEMPOTENCY_CONFLICT", "幂等键已用于其他预约方案");
        }
        return AppointmentExecutionResult.existing(row.appointmentNo());
    }

    private PlanRow findPlanForUpdate(AppointmentPlan plan, Principal principal) {
        return jdbc.query("""
                SELECT id, patient_id, department_id, doctor_id, slot_id, version, status, expires_at
                FROM agent_plan
                WHERE plan_no = ? AND patient_id = ? AND version = ?
                FOR UPDATE
                """, (result, rowNum) -> new PlanRow(
                result.getLong("id"), result.getLong("patient_id"), result.getLong("department_id"),
                result.getLong("doctor_id"), result.getLong("slot_id"), result.getInt("version"),
                result.getString("status"), result.getTimestamp("expires_at").toInstant()),
                plan.id().value(), principal.userId(), plan.version())
                .stream().findFirst().orElse(null);
    }

    private record PlanRow(long id, long patientId, long departmentId, long doctorId, long slotId,
                           int version, String status, Instant expiresAt) { }

    private record SlotRow(long id, String status, long doctorId, long departmentId) { }

    private record ExistingRow(String appointmentNo, long planId) { }
}
