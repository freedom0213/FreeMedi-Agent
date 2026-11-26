package com.mediflow.agent.appointment;

import com.mediflow.agent.config.AdminAccess.Scope;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Profile("mysql")
public class AdminCatalogService {
    private static final int PAGE_SIZE = 20;
    private static final ZoneId HOSPITAL = ZoneId.of("Asia/Shanghai");
    private static final String SCHEDULE_SOURCE = """
            FROM doctor_schedule sc JOIN doctor d ON d.id = sc.doctor_id
            JOIN department dp ON dp.id = d.department_id
            """;
    private static final String SCHEDULE_COLUMNS = """
            SELECT sc.id, sc.doctor_id, d.name AS doctor_name, dp.id AS department_id,
                   dp.name AS department_name, sc.schedule_date, sc.clinic_period, sc.status,
                   (SELECT COUNT(*) FROM appointment_slot s WHERE s.schedule_id = sc.id) AS slots,
                   (SELECT COUNT(*) FROM appointment_slot s WHERE s.schedule_id = sc.id
                      AND s.status = 'AVAILABLE' AND s.start_at > UTC_TIMESTAMP(3)
                      AND sc.status = 'OPEN' AND d.status = 'ACTIVE' AND dp.status = 'ACTIVE') AS remaining,
                   (SELECT COUNT(*) FROM appointment a JOIN appointment_slot s ON s.id = a.slot_id
                       WHERE s.schedule_id = sc.id AND a.status IN ('BOOKED', 'CHECKED_IN', 'VISITED')) AS booked,
                   EXISTS(SELECT 1 FROM schedule_interruption i WHERE i.schedule_id=sc.id) AS interrupted
            """;
    private static final RowMapper<Schedule> SCHEDULE = (rs, row) -> new Schedule(rs.getLong("id"),
            rs.getLong("department_id"), rs.getString("department_name"), rs.getLong("doctor_id"),
            rs.getString("doctor_name"), rs.getObject("schedule_date", LocalDate.class), rs.getString("clinic_period"),
            rs.getString("status"), rs.getInt("slots"), rs.getInt("remaining"), rs.getInt("booked"), rs.getBoolean("interrupted"));
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final HospitalRules rules;

    public AdminCatalogService(JdbcTemplate jdbc, Clock clock, HospitalRules rules) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public Page<Department> departments(String q, String status, int page) {
        checkStatus(status, "ACTIVE", "DISABLED");
        return page("SELECT id, code, name, description, status ", """
                FROM department
                WHERE (? IS NULL OR name LIKE CONCAT('%', ?, '%') OR code LIKE CONCAT('%', ?, '%'))
                  AND (? IS NULL OR status = ?)
                """, "ORDER BY name, id", (rs, row) -> new Department(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("status")), page, q, q, q, status, status);
    }

    @Transactional(readOnly = true)
    public Page<Doctor> doctors(String q, Long departmentId, String status, int page) {
        checkStatus(status, "ACTIVE", "DISABLED");
        return page("""
                SELECT d.id, d.department_id, dp.name AS department_name, d.name, d.title,
                       d.specialty, d.introduction, d.status, COALESCE(d.code,CONCAT('D-',d.id)) AS import_code
                """, """
                FROM doctor d JOIN department dp ON dp.id = d.department_id
                WHERE (? IS NULL OR d.name LIKE CONCAT('%', ?, '%'))
                  AND (? IS NULL OR d.department_id = ?) AND (? IS NULL OR d.status = ?)
                """, "ORDER BY dp.name, d.name, d.id", (rs, row) -> new Doctor(rs.getLong("id"), rs.getLong("department_id"),
                rs.getString("department_name"), rs.getString("name"), rs.getString("title"), rs.getString("specialty"),
                rs.getString("introduction"), rs.getString("status"), rs.getString("import_code")), page, q, q, departmentId, departmentId, status, status);
    }

    public List<CatalogOption> departmentOptions() {
        return departmentOptions(new Scope(true, java.util.Set.of()));
    }

    public List<CatalogOption> departmentOptions(Scope scope) {
        var args = new ArrayList<>();
        return jdbc.query("SELECT id, name, status FROM department WHERE " + scope.predicate("id", args) + " ORDER BY name, id",
                (rs, row) -> new CatalogOption(rs.getLong("id"), rs.getString("name"), rs.getString("status")), args.toArray());
    }

    public List<CatalogOption> doctorOptions(long departmentId) {
        return jdbc.query("SELECT id, name, status FROM doctor WHERE department_id = ? ORDER BY name, id",
                (rs, row) -> new CatalogOption(rs.getLong("id"), rs.getString("name"), rs.getString("status")), departmentId);
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
        var args = new ArrayList<Object>(Arrays.asList(departmentId, departmentId, doctorId, doctorId, date, date, from, from, to, to, status, status));
        return page(SCHEDULE_COLUMNS, SCHEDULE_SOURCE + """
                WHERE (? IS NULL OR dp.id = ?) AND (? IS NULL OR d.id = ?)
                  AND (? IS NULL OR sc.schedule_date = ?) AND (? IS NULL OR sc.schedule_date >= ?)
                  AND (? IS NULL OR sc.schedule_date <= ?) AND (? IS NULL OR sc.status = ?)
                """ + " AND " + scope.predicate("dp.id", args), " ORDER BY sc.schedule_date DESC, sc.id DESC", SCHEDULE, page,
                args.toArray());
    }

