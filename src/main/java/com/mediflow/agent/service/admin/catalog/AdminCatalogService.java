package com.mediflow.agent.service.admin.catalog;

import com.mediflow.agent.service.appointment.policy.HospitalRules;
import com.mediflow.agent.security.account.AdminAccess.Scope;
import com.mediflow.agent.mapper.admin.catalog.AdminCatalogQueryMapper;
import com.mediflow.agent.mapper.admin.catalog.AdminCatalogQueryMapper.ScheduleFilters;
import com.mediflow.agent.mapper.admin.catalog.AdminCatalogQueryMapper.ScheduleRow;
import com.mediflow.agent.mapper.admin.catalog.AdminCatalogWriteMapper;
import com.mediflow.agent.mapper.appointment.AppointmentLockMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

@Service
@Profile("mysql")
public class AdminCatalogService {
    private static final Pattern GENERATED_DOCTOR_CODE = Pattern.compile("D-[0-9]+", Pattern.CASE_INSENSITIVE);
    private static final int PAGE_SIZE = 20;
    private static final ZoneId HOSPITAL = ZoneId.of("Asia/Shanghai");
    private final AdminCatalogWriteMapper writes;
    private final AppointmentLockMapper locks;
    private final AdminCatalogQueryMapper queries;
    private final Clock clock;
    private final HospitalRules rules;

