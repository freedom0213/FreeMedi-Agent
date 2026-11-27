package com.mediflow.agent.service.admin.appointment;

import com.mediflow.agent.service.appointment.policy.HospitalRules;
import com.mediflow.agent.mapper.appointment.AppointmentOperationsMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

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
    private final AppointmentOperationsMapper mapper;
    private final Clock clock;
    private final HospitalRules rules;

    public AppointmentOperationsDashboard(AppointmentOperationsMapper mapper, Clock clock, HospitalRules rules) {
        this.mapper = mapper;
        this.clock = clock;
        this.rules = rules;
    }

    public Dashboard summarize(LocalDate from, LocalDate to, Long departmentId) {
        if (from == null || to == null || from.getYear() < 1000 || to.getYear() > 9999
                || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 90) {
            throw new IllegalArgumentException("请选择最多 90 天的有效出诊日期范围。");
        }
        if (departmentId != null && departmentId <= 0) throw new IllegalArgumentException("科室编号无效。");
        var now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, HOSPITAL);
        var rows = mapper.scheduleCounts(from, to, departmentId, now, today, rules.active().patientEnd(today));
        var days = new HashMap<LocalDate, Counts>();
        var departments = new LinkedHashMap<DepartmentKey, Counts>();
        var doctors = new LinkedHashMap<DoctorKey, Counts>();
        Counts total = ZERO;
        for (var row : rows) {
            var counts = new Counts(row.booked(), row.portal(), row.agent(), row.remaining(), row.occupied());
            total = total.plus(counts);
            days.merge(row.date(), counts, Counts::plus);
            departments.merge(new DepartmentKey(row.departmentId(), row.departmentName()), counts, Counts::plus);
            doctors.merge(new DoctorKey(row.doctorId(), row.doctorName(), row.departmentId(), row.departmentName()),
                    counts, Counts::plus);
        }
        return new Dashboard(from, to, total, rows.stream().filter(row -> "OPEN".equals(row.status())).count(),
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
}
