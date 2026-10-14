package com.mediflow.agent.appointment;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only catalog tools used by the agent to search synthetic appointment slots. */
@Service
@Profile("mysql")
public final class MySqlReadToolExecutor implements ReadToolExecutor {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final List<ReadToolSpec> TOOLS = List.of(
            new ReadToolSpec("findAvailableSlots", "按科室、医生、日期或时段查询可用号源"),
            new ReadToolSpec("getAppointmentRules", "查询预约规则"));

    private final JdbcTemplate jdbc;

    public MySqlReadToolExecutor(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
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
            LocalDate appointmentDate = date(arguments, "appointmentDate");
            String clinicPeriod = text(arguments, "clinicPeriod");
            int limit = limit(arguments.get("limit"));

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
                    """);
            List<Object> params = new ArrayList<>();
            if (departmentId != null) {
                sql.append(" AND d.id = ?");
                params.add(departmentId);
            }
            if (doctorId != null) {
                sql.append(" AND dr.id = ?");
                params.add(doctorId);
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

            List<Map<String, Object>> slots = jdbc.query(sql.toString(), (result, rowNum) -> {
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

            return ToolResult.success(Map.of(
                    "count", slots.size(),
                    "slots", slots,
                    "truncated", slots.size() == limit));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            return ToolResult.failure("INVALID_TOOL_ARGUMENT", exception.getMessage());
        }
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

    private static LocalDate date(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(name + " must use yyyy-MM-dd");
        }
    }

    private static String text(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String normalized = value.toString().trim().toUpperCase();
        if (!List.of("MORNING", "AFTERNOON", "EVENING").contains(normalized)) {
            throw new IllegalArgumentException(name + " must be MORNING, AFTERNOON or EVENING");
        }
        return normalized;
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
