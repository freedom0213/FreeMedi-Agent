package com.mediflow.agent.agent;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class TaskContext {
    private final TaskId taskId;
    private final long patientId;
    private final Map<String, Object> facts = new HashMap<>();
    private TaskStatus status = TaskStatus.UNDERSTANDING;
    private int stepCount;
    private int turnCount;
    private int planVersion;
    private AppointmentPlan activePlan;
    private ToolResult lastToolResult;

    public TaskContext(TaskId taskId, long patientId) {
        if (taskId == null || patientId <= 0) {
            throw new IllegalArgumentException("task context is incomplete");
        }
        this.taskId = taskId;
        this.patientId = patientId;
    }

    public synchronized TaskSnapshot snapshot() {
        return new TaskSnapshot(taskId, patientId, status, stepCount, turnCount, facts,
                Optional.ofNullable(activePlan), Optional.ofNullable(lastToolResult));
    }

    public synchronized void beginTurn() {
        if (status == TaskStatus.NEED_MORE_INFO) {
            transitionTo(TaskStatus.UNDERSTANDING);
        }
        if (status != TaskStatus.UNDERSTANDING && status != TaskStatus.SEARCHING) {
            throw new IllegalStateException("task cannot accept a message in state " + status);
        }
        turnCount++;
    }

    public synchronized void countStep() {
        stepCount++;
    }

    public synchronized void putFact(String key, Object value) {
        facts.put(key, value);
    }

    public synchronized void setLastToolResult(ToolResult result) {
        lastToolResult = result;
    }

    public synchronized AppointmentPlan createPlan(PlanDraft draft) {
        if (draft.patientId() != patientId) {
            throw new IllegalArgumentException("plan patient does not match task patient");
        }
        AppointmentPlan plan = new AppointmentPlan(
                new PlanId(taskId.value() + "-plan-" + (planVersion + 1)),
                ++planVersion,
                draft,
                PlanStatus.PROPOSED);
        activePlan = plan;
        return plan;
    }

    public synchronized AppointmentPlan activePlan() {
        if (activePlan == null) {
            throw new IllegalStateException("task has no active plan");
        }
        return activePlan;
    }

    public synchronized void transitionTo(TaskStatus next) {
        if (next == null || !allowedTransitions(status).contains(next)) {
            throw new IllegalStateException("invalid task transition: " + status + " -> " + next);
        }
        status = next;
    }

    private static EnumSet<TaskStatus> allowedTransitions(TaskStatus current) {
        return switch (current) {
            case UNDERSTANDING -> EnumSet.of(TaskStatus.SEARCHING, TaskStatus.NEED_MORE_INFO,
                    TaskStatus.PROPOSAL_READY, TaskStatus.COMPLETED, TaskStatus.FAILED);
            case SEARCHING -> EnumSet.of(TaskStatus.SEARCHING, TaskStatus.NEED_MORE_INFO,
                    TaskStatus.PROPOSAL_READY, TaskStatus.COMPLETED, TaskStatus.FAILED);
            case NEED_MORE_INFO -> EnumSet.of(TaskStatus.UNDERSTANDING, TaskStatus.FAILED);
            case PROPOSAL_READY -> EnumSet.of(TaskStatus.AWAITING_CONFIRMATION, TaskStatus.SEARCHING,
                    TaskStatus.FAILED);
            case AWAITING_CONFIRMATION -> EnumSet.of(TaskStatus.EXECUTING, TaskStatus.SEARCHING,
                    TaskStatus.FAILED, TaskStatus.CANCELLED);
            case EXECUTING -> EnumSet.of(TaskStatus.VERIFYING, TaskStatus.FAILED);
            case VERIFYING -> EnumSet.of(TaskStatus.COMPLETED, TaskStatus.FAILED);
            case COMPLETED, FAILED, CANCELLED -> EnumSet.noneOf(TaskStatus.class);
        };
    }
}
