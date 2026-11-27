package com.mediflow.agent.agent.model;

import com.mediflow.agent.agent.task.TaskSnapshot;
import com.mediflow.agent.agent.tool.ToolResult;
import java.util.List;

public record DecisionContext(TaskSnapshot task, String userMessage, List<ToolResult> toolResults,
                              boolean selectionRequired) {
    public DecisionContext(TaskSnapshot task, String userMessage, List<ToolResult> toolResults) {
        this(task, userMessage, toolResults, false);
    }
    public DecisionContext {
        if (task == null || userMessage == null) {
            throw new IllegalArgumentException("decision context is incomplete");
        }
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
    }
}