    @Transactional(readOnly = true)
    public ScheduleDetail schedule(long id) {
        Schedule schedule = jdbc.query(SCHEDULE_COLUMNS + SCHEDULE_SOURCE + " WHERE sc.id = ?", SCHEDULE, id).stream().findFirst()
                .orElseThrow(() -> notFound("排班"));
        var slots = jdbc.query("""
                SELECT slot_no, start_at, end_at, status FROM appointment_slot WHERE schedule_id = ? ORDER BY slot_no
                """, (rs, row) -> new Slot(rs.getInt("slot_no"), rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(), rs.getString("status")), id);
        return new ScheduleDetail(schedule, slots);
    }

    public long departmentForSchedule(long id) {
        return jdbc.queryForList("SELECT d.department_id FROM doctor_schedule s JOIN doctor d ON d.id=s.doctor_id WHERE s.id=?",
                Long.class, id).stream().findFirst().orElseThrow(() -> notFound("排班"));
    }

    @Transactional
    public void createDepartment(String code, String name, String description) {
        try {
            jdbc.update("INSERT INTO department (code, name, description, status) VALUES (?, ?, ?, 'ACTIVE')",
                    code.toUpperCase(Locale.ROOT), name.strip(), clean(description));
        } catch (DuplicateKeyException conflict) {
            throw conflict("科室编码已存在，请使用其他编码。");
        }
    }

    @Transactional
    public void editDepartment(long id, String name, String description) {
        requireFound(AppointmentAvailability.lockDepartment(jdbc, id), "科室");
        jdbc.update("UPDATE department SET name = ?, description = ? WHERE id = ?", name.strip(), clean(description), id);
    }

    @Transactional
    public void setDepartmentActive(long id, boolean active) {
        String current = requireFound(AppointmentAvailability.lockDepartment(jdbc, id), "科室");
        String desired = active ? "ACTIVE" : "DISABLED";
        if (current.equals(desired)) return;
        if (!active) protectFutureBookings("科室", "a.department_id", id);
        jdbc.update("UPDATE department SET status = ? WHERE id = ?", desired, id);
    }

    @Transactional
    public void createDoctor(long departmentId, String name, String title, String specialty, String introduction) {
        createDoctor(departmentId, null, name, title, specialty, introduction);
    }

