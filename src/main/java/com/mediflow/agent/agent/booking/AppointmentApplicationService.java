package com.mediflow.agent.agent.booking;

import com.mediflow.agent.agent.runtime.Principal;
import java.util.Optional;

public interface AppointmentApplicationService {
    AppointmentExecutionResult createConfirmedAppointment(
            AppointmentPlan plan, Principal principal, String idempotencyKey);

    AppointmentVerificationResult verify(String appointmentId, Principal principal);

    default Optional<AppointmentExecutionResult> findOriginal(PlanId planId, int version,
                                                               Principal principal, String idempotencyKey) {
        return Optional.empty();
    }
}
