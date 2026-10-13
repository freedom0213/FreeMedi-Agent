package com.mediflow.agent.agent;

public record AgentEvent(String type, String summary) {
    public AgentEvent {
        if (type == null || type.isBlank() || summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("event type and summary must be present");
        }
    }
}
