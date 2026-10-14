package com.mediflow.agent.agent;

public record ToolExecution(int turnNo, int callNo, ToolCall call, ToolResult result,
                            String status, long durationMs) {
}
