package com.mediflow.agent.appointment;

import com.mediflow.agent.agent.AppointmentApplicationService;
import com.mediflow.agent.agent.AppointmentExecutionResult;
import com.mediflow.agent.agent.AppointmentPlan;
import com.mediflow.agent.agent.AppointmentVerificationResult;
import com.mediflow.agent.agent.Principal;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Agent-facing adapter over {@link AppointmentWriteService}.
 *
 * <p>It only translates the agent's plan and principal into a booking command and maps the result
 * back. Reservation, idempotency and auditing all live in the write service, so the portal booking
 * path cannot diverge from the agent path.
 */
@Service
@Profile("mysql")
public final class MySqlAppointmentApplicationService implements AppointmentApplicationService {
    private final AppointmentWriteService writeService;

    public MySqlAppointmentApplicationService(AppointmentWriteService writeService) {
        this.writeService = writeService;
    }

    @Override
    public AppointmentExecutionResult createConfirmedAppointment(
            AppointmentPlan plan, Principal principal, String idempotencyKey) {
        if (plan == null || principal == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("appointment confirmation is incomplete");
        }
        AppointmentWriteService.BookingResult result = writeService.book(
                AppointmentWriteService.BookingCommand.forPlan(
                        new AppointmentWriteService.Actor(principal.userId(), principal.role()),
                        idempotencyKey, plan.id().value(), plan.version()));
        return switch (result.outcome()) {
            case CREATED -> AppointmentExecutionResult.success(result.appointmentNo());
            case EXISTING -> AppointmentExecutionResult.existing(result.appointmentNo());
            case REJECTED -> AppointmentExecutionResult.failure(result.code(), result.message());
        };
    }

    @Override
    public AppointmentVerificationResult verify(String appointmentId, Principal principal) {
        AppointmentWriteService.VerificationResult result =
                writeService.verify(appointmentId, principal == null ? 0 : principal.userId());
        return new AppointmentVerificationResult(result.success(), result.message());
    }
}
