package com.mediflow.agent.agent;

import java.util.List;

public record DecisionContext(TaskSnapshot task, String userMessage, List<ToolResult> toolResults) {
    public DecisionContext {
        if (task == null || userMessage == null) {
            throw new IllegalArgumentException("decision context is incomplete");
        }
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
    }
}
