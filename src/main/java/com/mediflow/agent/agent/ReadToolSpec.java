package com.mediflow.agent.agent;

import java.util.List;

public record ReadToolSpec(String name, String description, List<Parameter> parameters) {
    public ReadToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        parameters = List.copyOf(parameters);
    }

    public ReadToolSpec(String name, String description) {
        this(name, description, List.of());
    }

    public enum ParameterType { STRING, INTEGER, BOOLEAN }

    public record Parameter(String name, ParameterType type, boolean required, String description) {}
}
