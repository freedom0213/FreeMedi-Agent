package com.mediflow.agent.agent;

import java.util.Map;

public record ToolCall(String name, Map<String, Object> arguments) {
    public ToolCall {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
