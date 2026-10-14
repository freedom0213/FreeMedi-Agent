package com.mediflow.agent.agent;

public interface TaskStateStore {
    TaskId create(long sessionId, long patientId);

    TaskContext load(TaskId taskId);

    void save(TaskContext context);
}
