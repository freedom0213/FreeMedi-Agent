package com.mediflow.agent.config;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mediflow.agent.agent.AgentDecision;
import com.mediflow.agent.agent.DecisionContext;
import com.mediflow.agent.agent.DecisionModel;
import com.mediflow.agent.agent.PlanDraft;
import com.mediflow.agent.agent.ReadToolSpec;

/** Deterministic fallback for local demos until the DeepSeek decision model is configured. */
public final class DemoDecisionModel implements DecisionModel {
    @Override
    public AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools) {
        if (context.toolResults().isEmpty()) {
            return new AgentDecision.CallReadTool(
                    new com.mediflow.agent.agent.ToolCall("findAvailableSlots", Map.of("limit", 5)));
        }

        Map<String, Object> data = context.toolResults().get(context.toolResults().size() - 1).data();
        Object rawSlots = data.get("slots");
        if (!(rawSlots instanceof List<?> slots) || slots.isEmpty()) {
            return new AgentDecision.AskUser("当前没有可用号源，请更换日期或时段", Set.of("appointmentDate"));
        }
        if (!(slots.get(0) instanceof Map<?, ?> rawSlot)) {
            return new AgentDecision.Finish("号源查询结果格式异常，请稍后重试");
        }
        try {
            long departmentId = number(rawSlot, "departmentId");
            long doctorId = number(rawSlot, "doctorId");
            long slotId = number(rawSlot, "slotId");
            LocalDate date = LocalDate.parse(text(rawSlot, "appointmentDate"));
            String timeRange = text(rawSlot, "timeRange");
            PlanDraft draft = new PlanDraft(context.task().patientId(), departmentId, doctorId, slotId,
                    date, timeRange, Instant.now().plusSeconds(600));
            return new AgentDecision.ProposePlan(draft, "已找到可用号源，请确认预约方案");
        } catch (RuntimeException exception) {
            return new AgentDecision.Finish("号源查询结果无法生成预约方案，请稍后重试");
        }
    }

    private static long number(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String text(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(name + " is missing");
        }
        return value.toString();
    }
}
