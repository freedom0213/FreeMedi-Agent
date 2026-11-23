package com.mediflow.agent.appointment;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Profile("mysql")
public class AppointmentOperationsDashboard {
    private static final ZoneId HOSPITAL = ZoneId.of("Asia/Shanghai");
    private static final Counts ZERO = new Counts(0, 0, 0, 0, 0);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AppointmentOperationsDashboard(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Dashboard summarize(LocalDate from, LocalDate to, Long departmentId) {
        if (from == null || to == null || from.getYear() < 1000 || to.getYear() > 9999
                || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 90) {
            throw new IllegalArgumentException("请选择最多 90 天的有效出诊日期范围。");
        }
        if (departmentId != null && departmentId <= 0) throw new IllegalArgumentException("科室编号无效。");
        var now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, HOSPITAL);
        var rows = jdbc.query("""
                WITH scoped AS (
                    SELECT sc.id, sc.schedule_date, sc.status AS schedule_status, d.id AS doctor_id,
                           d.name AS doctor_name, d.status AS doctor_status, dp.id AS department_id,
                           dp.name AS department_name, dp.status AS department_status
                    FROM doctor_schedule sc JOIN doctor d ON d.id = sc.doctor_id
                    JOIN department dp ON dp.id = d.department_id
                    WHERE sc.schedule_date BETWEEN ? AND ? AND (? IS NULL OR dp.id = ?)
                ),
                occupancy AS (
                    SELECT sc.id,
                           SUM(CASE WHEN s.status = 'AVAILABLE' AND s.start_at > ?
                                    AND sc.schedule_date BETWEEN ? AND ?
                                    AND sc.schedule_status = 'OPEN' AND sc.doctor_status = 'ACTIVE'
                                    AND sc.department_status = 'ACTIVE' THEN 1 ELSE 0 END) AS remaining,
                           SUM(CASE WHEN s.status = 'RESERVED' THEN 1 ELSE 0 END) AS occupied
                    FROM scoped sc LEFT JOIN appointment_slot s ON s.schedule_id = sc.id
                    GROUP BY sc.id
                ),
                bookings AS (
                    SELECT sc.id, COUNT(*) AS booked,
                           SUM(CASE WHEN a.booked_via = 'PORTAL' THEN 1 ELSE 0 END) AS portal,
                           SUM(CASE WHEN a.booked_via = 'AGENT' THEN 1 ELSE 0 END) AS agent
                    FROM scoped sc JOIN appointment_slot s ON s.schedule_id = sc.id
                    JOIN appointment a ON a.slot_id = s.id AND a.status = 'BOOKED'
                    GROUP BY sc.id
                )
                SELECT sc.schedule_date, sc.schedule_status, sc.department_id, sc.department_name, sc.doctor_id, sc.doctor_name,
                       COALESCE(b.booked, 0) AS booked, COALESCE(b.portal, 0) AS portal, COALESCE(b.agent, 0) AS agent,
                       o.remaining, o.occupied
                FROM scoped sc JOIN occupancy o ON o.id = sc.id LEFT JOIN bookings b ON b.id = sc.id
                ORDER BY sc.department_name, sc.department_id, sc.doctor_name, sc.doctor_id, sc.schedule_date
                """, (rs, row) -> new ScheduleCounts(rs.getObject("schedule_date", LocalDate.class),
                new DepartmentKey(rs.getLong("department_id"), rs.getString("department_name")),
                new DoctorKey(rs.getLong("doctor_id"), rs.getString("doctor_name"),
                        rs.getLong("department_id"), rs.getString("department_name")),
                new Counts(rs.getLong("booked"), rs.getLong("portal"), rs.getLong("agent"),
                        rs.getLong("remaining"), rs.getLong("occupied")), "OPEN".equals(rs.getString("schedule_status"))),
                from, to, departmentId, departmentId, Timestamp.from(now), today, today.plusDays(SlotQueryService.SEARCH_HORIZON_DAYS));
        var days = new HashMap<LocalDate, Counts>();
        var departments = new LinkedHashMap<DepartmentKey, Counts>();
        var doctors = new LinkedHashMap<DoctorKey, Counts>();
        Counts total = ZERO;
        for (var row : rows) {
            total = total.plus(row.counts());
            days.merge(row.date(), row.counts(), Counts::plus);
            departments.merge(row.department(), row.counts(), Counts::plus);
            doctors.merge(row.doctor(), row.counts(), Counts::plus);
        }
        return new Dashboard(from, to, total, rows.stream().filter(ScheduleCounts::open).count(),
                from.datesUntil(to.plusDays(1)).map(day -> trend(day, days)).toList(),
                departments.entrySet().stream().map(entry -> new DepartmentRow(
                        entry.getKey().id(), entry.getKey().name(), entry.getValue())).toList(),
                doctors.entrySet().stream().map(entry -> new DoctorRow(entry.getKey().id(), entry.getKey().name(),
                        entry.getKey().departmentId(), entry.getKey().departmentName(), entry.getValue())).toList());
    }

    private static Trend trend(LocalDate date, Map<LocalDate, Counts> days) {
        var counts = days.getOrDefault(date, ZERO);
        return new Trend(date, counts.booked(), counts.portal(), counts.agent());
    }

    public record Counts(long booked, long portal, long agent, long remaining, long occupied) {
        Counts plus(Counts other) {
            return new Counts(booked + other.booked, portal + other.portal, agent + other.agent,
                    remaining + other.remaining, occupied + other.occupied);
        }
    }
    public record Trend(LocalDate date, long booked, long portal, long agent) {}
    public record DepartmentRow(long departmentId, String departmentName, Counts counts) {}
    public record DoctorRow(long doctorId, String doctorName, long departmentId, String departmentName, Counts counts) {}
    public record Dashboard(LocalDate from, LocalDate to, Counts totals, long openSchedules, List<Trend> trend,
                            List<DepartmentRow> departments, List<DoctorRow> doctors) {}

    private record DepartmentKey(long id, String name) {}
    private record DoctorKey(long id, String name, long departmentId, String departmentName) {}
    private record ScheduleCounts(LocalDate date, DepartmentKey department, DoctorKey doctor, Counts counts, boolean open) {}
}
