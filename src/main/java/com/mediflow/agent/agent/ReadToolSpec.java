package com.mediflow.agent.agent;

public record ReadToolSpec(String name, String description) {
    public ReadToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
    }
}
