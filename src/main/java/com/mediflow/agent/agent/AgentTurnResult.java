package com.mediflow.agent.agent;

import java.util.List;

public record AgentTurnResult(String message, TaskStatus status, List<AgentEvent> events, TaskSnapshot task) {
    public AgentTurnResult {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
