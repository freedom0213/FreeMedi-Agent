package com.mediflow.agent.appointment;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads bookable slots for both callers that need them.
 *
 * <p>The two entry points differ in one deliberate way. The agent may relax a date or period
 * preference when nothing matches, because it tells the patient what changed and waits for
 * confirmation. Ordinary registration must not: a patient who picked Tuesday morning is answered
 * "no slots" rather than quietly moved to Thursday afternoon. Both share the same SQL and the same
 * clinical-destination rule, so the two behaviours cannot drift apart.
 */
@Service
@Profile("mysql")
public final class SlotQueryService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    /** How far ahead the synthetic hospital publishes clinics. */
    public static final int SEARCH_HORIZON_DAYS = 90;

    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> PERIODS = List.of("MORNING", "AFTERNOON", "EVENING");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SlotQueryService(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    /** The hospital's current date. Search windows and date parsing both start from here. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(HOSPITAL_ZONE));
    }

    /** Only what the caller asked for: an exact match or nothing. */
    public SlotSearch searchStrict(SlotCriteria criteria) {
        LocalDate today = today();
        LocalDate until = today.plusDays(SEARCH_HORIZON_DAYS);
        return new SlotSearch(query(criteria, today, until), true,
                criteria.appointmentDate(), criteria.clinicPeriod(), until);
    }

    /**
     * Drops the date preference first, then the period preference, when the exact criteria find
     * nothing. The department and doctor are never relaxed: changing the clinical destination is a
     * decision for the patient, not for a fallback query.
     */
    public SlotSearch searchWithFallback(SlotCriteria criteria) {
        LocalDate today = today();
        LocalDate until = today.plusDays(SEARCH_HORIZON_DAYS);
        List<AvailableSlot> slots = query(criteria, today, until);
        boolean exactMatch = !slots.isEmpty();
        if (slots.isEmpty() && (criteria.appointmentDate() != null || criteria.clinicPeriod() != null)) {
            LocalDate from = criteria.appointmentDate() != null && criteria.appointmentDate().isAfter(today)
                    ? criteria.appointmentDate() : today;
            SlotCriteria withoutDate = new SlotCriteria(criteria.departmentId(), criteria.departmentName(),
                    criteria.doctorId(), criteria.doctorName(), null, criteria.clinicPeriod(), criteria.limit());
            slots = query(withoutDate, from, until);
            if (slots.isEmpty() && criteria.clinicPeriod() != null) {
                SlotCriteria withoutPeriod = new SlotCriteria(criteria.departmentId(), criteria.departmentName(),
                        criteria.doctorId(), criteria.doctorName(), null, null, criteria.limit());
                slots = query(withoutPeriod, from, until);
            }
        }
        return new SlotSearch(slots, exactMatch, criteria.appointmentDate(), criteria.clinicPeriod(), until);
    }

    /**
     * Slot vocabulary shared by the tool arguments and the portal query string: accepts the stored
     * codes and the words patients use. Returns null for "no preference".
     */
    public static String normalisePeriod(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalised = value.trim().toUpperCase();
        normalised = switch (normalised) {
            case "上午", "早上" -> "MORNING";
            case "下午" -> "AFTERNOON";
            case "晚上", "晚间" -> "EVENING";
            case "不限", "都可以" -> "";
            default -> normalised;
        };
        if (normalised.isEmpty()) {
            return null;
        }
        if (!PERIODS.contains(normalised)) {
            throw new IllegalArgumentException("没有识别出就诊时段，可以选择上午、下午、晚上，或不限");
        }
        return normalised;
    }

    private List<AvailableSlot> query(SlotCriteria criteria, LocalDate from, LocalDate until) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id AS slot_id, d.id AS department_id, d.code AS department_code,
                       d.name AS department_name, dr.id AS doctor_id, dr.name AS doctor_name,
                       dr.title AS doctor_title, ds.schedule_date, ds.clinic_period,
                       s.start_at, s.end_at
                FROM appointment_slot s
                JOIN doctor_schedule ds ON ds.id = s.schedule_id
                JOIN doctor dr ON dr.id = ds.doctor_id
                JOIN department d ON d.id = dr.department_id
                WHERE s.status = 'AVAILABLE'
                  AND ds.status = 'OPEN'
                  AND dr.status = 'ACTIVE'
                  AND d.status = 'ACTIVE'
                  AND s.start_at > ?
                  AND ds.schedule_date BETWEEN ? AND ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(Instant.now(clock)));
        params.add(from);
        params.add(until);
        if (criteria.departmentId() != null) {
            sql.append(" AND d.id = ?");
            params.add(criteria.departmentId());
        }
        if (criteria.doctorId() != null) {
            sql.append(" AND dr.id = ?");
            params.add(criteria.doctorId());
        }
        if (criteria.departmentName() != null) {
            sql.append(" AND (d.name = ? OR d.code = ?)");
            params.add(criteria.departmentName());
            params.add(criteria.departmentName());
        }
        if (criteria.doctorName() != null) {
            sql.append(" AND dr.name = ?");
            params.add(criteria.doctorName());
        }
        if (criteria.appointmentDate() != null) {
            sql.append(" AND ds.schedule_date = ?");
            params.add(criteria.appointmentDate());
        }
        if (criteria.clinicPeriod() != null) {
            sql.append(" AND ds.clinic_period = ?");
            params.add(criteria.clinicPeriod());
        }
        sql.append(" ORDER BY ds.schedule_date, s.start_at, dr.id, s.slot_no LIMIT ?");
        params.add(Math.min(Math.max(criteria.limit(), 1), MAX_LIMIT));

        return jdbc.query(sql.toString(), (result, rowNum) -> new AvailableSlot(
                result.getLong("slot_id"),
                result.getLong("department_id"),
                result.getString("department_code"),
                result.getString("department_name"),
                result.getLong("doctor_id"),
                result.getString("doctor_name"),
                result.getString("doctor_title"),
                result.getDate("schedule_date").toLocalDate(),
                result.getString("clinic_period"),
                formatTime(result.getTimestamp("start_at")) + "-" + formatTime(result.getTimestamp("end_at"))),
                params.toArray());
    }

    private static String formatTime(Timestamp timestamp) {
        LocalTime time = timestamp.toLocalDateTime().toLocalTime();
        return time.toString().substring(0, 5);
    }
}
