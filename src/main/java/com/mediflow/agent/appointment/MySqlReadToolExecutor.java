package com.mediflow.agent.appointment;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Clock;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.mediflow.agent.agent.ReadToolExecutor;
import com.mediflow.agent.agent.ReadToolSpec;
import com.mediflow.agent.agent.TaskSnapshot;
import com.mediflow.agent.agent.ToolCall;
import com.mediflow.agent.agent.ToolResult;

import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only catalog tools used by the agent to search synthetic appointment slots. */
@Service
@Profile("mysql")
public final class MySqlReadToolExecutor implements ReadToolExecutor {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final List<ReadToolSpec> TOOLS = List.of(
            new ReadToolSpec("findAvailableSlots", "按科室、医生和可选日期/时段查询未来可用号源，无匹配时返回同科室近期替代时间"),
            new ReadToolSpec("getAppointmentRules", "查询预约规则"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MySqlReadToolExecutor(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    @Autowired
    public MySqlReadToolExecutor(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    @Override
    public ToolResult execute(ToolCall call, TaskSnapshot task) {
        if (call == null || task == null) {
            return ToolResult.failure("INVALID_TOOL_REQUEST", "工具请求不完整");
        }
        return switch (call.name()) {
            case "findAvailableSlots" -> findAvailableSlots(call.arguments());
            case "getAppointmentRules" -> ToolResult.success(rules());
            default -> ToolResult.failure("UNKNOWN_TOOL", "未知只读工具: " + call.name());
        };
    }

    @Override
    public List<ReadToolSpec> tools() {
        return TOOLS;
    }

    private ToolResult findAvailableSlots(Map<String, Object> arguments) {
        try {
            Long departmentId = positiveLong(arguments, "departmentId");
            Long doctorId = positiveLong(arguments, "doctorId");
            String departmentName = name(arguments, "departmentName");
            String doctorName = name(arguments, "doctorName");
            LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
            LocalDate appointmentDate = AppointmentDateParser.parse(name(arguments, "appointmentDate"), today);
            String clinicPeriod = text(arguments, "clinicPeriod");
            int limit = limit(arguments.get("limit"));

            List<Map<String, Object>> slots = querySlots(departmentId, doctorId, departmentName, doctorName,
                    appointmentDate, clinicPeriod, today, today.plusDays(90), limit);
            boolean exactMatch = !slots.isEmpty();
            // Keep clinical destination fixed. Only date/period preferences may be relaxed.
            if (slots.isEmpty() && (appointmentDate != null || clinicPeriod != null)) {
                LocalDate from = appointmentDate != null && appointmentDate.isAfter(today) ? appointmentDate : today;
                slots = querySlots(departmentId, doctorId, departmentName, doctorName,
                        null, clinicPeriod, from, today.plusDays(90), limit);
                if (slots.isEmpty() && clinicPeriod != null) {
                    slots = querySlots(departmentId, doctorId, departmentName, doctorName,
                            null, null, from, today.plusDays(90), limit);
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", slots.size());
            data.put("slots", slots);
            data.put("truncated", slots.size() == limit);
            data.put("exactMatch", exactMatch);
            data.put("requestedDate", appointmentDate == null ? "" : appointmentDate.toString());
            data.put("requestedPeriod", clinicPeriod == null ? "" : clinicPeriod);
            data.put("searchEndDate", today.plusDays(90).toString());
            data.put("notice", !exactMatch && !slots.isEmpty()
                    ? "原日期或时段无号，以下为同科室的替代时间，需向患者说明变化并等待确认"
                    : slots.isEmpty() ? "未来90天的查询范围内没有可用号源" : "已找到符合条件的号源");
            return ToolResult.success(data);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure("INVALID_TOOL_ARGUMENT", exception.getMessage());
        }
    }

    private List<Map<String, Object>> querySlots(Long departmentId, Long doctorId,
                                                String departmentName, String doctorName,
                                                LocalDate appointmentDate, String clinicPeriod,
                                                LocalDate from, LocalDate until, int limit) {

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
            if (departmentId != null) {
                sql.append(" AND d.id = ?");
                params.add(departmentId);
            }
            if (doctorId != null) {
                sql.append(" AND dr.id = ?");
                params.add(doctorId);
            }
            if (departmentName != null) {
                sql.append(" AND (d.name = ? OR d.code = ?)");
                params.add(departmentName);
                params.add(departmentName);
            }
            if (doctorName != null) {
                sql.append(" AND dr.name = ?");
                params.add(doctorName);
            }
            if (appointmentDate != null) {
                sql.append(" AND ds.schedule_date = ?");
                params.add(appointmentDate);
            }
            if (clinicPeriod != null) {
                sql.append(" AND ds.clinic_period = ?");
                params.add(clinicPeriod);
            }
            sql.append(" ORDER BY ds.schedule_date, s.start_at, dr.id, s.slot_no LIMIT ?");
            params.add(limit);

            return jdbc.query(sql.toString(), (result, rowNum) -> {
                Timestamp start = result.getTimestamp("start_at");
                Timestamp end = result.getTimestamp("end_at");
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("slotId", result.getLong("slot_id"));
                slot.put("departmentId", result.getLong("department_id"));
                slot.put("departmentCode", result.getString("department_code"));
                slot.put("departmentName", result.getString("department_name"));
                slot.put("doctorId", result.getLong("doctor_id"));
                slot.put("doctorName", result.getString("doctor_name"));
                slot.put("doctorTitle", result.getString("doctor_title"));
                slot.put("appointmentDate", result.getDate("schedule_date").toLocalDate().toString());
                slot.put("clinicPeriod", result.getString("clinic_period"));
                slot.put("timeRange", formatTime(start) + "-" + formatTime(end));
                return slot;
            }, params.toArray());

    }

    private Map<String, Object> rules() {
        return Map.of(
                "advanceBookingDays", 90,
                "cancelBeforeHours", 24,
                "confirmationRequired", true,
                "syntheticData", true);
    }

    private static Long positiveLong(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        try {
            long parsed = value instanceof Number number
                    ? number.longValue() : Long.parseLong(value.toString());
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
    }

    private static String text(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String normalized = value.toString().trim().toUpperCase();
        normalized = switch (normalized) {
            case "上午", "早上" -> "MORNING";
            case "下午" -> "AFTERNOON";
            case "晚上", "晚间" -> "EVENING";
            case "不限", "都可以" -> "";
            default -> normalized;
        };
        if (normalized.isEmpty()) return null;
        if (!List.of("MORNING", "AFTERNOON", "EVENING").contains(normalized)) {
            throw new IllegalArgumentException("没有识别出就诊时段，可以选择上午、下午、晚上，或不限");
        }
        return normalized;
    }

    private static String name(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String text = value.toString().trim();
        if ("departmentName".equals(name)) {
            return switch (text) {
                case "呼吸科" -> "呼吸内科";
                case "心血管内科", "心脏内科" -> "心内科";
                case "全科" -> "全科医学科";
                default -> text;
            };
        }
        return text;
    }

    private static int limit(Object value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int parsed = value instanceof Number number
                    ? number.intValue() : Integer.parseInt(value.toString());
            if (parsed <= 0 || parsed > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit must be a positive integer");
        }
    }

    private static String formatTime(Timestamp timestamp) {
        LocalTime time = timestamp.toLocalDateTime().toLocalTime();
        return time.toString().substring(0, 5);
    }
}
