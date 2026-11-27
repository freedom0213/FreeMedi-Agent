package com.mediflow.agent.service.appointment.query;

import com.mediflow.agent.service.appointment.policy.HospitalRules;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.mediflow.agent.mapper.appointment.SlotQueryMapper;
import org.springframework.context.annotation.Profile;
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
    public record PortalPage(List<AvailableSlot> slots, LocalDate startDate, LocalDate endDate,
                             int page, int totalCount, int totalPages) {}

    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> PERIODS = List.of("MORNING", "AFTERNOON", "EVENING");

    private final SlotQueryMapper mapper;
    private final Clock clock;
    private final HospitalRules rules;

    public SlotQueryService(SlotQueryMapper mapper, Clock clock, HospitalRules rules) {
        this.mapper = mapper;
        this.clock = clock;
        this.rules = rules;
    }

    /** The hospital's current date. Search windows and date parsing both start from here. */
    public LocalDate today() {
        return hospitalDate(clock.instant());
    }

    public static LocalDate hospitalDate(java.time.Instant now) {
        return now.atZone(HOSPITAL_ZONE).toLocalDate();
    }

    public HospitalRules.Rules rules() { return rules.active(); }

    public LocalDate patientWindowEnd(LocalDate today) {
        return rules().patientEnd(today);
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
        if (!rules.bookingEnabled()) return new PortalPage(List.of(), from, until, 1, 0, 0);
        var now = Instant.now(clock);
        int total = mapper.count(criteria, from, until, null, now);
        int size = criteria.limit();
        int totalPages = (int) ((total + (long) size - 1) / size);
        int page = Math.min(requestedPage, Math.max(1, totalPages));
        List<AvailableSlot> slots = total == 0 ? List.of() : fetch(criteria, from, until, null, now, size, (long) (page - 1) * size);
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
        String normalised = value.trim().toUpperCase(Locale.ROOT);
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
        if (!rules.bookingEnabled()) return List.of();
        return fetch(criteria, from, until, slotId, Instant.now(clock),
                Math.min(Math.max(criteria.limit(), 1), MAX_LIMIT), 0);
    }

    private List<AvailableSlot> fetch(SlotCriteria criteria, LocalDate from, LocalDate until, Long slotId,
                                      Instant now, int limit, long offset) {
        var policy = rules();
        return mapper.find(criteria, from, until, slotId, now, limit, offset).stream()
                .map(row -> new AvailableSlot(row.slotId(), row.departmentId(), row.departmentCode(), row.departmentName(),
                        row.doctorId(), row.doctorName(), row.doctorTitle(), row.date(), row.period(),
                        formatTime(row.startAt()) + "-" + formatTime(row.endAt()), policy.cancelBefore(row.date())))
                .toList();
    }

    /** Shared with {@link AppointmentQueryService} so both render slot times the same way. */
    public static String formatTime(Timestamp timestamp) {
        LocalTime time = timestamp.toLocalDateTime().toLocalTime();
        return time.toString().substring(0, 5);
    }
}
