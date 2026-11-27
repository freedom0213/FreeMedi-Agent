package com.mediflow.agent.appointment;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
@Profile("mysql")
public class HospitalCatalogImport {
    public enum Kind {
        DEPARTMENTS("code,name"), DOCTORS("code,name,departmentCode,title"),
        SCHEDULES("doctorCode,date,period,start,minutes,count");

        final String header;
        Kind(String header) { this.header = header; }
    }

    public record Row(int line, String key, String state, String message) {}
    public record Preview(String token, List<Row> rows) {}
    private record Entry(Row row, Runnable apply) {}
    private record Department(long id, String name, String status) {}
    private record Doctor(long id, long department, String name, String title, String status) {}

    private static final Pattern FIELD = Pattern.compile("\"((?:[^\"]|\"\")*)\"|([^,\"]*)");
    private final JdbcTemplate jdbc;
    private final AdminCatalogService catalog;

    public HospitalCatalogImport(JdbcTemplate jdbc, AdminCatalogService catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public Preview preview(Kind kind, String text) {
        return preview(kind, text, inspect(kind, text));
    }

    @Transactional
    public int commit(Kind kind, String text, String token) {
        var entries = inspect(kind, text);
        var preview = preview(kind, text, entries);
        if (!preview.token().equals(token) || preview.rows().stream().anyMatch(row -> row.state().equals("ERROR"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "导入内容或现有数据已变化，请重新预览。");
        }
        try {
            entries.stream().filter(entry -> entry.apply() != null).forEach(entry -> entry.apply().run());
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "导入时数据已变化，请重新预览。");
        }
        return (int) entries.stream().filter(entry -> entry.apply() != null).count();
    }

    private List<Entry> inspect(Kind kind, String text) {
        var lines = text.replaceFirst("^\uFEFF", "").stripTrailing().split("\\R");
        if (lines.length < 2 || lines.length > 201 || !kind.header.equals(lines[0].strip())) {
            throw new IllegalArgumentException("请使用所选模板的表头，每批填写 1 至 200 行。");
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            int line = i + 1;
            String key = "";
            try {
                var fields = fields(lines[i]);
                if (fields.size() != kind.header.split(",").length) throw invalid("列数与表头不一致。");
                var code = code(fields.get(0));
                if (kind == Kind.SCHEDULES) {
                    key = code + "/" + fields.get(1) + "/" + fields.get(2);
                    var date = LocalDate.parse(fields.get(1));
                    var normalizedPeriod = SlotQueryService.normalisePeriod(fields.get(2));
                    if (normalizedPeriod == null) throw invalid("请填写具体的就诊时段。");
                    var period = AdminCatalogService.Period.valueOf(normalizedPeriod);
                    key = code + "/" + date + "/" + period.name();
                    if (!keys.add(key)) throw invalid("文件中存在重复记录。");
                    entries.add(schedule(line, key, code, date, period, fields));
                    continue;
                }
                key = code;
                if (!keys.add(key)) throw invalid("文件中存在重复记录。");
                entries.add(kind == Kind.DEPARTMENTS
                        ? department(line, code, value(fields.get(1), 100, true))
                        : doctor(line, code, value(fields.get(1), 100, true), code(fields.get(2)),
                                value(fields.get(3), 80, false)));
            } catch (IllegalArgumentException | java.time.DateTimeException error) {
                entries.add(new Entry(new Row(line, key, "ERROR", error instanceof java.time.DateTimeException
                        ? "日期或时间格式有误，请使用 YYYY-MM-DD 和 HH:mm。" : error.getMessage()), null));
            }
        }
        return entries;
    }

    private Entry department(int line, String code, String name) {
        var existing = department(code);
        if (existing != null) return duplicate(line, code, name.equals(existing.name()) && "ACTIVE".equals(existing.status()));
        return ready(line, code, () -> catalog.createDepartment(code, name, null));
    }

    private Entry doctor(int line, String code, String name, String departmentCode, String title) {
        var department = department(departmentCode);
        if (department == null || !"ACTIVE".equals(department.status())) throw invalid("所属科室不存在或未启用。");
        var existing = doctor(code);
        if (existing != null) return duplicate(line, code, existing.department() == department.id()
                && name.equals(existing.name()) && title.equals(Objects.toString(existing.title(), ""))
                && "ACTIVE".equals(existing.status()));
        AdminCatalogService.validateNewDoctorCode(code);
        return ready(line, code, () -> catalog.createDoctor(department.id(), code, name, title, null, null));
    }

    private Entry schedule(int line, String key, String code, LocalDate date, AdminCatalogService.Period period,
                           List<String> fields) {
        var existing = doctor(code);
        if (existing == null || !"ACTIVE".equals(existing.status())) throw invalid("医生或所属科室不存在或未启用。");
        var start = LocalTime.parse(fields.get(3));
        int minutes = number(fields.get(4), 5, 120);
        int count = number(fields.get(5), 1, 40);
        if (start.getSecond() != 0 || start.getNano() != 0) throw invalid("开始时间须填写到分钟。");
        var first = date.atTime(start);
        var end = first.plusMinutes((long) count * minutes);
        var slots = jdbc.query("""
                SELECT s.slot_no, s.start_at, s.end_at, sc.status FROM doctor_schedule sc
                LEFT JOIN appointment_slot s ON s.schedule_id=sc.id
                WHERE sc.doctor_id=? AND sc.schedule_date=? AND sc.clinic_period=? ORDER BY s.slot_no
                """, (row, i) -> {
                    Timestamp actualStart = row.getTimestamp(2);
                    return actualStart != null && row.getInt(1) == i + 1 && "OPEN".equals(row.getString(4))
                            && actualStart.toInstant().equals(first.plusMinutes((long) i * minutes).atZone(HospitalRules.ZONE).toInstant())
                            && row.getTimestamp(3).toInstant().equals(first.plusMinutes((long) (i + 1) * minutes).atZone(HospitalRules.ZONE).toInstant());
                }, existing.id(), date, period.name());
        if (!slots.isEmpty()) return duplicate(line, key, slots.size() == count && slots.stream().allMatch(Boolean::booleanValue));
        catalog.validateWindow(first, end, period);
        return ready(line, key, () -> catalog.createSchedule(existing.department(), existing.id(), date, period, start, minutes, count));
    }

    private Department department(String code) {
        return jdbc.query("SELECT id,name,status FROM department WHERE code=?",
                (row, i) -> new Department(row.getLong(1), row.getString(2), row.getString(3)), code)
                .stream().findFirst().orElse(null);
    }

    private Doctor doctor(String code) {
        var matches = jdbc.query("""
                SELECT d.id,d.department_id,d.name,d.title,IF(dp.status='ACTIVE',d.status,'DISABLED')
                FROM doctor d JOIN department dp ON dp.id=d.department_id
                WHERE d.code=? OR (d.code IS NULL AND CONCAT('D-',d.id)=?)
                LIMIT 2
                """, (row, i) -> new Doctor(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4), row.getString(5)),
                code, code);
        if (matches.size() > 1) throw invalid("此编码对应多位医生，请核对医生目录中的编码冲突。");
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static List<String> fields(String input) {
        var columns = new ArrayList<String>();
        var matcher = FIELD.matcher(input);
        int offset = 0;
        do {
            if (!matcher.region(offset, input.length()).lookingAt()) throw invalid("CSV 格式有误。");
            String field = matcher.group(1);
            columns.add((field == null ? matcher.group(2) : field.replace("\"\"", "\"")).strip());
            offset = matcher.end();
            if (offset == input.length()) return columns;
            if (input.charAt(offset++) != ',') throw invalid("CSV 引号格式有误。");
        } while (offset <= input.length());
        throw invalid("CSV 格式有误。");
    }

    private static String code(String input) {
        if (!input.matches("[A-Za-z0-9_-]{1,48}")) throw invalid("编码须为 1 至 48 位字母、数字、下划线或短横线。");
        return input.toUpperCase(Locale.ROOT);
    }

    private static String value(String input, int max, boolean required) {
        if (input.length() > max || (required && input.isEmpty())) throw invalid("名称或职称长度不符合模板要求。");
        return input;
    }

    private static int number(String input, int min, int max) {
        if (input.matches("[0-9]{1,3}")) {
            int parsed = Integer.parseInt(input);
            if (parsed >= min && parsed <= max) return parsed;
        }
        throw invalid("号源时长或数量超出范围。");
    }

    private static Entry ready(int line, String key, Runnable insert) {
        return new Entry(new Row(line, key, "CREATE", "可导入"), insert);
    }

    private static Entry duplicate(int line, String key, boolean exact) {
        return new Entry(new Row(line, key, exact ? "SKIP" : "ERROR",
                exact ? "已有相同记录，将跳过" : "与现有数据冲突，请核对编码或排班"), null);
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static Preview preview(Kind kind, String text, List<Entry> entries) {
        var rows = entries.stream().map(Entry::row).toList();
        try {
            var bytes = (kind.name() + "\n" + text + "\n" + rows).getBytes(StandardCharsets.UTF_8);
            return new Preview(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), rows);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
