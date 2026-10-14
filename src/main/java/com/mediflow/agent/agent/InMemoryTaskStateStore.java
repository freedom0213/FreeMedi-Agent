package com.mediflow.agent.agent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTaskStateStore implements TaskStateStore {
    private final Map<TaskId, TaskContext> tasks = new ConcurrentHashMap<>();

    public TaskId create(long patientId) {
        return create(1L, patientId);
    }

    @Override
    public TaskId create(long sessionId, long patientId) {
        TaskId id = new TaskId(UUID.randomUUID().toString());
        tasks.put(id, new TaskContext(id, patientId));
        return id;
    }

    @Override
    public TaskContext load(TaskId taskId) {
        TaskContext context = tasks.get(taskId);
        if (context == null) {
            throw new IllegalArgumentException("task not found: " + taskId.value());
        }
        return context;
    }

    @Override
    public void save(TaskContext context) {
        tasks.put(context.snapshot().taskId(), context);
        context.acknowledgeSave(context.stateVersion() + 1);
    }
}
