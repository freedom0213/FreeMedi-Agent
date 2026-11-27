package com.mediflow.agent.service.appointment.query;

import java.time.Instant;
import java.time.LocalDate;

/** One bookable slot with its catalog rows already resolved. */
public record AvailableSlot(long slotId, long departmentId, String departmentCode, String departmentName,
                            long doctorId, String doctorName, String doctorTitle,
                            LocalDate appointmentDate, String clinicPeriod, String timeRange,
                            Instant cancellationDeadline) {
}
