package com.mediflow.agent.agent.tool;

import com.mediflow.agent.agent.task.TaskSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Presents several read tools to the runtime as one.
 *
 * <p>The runtime asks for a single {@link ReadToolExecutor}: it needs the whole tool list when
 * declaring tools to the model, and one place to dispatch a call. Each executor still owns one
 * subject and its own dependencies, so the appointment tools do not have to know about hospital
 * material or the other way round.
 *
 * <p>Two tool names resolving to nothing would silently shadow each other, which is the kind of bug
 * that shows up as a model calling a tool that "does not exist", so duplicates are rejected here
 * rather than at request time.
 */
public final class CompositeReadToolExecutor implements ReadToolExecutor {

    private final List<ReadToolSpec> tools;
    private final Map<String, ReadToolExecutor> byToolName;

    public CompositeReadToolExecutor(List<ReadToolExecutor> delegates) {
        Map<String, ReadToolExecutor> dispatch = new LinkedHashMap<>();
        List<ReadToolSpec> declared = new ArrayList<>();
        for (ReadToolExecutor delegate : delegates) {
            if (delegate == null) {
                continue;
            }
            for (ReadToolSpec spec : delegate.tools()) {
                ReadToolExecutor previous = dispatch.putIfAbsent(spec.name(), delegate);
                if (previous != null) {
                    throw new IllegalStateException("只读工具重名：" + spec.name());
                }
                declared.add(spec);
            }
        }
        this.tools = List.copyOf(declared);
        this.byToolName = Map.copyOf(dispatch);
    }

    @Override
    public List<ReadToolSpec> tools() {
        return tools;
    }

    @Override
    public ToolResult execute(ToolCall call, TaskSnapshot task) {
        if (call == null || task == null) {
            return ToolResult.failure("INVALID_TOOL_REQUEST", "工具请求不完整");
        }
        ReadToolExecutor delegate = byToolName.get(call.name());
        if (delegate == null) {
            return ToolResult.failure("UNKNOWN_TOOL", "未知只读工具: " + call.name());
        }
        return delegate.execute(call, task);
    }
}
