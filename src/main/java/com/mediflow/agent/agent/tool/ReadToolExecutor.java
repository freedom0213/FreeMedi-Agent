package com.mediflow.agent.agent.tool;

import com.mediflow.agent.agent.task.TaskSnapshot;
import java.util.List;

public interface ReadToolExecutor {
    ToolResult execute(ToolCall call, TaskSnapshot task);

    List<ReadToolSpec> tools();
}
