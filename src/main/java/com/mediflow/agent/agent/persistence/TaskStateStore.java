package com.mediflow.agent.agent.persistence;

import com.mediflow.agent.agent.task.TaskContext;
import com.mediflow.agent.agent.task.TaskId;

public interface TaskStateStore {
    TaskId create(long sessionId, long patientId);

    TaskContext load(TaskId taskId);

    void save(TaskContext context);
}
