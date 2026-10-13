package com.mediflow.agent.agent;

import java.util.List;
import java.util.Map;

public final class InMemoryReadToolExecutor implements ReadToolExecutor {
    private final List<ReadToolSpec> tools = List.of(
            new ReadToolSpec("findAvailableSlots", "查询指定条件下的可用号源"),
            new ReadToolSpec("getAppointmentRules", "查询预约规则"));

    @Override
    public ToolResult execute(ToolCall call, TaskSnapshot task) {
        if (tools.stream().noneMatch(tool -> tool.name().equals(call.name()))) {
            return ToolResult.failure("UNKNOWN_TOOL", "未知只读工具: " + call.name());
        }
        if ("findAvailableSlots".equals(call.name())) {
            return ToolResult.success(Map.of(
                    "departmentId", 10L,
                    "doctorId", 20L,
                    "slotId", 30L,
                    "appointmentDate", "2026-10-21",
                    "timeRange", "14:00-14:30"));
        }
        return ToolResult.success(Map.of("advanceBookingDays", 7, "cancelBeforeHours", 24));
    }

    @Override
    public List<ReadToolSpec> tools() {
        return tools;
    }
}
