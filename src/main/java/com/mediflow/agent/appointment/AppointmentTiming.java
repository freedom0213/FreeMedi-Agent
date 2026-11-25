package com.mediflow.agent.appointment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

final class AppointmentTiming {
    static final int CHECK_IN_LEAD_MINUTES = 30;
    static final LocalTime CANCELLATION_CUTOFF = LocalTime.of(23, 0);
    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");

    private AppointmentTiming() {}

    static Instant cancellationDeadline(LocalDate appointmentDate) {
        return appointmentDate.minusDays(1).atTime(CANCELLATION_CUTOFF).atZone(HOSPITAL_ZONE).toInstant();
    }

    static Instant checkInOpensAt(Instant startAt) {
        return startAt.minus(CHECK_IN_LEAD_MINUTES, ChronoUnit.MINUTES);
    }
}
