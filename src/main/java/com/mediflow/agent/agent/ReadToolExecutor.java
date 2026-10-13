package com.mediflow.agent.agent;

import java.util.List;

public interface ReadToolExecutor {
    ToolResult execute(ToolCall call, TaskSnapshot task);

    List<ReadToolSpec> tools();
}
