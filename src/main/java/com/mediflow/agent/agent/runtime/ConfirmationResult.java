package com.mediflow.agent.agent.runtime;

import com.mediflow.agent.agent.task.TaskSnapshot;
import com.mediflow.agent.agent.task.TaskStatus;
import java.util.List;

public record ConfirmationResult(boolean success, String message, TaskStatus status, List<AgentEvent> events,
                                 TaskSnapshot task, String code) {
    public ConfirmationResult(boolean success, String message, TaskStatus status, List<AgentEvent> events,
                              TaskSnapshot task) {
        this(success, message, status, events, task, null);
    }
    public ConfirmationResult {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