    @Transactional
    public void createDoctor(long departmentId, String code, String name, String title, String specialty, String introduction) {
        if (!"ACTIVE".equals(AppointmentAvailability.lockDepartment(jdbc, departmentId))) {
            throw conflict("请为医生选择已启用的科室。");
        }
        jdbc.update("""
                INSERT INTO doctor (department_id, code, name, title, specialty, introduction, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, departmentId, code, name.strip(), clean(title), clean(specialty), clean(introduction));
    }

    @Transactional
    public void editDoctor(long id, String name, String title, String specialty, String introduction) {
        lockDoctor(doctorDepartment(id), id);
        jdbc.update("UPDATE doctor SET name = ?, title = ?, specialty = ?, introduction = ? WHERE id = ?",
                name.strip(), clean(title), clean(specialty), clean(introduction), id);
    }

    @Transactional
    public void setDoctorActive(long id, boolean active) {
        var statuses = lockDoctor(doctorDepartment(id), id);
        String desired = active ? "ACTIVE" : "DISABLED";
        if (statuses.doctor().equals(desired)) return;
        if (active && !"ACTIVE".equals(statuses.department())) throw conflict("请先启用所属科室。");
        if (!active) protectFutureBookings("医生", "a.doctor_id", id);
        jdbc.update("UPDATE doctor SET status = ? WHERE id = ?", desired, id);
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
        if (!jdbc.queryForList("""
                SELECT id FROM doctor_schedule WHERE doctor_id = ? AND schedule_date = ? AND clinic_period = ? FOR UPDATE
                """, Long.class, doctorId, date, period.name()).isEmpty()) {
            throw conflict("该医生在同一天的此时段已有排班。");
        }
        if (!jdbc.queryForList("""
                SELECT s.id FROM appointment_slot s JOIN doctor_schedule sc ON sc.id = s.schedule_id
                WHERE sc.doctor_id = ? AND sc.schedule_date = ? AND sc.status = 'OPEN'
                  AND s.start_at < ? AND s.end_at > ? FOR UPDATE
                """, Long.class, doctorId, date, timestamp(end), timestamp(first)).isEmpty()) {
            throw conflict("新号源与该医生已有排班的时间重叠。");
        }
        try {
            jdbc.update("INSERT INTO doctor_schedule (doctor_id, schedule_date, clinic_period, status) VALUES (?, ?, ?, 'OPEN')",
                    doctorId, date, period.name());
        } catch (DuplicateKeyException conflict) {
            throw conflict("该医生在同一天的此时段已有排班。");
        }
        long id = jdbc.queryForObject("""
                SELECT id FROM doctor_schedule WHERE doctor_id = ? AND schedule_date = ? AND clinic_period = ?
                """, Long.class, doctorId, date, period.name());
        for (int i = 0; i < count; i++) {
            LocalDateTime slotStart = first.plusMinutes((long) minutes * i);
            jdbc.update("""
                    INSERT INTO appointment_slot (schedule_id, slot_no, start_at, end_at, status)
                    VALUES (?, ?, ?, ?, 'AVAILABLE')
                    """, id, i + 1, timestamp(slotStart), timestamp(slotStart.plusMinutes(minutes)));
        }
    }

    @Transactional
    public void closeSchedule(long id) {
        var path = jdbc.query("""
                SELECT sc.doctor_id, d.department_id FROM doctor_schedule sc
                JOIN doctor d ON d.id = sc.doctor_id WHERE sc.id = ?
                """, (rs, row) -> new SchedulePath(rs.getLong("department_id"), rs.getLong("doctor_id")), id)
                .stream().findFirst().orElseThrow(() -> notFound("排班"));
        lockDoctor(path.departmentId(), path.doctorId());
        String current = requireFound(AppointmentAvailability.lockSchedule(jdbc, path.doctorId(), id), "排班");
        if ("CLOSED".equals(current)) return;
        var occupied = jdbc.queryForList("""
                SELECT a.id FROM appointment a JOIN appointment_slot s ON s.id = a.slot_id
                WHERE s.schedule_id = ? AND a.status IN ('BOOKED', 'CHECKED_IN', 'VISITED') FOR UPDATE
                """, Long.class, id);
        if (!occupied.isEmpty()) throw conflict("已有 " + occupied.size() + " 笔有效预约，无法关闭此排班。");
        if (!jdbc.queryForList("""
                SELECT id FROM appointment_slot WHERE schedule_id = ? AND status = 'RESERVED' FOR UPDATE
                """, Long.class, id).isEmpty()) {
            throw conflict("此排班仍有占用中的号源，核对预约状态后才能关闭。");
        }
        jdbc.update("UPDATE doctor_schedule SET status = 'CLOSED' WHERE id = ?", id);
        jdbc.update("""
                UPDATE appointment_slot SET status = 'CLOSED', version = version + 1
                WHERE schedule_id = ? AND status = 'AVAILABLE'
                """, id);
    }

    private long doctorDepartment(long doctorId) {
        return jdbc.queryForList("SELECT department_id FROM doctor WHERE id = ?", Long.class, doctorId)
                .stream().findFirst().orElseThrow(() -> notFound("医生"));
    }

    private DoctorStatus lockDoctor(long departmentId, long doctorId) {
        String department = requireFound(AppointmentAvailability.lockDepartment(jdbc, departmentId), "科室");
        String doctor = requireFound(AppointmentAvailability.lockDoctor(jdbc, departmentId, doctorId), "该科室下的医生");
        return new DoctorStatus(department, doctor);
    }

    private void protectFutureBookings(String label, String field, long id) {
        var booked = jdbc.queryForList("""
                SELECT a.id FROM appointment a JOIN appointment_slot s ON s.id = a.slot_id
                WHERE a.status IN ('BOOKED', 'CHECKED_IN', 'VISITED') AND s.end_at > UTC_TIMESTAMP(3) AND %s = ? FOR UPDATE
                """.formatted(field), Long.class, id);
        if (!booked.isEmpty()) throw conflict(label + "还有 " + booked.size() + " 笔有效预约，无法停用。");
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

    private static Timestamp timestamp(LocalDateTime time) { return Timestamp.from(time.atZone(HOSPITAL).toInstant()); }
    private static long offset(int page) {
        if (page < 1) throw new IllegalArgumentException("页码须从 1 开始。");
        return ((long) page - 1) * PAGE_SIZE;
    }
    private <T> Page<T> page(String columns, String from, String order, RowMapper<T> mapper, int page, Object... filters) {
        long offset = offset(page);
        long count = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, filters);
        long pages = (count + PAGE_SIZE - 1) / PAGE_SIZE;
        if (offset >= count) return new Page<>(List.of(), page, false, pages);
        var parameters = Arrays.copyOf(filters, filters.length + 2);
        parameters[filters.length] = PAGE_SIZE;
        parameters[filters.length + 1] = offset;
        var items = jdbc.query(columns + from + order + " LIMIT ? OFFSET ?", mapper, parameters);
        return new Page<>(items, page, page < pages, pages);
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
    private record SchedulePath(long departmentId, long doctorId) {}
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
