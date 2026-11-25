package com.mediflow.agent.appointment;

import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One bookable slot with its catalog rows already resolved. */
public record AvailableSlot(long slotId, long departmentId, String departmentCode, String departmentName,
                            long doctorId, String doctorName, String doctorTitle,
                            LocalDate appointmentDate, String clinicPeriod, String timeRange) {
    @JsonProperty
    public Instant cancellationDeadline() {
        return AppointmentTiming.cancellationDeadline(appointmentDate);
    }
}
