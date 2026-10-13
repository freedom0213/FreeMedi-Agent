package com.mediflow.agent.agent;

public record TaskId(String value) {
    public TaskId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("task id must not be blank");
        }
    }
}
