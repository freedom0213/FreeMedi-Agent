package com.mediflow.agent.appointment;

import java.time.LocalDate;

/** One bookable slot with its catalog rows already resolved. */
public record AvailableSlot(long slotId, long departmentId, String departmentCode, String departmentName,
                            long doctorId, String doctorName, String doctorTitle,
                            LocalDate appointmentDate, String clinicPeriod, String timeRange) {
}
