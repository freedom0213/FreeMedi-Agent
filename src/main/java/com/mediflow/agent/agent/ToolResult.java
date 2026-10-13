package com.mediflow.agent.agent;

import java.util.Map;

public record ToolResult(boolean success, Map<String, Object> data, String errorCode, String errorMessage) {
    public ToolResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static ToolResult success(Map<String, Object> data) {
        return new ToolResult(true, data, null, null);
    }

    public static ToolResult failure(String code, String message) {
        return new ToolResult(false, Map.of(), code, message);
    }
}
