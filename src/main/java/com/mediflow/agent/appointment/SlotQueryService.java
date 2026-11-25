package com.mediflow.agent.appointment;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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
public class SlotQueryService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    /** How far ahead the synthetic hospital publishes clinics. */
    public static final int SCHEDULE_HORIZON_DAYS = 90;

    public static final int PATIENT_WINDOW_DAYS = 28;

    public record PortalPage(List<AvailableSlot> slots, LocalDate startDate, LocalDate endDate,
                             int page, int totalCount, int totalPages) {}

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
        return hospitalDate(clock.instant());
    }

    static LocalDate hospitalDate(java.time.Instant now) {
        return now.atZone(HOSPITAL_ZONE).toLocalDate();
    }

    public static LocalDate patientWindowEnd(LocalDate today) {
        return today.plusDays(PATIENT_WINDOW_DAYS - 1);
    }

    /** Only what the caller asked for: an exact match or nothing. */
    public SlotSearch searchStrict(SlotCriteria criteria) {
        LocalDate today = today();
        LocalDate until = patientWindowEnd(today);
        return new SlotSearch(query(criteria, today, until), true,
                criteria.appointmentDate(), criteria.clinicPeriod(), until);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PortalPage searchPortal(SlotCriteria criteria, int requestedPage) {
        LocalDate from = today();
        LocalDate until = patientWindowEnd(from);
        List<Object> params = new ArrayList<>();
        String source = matchingSlots(criteria, from, until, null, params);
        int total = jdbc.queryForObject("SELECT COUNT(*) " + source, Integer.class, params.toArray());
        int size = criteria.limit();
        int totalPages = (int) ((total + (long) size - 1) / size);
        int page = Math.min(requestedPage, Math.max(1, totalPages));
        List<AvailableSlot> slots = total == 0 ? List.of() : fetch(source, params, size, (long) (page - 1) * size);
        return new PortalPage(slots, from, until, page, total, totalPages);
    }

    /**
     * Drops the date preference first, then the period preference, when the exact criteria find
     * nothing. The department and doctor are never relaxed: changing the clinical destination is a
     * decision for the patient, not for a fallback query.
     */
    public SlotSearch searchWithFallback(SlotCriteria criteria) {
        LocalDate today = today();
        LocalDate until = patientWindowEnd(today);
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
        return query(criteria, from, until, null);
    }

    public Optional<AvailableSlot> findAvailable(long slotId) {
        LocalDate today = today();
        return query(new SlotCriteria(null, null, null, null, null, null, 1),
                today, patientWindowEnd(today), slotId).stream().findFirst();
    }

    private List<AvailableSlot> query(SlotCriteria criteria, LocalDate from, LocalDate until, Long slotId) {
        List<Object> params = new ArrayList<>();
        String source = matchingSlots(criteria, from, until, slotId, params);
        return fetch(source, params, Math.min(Math.max(criteria.limit(), 1), MAX_LIMIT), 0);
    }

    private String matchingSlots(SlotCriteria criteria, LocalDate from, LocalDate until, Long slotId,
                                 List<Object> params) {
        StringBuilder sql = new StringBuilder("""
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
        params.add(Timestamp.from(Instant.now(clock)));
        params.add(from);
        params.add(until);
        if (slotId != null) {
            sql.append(" AND s.id = ?");
            params.add(slotId);
        }
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
        return sql.toString();
    }

    private List<AvailableSlot> fetch(String source, List<Object> params, int limit, long offset) {
        String select = """
                SELECT s.id AS slot_id, d.id AS department_id, d.code AS department_code,
                       d.name AS department_name, dr.id AS doctor_id, dr.name AS doctor_name,
                       dr.title AS doctor_title, ds.schedule_date, ds.clinic_period,
                       s.start_at, s.end_at
                """;
        params.add(limit);
        params.add(offset);
        return jdbc.query(select + source + " ORDER BY ds.schedule_date, s.start_at, dr.id, s.slot_no, s.id LIMIT ? OFFSET ?", (result, rowNum) -> new AvailableSlot(
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

    /** Shared with {@link AppointmentQueryService} so both render slot times the same way. */
    static String formatTime(Timestamp timestamp) {
        LocalTime time = timestamp.toLocalDateTime().toLocalTime();
        return time.toString().substring(0, 5);
    }
}
