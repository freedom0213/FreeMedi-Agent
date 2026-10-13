package com.mediflow.agent.agent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAppointmentApplicationService implements AppointmentApplicationService {
    private final Map<String, AppointmentExecutionResult> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Set<Long> reservedSlots = ConcurrentHashMap.newKeySet();

    @Override
    public AppointmentExecutionResult createConfirmedAppointment(
            AppointmentPlan plan, Principal principal, String idempotencyKey) {
        AppointmentExecutionResult previous = byIdempotencyKey.get(idempotencyKey);
        if (previous != null) {
            return previous;
        }
        if (plan.draft().patientId() != principal.userId()) {
            return AppointmentExecutionResult.failure("FORBIDDEN", "预约患者与当前用户不一致");
        }
        if (!reservedSlots.add(plan.draft().slotId())) {
            return AppointmentExecutionResult.failure("SLOT_UNAVAILABLE", "号源已被占用");
        }
        AppointmentExecutionResult result = AppointmentExecutionResult.success(UUID.randomUUID().toString());
        byIdempotencyKey.put(idempotencyKey, result);
        return result;
    }

    @Override
    public AppointmentVerificationResult verify(String appointmentId, Principal principal) {
        boolean exists = byIdempotencyKey.values().stream()
                .anyMatch(result -> appointmentId.equals(result.appointmentId()));
        return exists
                ? new AppointmentVerificationResult(true, "预约核验成功")
                : new AppointmentVerificationResult(false, "未找到预约记录");
    }

    public void occupySlot(long slotId) {
        reservedSlots.add(slotId);
    }

    public int createdAppointmentCount() {
        return (int) byIdempotencyKey.values().stream()
                .filter(AppointmentExecutionResult::success)
                .count();
    }
}
