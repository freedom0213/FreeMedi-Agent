package com.mediflow.agent.agent;

import java.util.List;

public record ConfirmationResult(boolean success, String message, TaskStatus status, List<AgentEvent> events,
                                 TaskSnapshot task) {
    public ConfirmationResult {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
