package com.mediflow.agent.agent;

public interface AppointmentApplicationService {
    AppointmentExecutionResult createConfirmedAppointment(
            AppointmentPlan plan, Principal principal, String idempotencyKey);

    AppointmentVerificationResult verify(String appointmentId, Principal principal);
}
