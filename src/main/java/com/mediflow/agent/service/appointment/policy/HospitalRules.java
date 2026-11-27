package com.mediflow.agent.service.appointment.policy;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Service
@Profile("mysql")
public class HospitalRules {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final Rules DEFAULTS = new Rules(90, 28, LocalTime.of(23, 0), 30);

    public record Rules(int scheduleDays, int patientDays, LocalTime cancellationCutoff, int checkInLead) {
        public LocalDate patientEnd(LocalDate today) { return today.plusDays(patientDays - 1L); }

        public Instant cancelBefore(LocalDate day) {
            return day.minusDays(1).atTime(cancellationCutoff).atZone(ZONE).toInstant();
        }

        public Instant checkInFrom(Instant start) { return start.minus(checkInLead, ChronoUnit.MINUTES); }
    }

    private final JdbcTemplate jdbc;

    public HospitalRules(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Rules active() {
        return jdbc.queryForObject("""
                SELECT schedule_days, patient_days, cancellation_cutoff, check_in_lead FROM hospital_settings WHERE id=1
                """, (row, i) -> new Rules(row.getInt(1), row.getInt(2), row.getTime(3).toLocalTime(), row.getInt(4)));
    }

    public boolean bookingEnabled() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT NOT initial_setup_required OR activated_at IS NOT NULL FROM hospital_settings WHERE id=1", Boolean.class));
    }

    public record PublicProfile(String name, String contact, boolean demo, boolean bookingEnabled) {}

    public PublicProfile publicProfile() {
        return jdbc.queryForObject("""
                SELECT hospital_name,contact,NOT initial_setup_required,NOT initial_setup_required OR activated_at IS NOT NULL
                FROM hospital_settings WHERE id=1
                """, (row, i) -> new PublicProfile(row.getString(1), row.getString(2), row.getBoolean(3), row.getBoolean(4)));
    }
}