    public AdminCatalogService(AdminCatalogQueryMapper queries, AdminCatalogWriteMapper writes,
                               Clock clock, HospitalRules rules, AppointmentLockMapper locks) {
        this.queries = queries;
        this.writes = writes;
        this.locks = locks;
        this.clock = clock;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public Page<Department> departments(String q, String status, int page) {
        checkStatus(status, "ACTIVE", "DISABLED");
        return page(page, () -> queries.countDepartments(q, status),
                offset -> queries.departments(q, status, PAGE_SIZE, offset),
                row -> new Department(row.id(), row.code(), row.name(), row.description(), row.status()));
    }

    @Transactional(readOnly = true)
    public Page<Doctor> doctors(String q, Long departmentId, String status, int page) {
        checkStatus(status, "ACTIVE", "DISABLED");
        return page(page, () -> queries.countDoctors(q, departmentId, status),
                offset -> queries.doctors(q, departmentId, status, PAGE_SIZE, offset),
                row -> new Doctor(row.id(), row.departmentId(), row.departmentName(), row.name(), row.title(),
                        row.specialty(), row.introduction(), row.status(), row.importCode()));
    }

    public List<CatalogOption> departmentOptions() {
        return departmentOptions(new Scope(true, java.util.Set.of()));
    }

    public List<CatalogOption> departmentOptions(Scope scope) {
        return queries.departmentOptions(scope).stream()
                .map(row -> new CatalogOption(row.id(), row.name(), row.status())).toList();
    }

    public List<CatalogOption> doctorOptions(long departmentId) {
        return queries.doctorOptions(departmentId).stream()
                .map(row -> new CatalogOption(row.id(), row.name(), row.status())).toList();
    }

    @Transactional(readOnly = true)
    public Page<Schedule> schedules(Long departmentId, Long doctorId, LocalDate date, LocalDate from, LocalDate to,
                                    String status, int page) {
        return schedules(departmentId, doctorId, date, from, to, status, page, new Scope(true, java.util.Set.of()));
    }

    @Transactional(readOnly = true)
    public Page<Schedule> schedules(Long departmentId, Long doctorId, LocalDate date, LocalDate from, LocalDate to,
                                    String status, int page, Scope scope) {
        checkStatus(status, "OPEN", "CLOSED");
        if (from != null && to != null && to.isBefore(from)) throw new IllegalArgumentException("结束日期不能早于开始日期。");
        var filters = new ScheduleFilters(departmentId, doctorId, date, from, to, status);
        return page(page, () -> queries.countSchedules(filters, scope),
                offset -> queries.schedules(filters, scope, PAGE_SIZE, offset), AdminCatalogService::schedule);
    }

    @Transactional(readOnly = true)
    public ScheduleDetail schedule(long id) {
        var row = queries.schedule(id).orElseThrow(() -> notFound("排班"));
        var slots = queries.slots(id).stream()
                .map(slot -> new Slot(slot.number(), slot.startAt(), slot.endAt(), slot.status())).toList();
        return new ScheduleDetail(schedule(row), slots);
    }

    public long departmentForSchedule(long id) {
        return queries.departmentForSchedule(id).orElseThrow(() -> notFound("排班"));
    }

    private static Schedule schedule(ScheduleRow row) {
        return new Schedule(row.id(), row.departmentId(), row.departmentName(), row.doctorId(), row.doctorName(),
                row.scheduleDate(), row.clinicPeriod(), row.status(), row.slots(), row.remaining(), row.booked(),
                row.interrupted());
    }

    @Transactional
    public void createDepartment(String code, String name, String description) {
        try {
            writes.insertDepartment(code.toUpperCase(Locale.ROOT), name.strip(), clean(description));
        } catch (DuplicateKeyException conflict) {
            throw conflict("科室编码已存在，请使用其他编码。");
        }
    }

    @Transactional
    public void editDepartment(long id, String name, String description) {
        requireFound(locks.lockDepartment(id), "科室");
        writes.updateDepartment(id, name.strip(), clean(description));
    }

    @Transactional
    public void setDepartmentActive(long id, boolean active) {
        String current = requireFound(locks.lockDepartment(id), "科室");
        String desired = active ? "ACTIVE" : "DISABLED";
        if (current.equals(desired)) return;
        if (!active) protectFutureBookings("科室", writes.lockFutureDepartmentBookings(id).size());
        writes.setDepartmentStatus(id, desired);
    }

    @Transactional
    public void createDoctor(long departmentId, String name, String title, String specialty, String introduction) {
        createDoctor(departmentId, null, name, title, specialty, introduction);
    }

    @Transactional
    public void createDoctor(long departmentId, String code, String name, String title, String specialty, String introduction) {
        validateNewDoctorCode(code);
        if (!"ACTIVE".equals(locks.lockDepartment(departmentId))) {
            throw conflict("请为医生选择已启用的科室。");
        }
        writes.insertDoctor(departmentId, code, name.strip(), clean(title), clean(specialty), clean(introduction));
    }

    static void validateNewDoctorCode(String code) {
        if (code != null && GENERATED_DOCTOR_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("D- 加数字是系统保留的医生编码，请使用其他编码。");
        }
    }

    @Transactional
    public void editDoctor(long id, String name, String title, String specialty, String introduction) {
        lockDoctor(doctorDepartment(id), id);
        writes.updateDoctor(id, name.strip(), clean(title), clean(specialty), clean(introduction));
    }

    @Transactional
    public void setDoctorActive(long id, boolean active) {
        var statuses = lockDoctor(doctorDepartment(id), id);
        String desired = active ? "ACTIVE" : "DISABLED";
        if (statuses.doctor().equals(desired)) return;
        if (active && !"ACTIVE".equals(statuses.department())) throw conflict("请先启用所属科室。");
        if (!active) protectFutureBookings("医生", writes.lockFutureDoctorBookings(id).size());
        writes.setDoctorStatus(id, desired);
    }

    @Transactional
    public void createSchedule(long departmentId, long doctorId, LocalDate date, Period period,
                               LocalTime start, int minutes, int count) {
        LocalDateTime first = date.atTime(start);
        LocalDateTime end = first.plusMinutes((long) minutes * count);
        validateWindow(first, end, period);
        var statuses = lockDoctor(departmentId, doctorId);
        if (!"ACTIVE".equals(statuses.department()) || !"ACTIVE".equals(statuses.doctor())) {
            throw conflict("只能为已启用的科室和医生新增排班。");
        }
        // The doctor lock also serializes new schedules in different periods on the same day.
        validateWindow(first, end, period);
        if (!writes.lockSchedulesForPeriod(doctorId, date, period.name()).isEmpty()) {
            throw conflict("该医生在同一天的此时段已有排班。");
        }
        if (!writes.lockOverlappingSlots(doctorId, date, instant(first), instant(end)).isEmpty()) {
            throw conflict("新号源与该医生已有排班的时间重叠。");
        }
        try {
            writes.insertSchedule(doctorId, date, period.name());
        } catch (DuplicateKeyException conflict) {
            throw conflict("该医生在同一天的此时段已有排班。");
        }
        long id = writes.scheduleId(doctorId, date, period.name());
        for (int i = 0; i < count; i++) {
            LocalDateTime slotStart = first.plusMinutes((long) minutes * i);
            writes.insertSlot(id, i + 1, instant(slotStart), instant(slotStart.plusMinutes(minutes)));
        }
    }

    @Transactional
    public void closeSchedule(long id) {
        var path = writes.schedulePath(id).orElseThrow(() -> notFound("排班"));
        lockDoctor(path.departmentId(), path.doctorId());
        String current = requireFound(locks.lockSchedule(path.doctorId(), id), "排班");
        if ("CLOSED".equals(current)) return;
        int occupied = writes.lockActiveScheduleBookings(id).size();
        if (occupied > 0) throw conflict("已有 " + occupied + " 笔有效预约，无法关闭此排班。");
        if (!writes.lockReservedSlots(id).isEmpty()) {
            throw conflict("此排班仍有占用中的号源，核对预约状态后才能关闭。");
        }
        writes.closeSchedule(id);
        writes.closeAvailableSlots(id);
    }

    private long doctorDepartment(long doctorId) {
        return writes.doctorDepartment(doctorId).orElseThrow(() -> notFound("医生"));
    }

    private DoctorStatus lockDoctor(long departmentId, long doctorId) {
        String department = requireFound(locks.lockDepartment(departmentId), "科室");
        String doctor = requireFound(locks.lockDoctor(departmentId, doctorId), "该科室下的医生");
        return new DoctorStatus(department, doctor);
    }

    private static void protectFutureBookings(String label, int booked) {
        if (booked > 0) throw conflict(label + "还有 " + booked + " 笔有效预约，无法停用。");
    }

    void validateWindow(LocalDateTime first, LocalDateTime end, Period period) {
        LocalDate today = LocalDate.now(clock.withZone(HOSPITAL));
        int horizon = rules.active().scheduleDays();
        if (!first.atZone(HOSPITAL).toInstant().isAfter(clock.instant())
                || first.toLocalDate().isAfter(today.plusDays(horizon))) {
            throw new IllegalArgumentException("号源须在当前时间之后，最晚可排到今天之后第 " + horizon + " 天。");
        }
        if (first.toLocalTime().isBefore(period.start) || end.isAfter(first.toLocalDate().atTime(period.end))
                || first.getSecond() != 0 || first.getNano() != 0) {
            throw new IllegalArgumentException("所有号源必须在所选时段内，开始时间精确到分钟。");
        }
    }

    private static Instant instant(LocalDateTime time) { return time.atZone(HOSPITAL).toInstant(); }
    private static <R, T> Page<T> page(int page, LongSupplier countRows, LongFunction<List<R>> readRows,
                                      Function<R, T> mapper) {
        if (page < 1) throw new IllegalArgumentException("页码须从 1 开始。");
        long offset = ((long) page - 1) * PAGE_SIZE;
        long count = countRows.getAsLong();
        long pages = (count + PAGE_SIZE - 1) / PAGE_SIZE;
        if (offset >= count) return new Page<>(List.of(), page, false, pages);
        return new Page<>(readRows.apply(offset).stream().map(mapper).toList(), page, page < pages, pages);
    }
    private static void checkStatus(String actual, String first, String second) {
        if (actual != null && !first.equals(actual) && !second.equals(actual)) {
            throw new IllegalArgumentException("请选择有效的状态。");
        }
    }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String requireFound(String status, String label) {
        if (status == null) throw notFound(label);
        return status;
    }
    private static CatalogProblem notFound(String label) { return new CatalogProblem(404, "未找到" + label + "。"); }
    private static CatalogProblem conflict(String message) { return new CatalogProblem(409, message); }

    public static final class CatalogProblem extends RuntimeException {
        private final int status;
        private CatalogProblem(int status, String message) {
            super(message);
            this.status = status;
        }
        public int status() { return status; }
    }
    public enum Period {
        MORNING(LocalTime.MIDNIGHT, LocalTime.NOON), AFTERNOON(LocalTime.NOON, LocalTime.of(18, 0)),
        EVENING(LocalTime.of(18, 0), LocalTime.MAX);
        private final LocalTime start;
        private final LocalTime end;
        Period(LocalTime start, LocalTime end) { this.start = start; this.end = end; }
    }
    private record DoctorStatus(String department, String doctor) {}
    public record Page<T>(List<T> items, int page, boolean hasNext, long totalPages) {}
    public record CatalogOption(long id, String name, String status) {}
    public record Department(long id, String code, String name, String description, String status) {}
    public record Doctor(long id, long departmentId, String departmentName, String name,
                         String title, String specialty, String introduction, String status, String importCode) {}
    public record Schedule(long id, long departmentId, String departmentName, long doctorId, String doctorName,
                           LocalDate scheduleDate, String clinicPeriod, String status, int slots, int remaining, int booked,
                           boolean interrupted) {}
    public record Slot(int number, Instant startAt, Instant endAt, String status) {}
    public record ScheduleDetail(Schedule schedule, List<Slot> slots) {}
}
