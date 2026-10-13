package com.mediflow.agent.agent;

import java.util.Map;
import java.util.Optional;

public record TaskSnapshot(
        TaskId taskId,
        long patientId,
        TaskStatus status,
        int stepCount,
        int turnCount,
        Map<String, Object> facts,
        Optional<AppointmentPlan> activePlan,
        Optional<ToolResult> lastToolResult) {

    public TaskSnapshot {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        activePlan = activePlan == null ? Optional.empty() : activePlan;
        lastToolResult = lastToolResult == null ? Optional.empty() : lastToolResult;
    }
}
