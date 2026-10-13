package com.mediflow.agent.agent;

import java.time.Instant;
import java.time.LocalDate;

public record PlanDraft(
        long patientId,
        long departmentId,
        long doctorId,
        long slotId,
        LocalDate appointmentDate,
        String timeRange,
        Instant expiresAt) {

    public PlanDraft {
        if (patientId <= 0 || departmentId <= 0 || doctorId <= 0 || slotId <= 0) {
            throw new IllegalArgumentException("plan ids must be positive");
        }
        if (appointmentDate == null || timeRange == null || timeRange.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("plan details must be present");
        }
    }
}
