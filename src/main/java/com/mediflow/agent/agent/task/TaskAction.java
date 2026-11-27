package com.mediflow.agent.agent.task;

import java.util.Map;

/** Allowlisted diagnostic metadata associated with a task checkpoint. */
public record TaskAction(String action, TaskStatus taskStatus, int turnNo, int stepNo,
                         int attempt, String outcome, String reason, long durationMs,
                         Map<String, Object> summary) {
    public TaskAction {
        summary = Map.copyOf(summary);
    }
}
