package com.mediflow.agent.agent.task;

public record StateTransition(TaskStatus from, TaskStatus to) {
}
