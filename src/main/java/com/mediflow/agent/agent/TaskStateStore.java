package com.mediflow.agent.agent;

public interface TaskStateStore {
    TaskContext load(TaskId taskId);

    void save(TaskContext context);
}
