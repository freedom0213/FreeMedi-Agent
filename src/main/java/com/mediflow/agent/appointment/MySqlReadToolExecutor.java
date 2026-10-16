package com.mediflow.agent.appointment;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mediflow.agent.agent.ReadToolExecutor;
import com.mediflow.agent.agent.ReadToolSpec;
import com.mediflow.agent.agent.TaskSnapshot;
import com.mediflow.agent.agent.ToolCall;
import com.mediflow.agent.agent.ToolResult;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Read-only catalog tools used by the agent to search synthetic appointment slots. */
@Service
@Profile("mysql")
public final class MySqlReadToolExecutor implements ReadToolExecutor {
    private static final int DEFAULT_LIMIT = SlotQueryService.DEFAULT_LIMIT;
    private static final int MAX_LIMIT = SlotQueryService.MAX_LIMIT;
    private static final List<ReadToolSpec> TOOLS = List.of(
            new ReadToolSpec("findAvailableSlots", "按科室、医生和可选日期/时段查询未来可用号源，无匹配时返回同科室近期替代时间"),
            new ReadToolSpec("getAppointmentRules", "查询预约规则"));

    private final SlotQueryService slotQuery;

    public MySqlReadToolExecutor(SlotQueryService slotQuery) {
        this.slotQuery = slotQuery;
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
            LocalDate today = slotQuery.today();
            SlotCriteria criteria = new SlotCriteria(
                    positiveLong(arguments, "departmentId"),
                    name(arguments, "departmentName"),
                    positiveLong(arguments, "doctorId"),
                    name(arguments, "doctorName"),
                    AppointmentDateParser.parse(name(arguments, "appointmentDate"), today),
                    SlotQueryService.normalisePeriod(text(arguments, "clinicPeriod")),
                    limit(arguments.get("limit")));

            SlotSearch search = slotQuery.searchWithFallback(criteria);
            List<Map<String, Object>> slots = search.slots().stream()
                    .map(MySqlReadToolExecutor::toolPayload)
                    .toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", slots.size());
            data.put("slots", slots);
            data.put("truncated", slots.size() == criteria.limit());
            data.put("exactMatch", search.exactMatch());
            data.put("requestedDate", search.requestedDate() == null ? "" : search.requestedDate().toString());
            data.put("requestedPeriod", search.requestedPeriod() == null ? "" : search.requestedPeriod());
            data.put("searchEndDate", search.searchEndDate().toString());
            data.put("notice", notice(search));
            return ToolResult.success(data);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure("INVALID_TOOL_ARGUMENT", exception.getMessage());
        }
    }

    private static String notice(SlotSearch search) {
        if (!search.exactMatch() && !search.slots().isEmpty()) {
            return "原日期或时段无号，以下为同科室的替代时间，需向患者说明变化并等待确认";
        }
        return search.slots().isEmpty() ? "未来90天的查询范围内没有可用号源" : "已找到符合条件的号源";
    }

    /**
     * The agent reads slots as plain JSON, so the key set and its order are part of the tool contract
     * and must stay stable for prompts and recorded tool calls.
     */
    private static Map<String, Object> toolPayload(AvailableSlot slot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slotId", slot.slotId());
        payload.put("departmentId", slot.departmentId());
        payload.put("departmentCode", slot.departmentCode());
        payload.put("departmentName", slot.departmentName());
        payload.put("doctorId", slot.doctorId());
        payload.put("doctorName", slot.doctorName());
        payload.put("doctorTitle", slot.doctorTitle());
        payload.put("appointmentDate", slot.appointmentDate().toString());
        payload.put("clinicPeriod", slot.clinicPeriod());
        payload.put("timeRange", slot.timeRange());
        return payload;
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
        return value.toString().trim();
    }

    private static String name(Map<String, Object> arguments, String name) {
        String text = text(arguments, name);
        if (text == null) {
            return null;
        }
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
}
