package com.mediflow.agent.agent;

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private long stateVersion;
    private int toolCallCount;
    private final List<TaskTurn> turns = new ArrayList<>();
    private final List<StateTransition> pendingTransitions = new ArrayList<>();
    private final List<ToolExecution> pendingTools = new ArrayList<>();

    public TaskContext(TaskId taskId, long patientId) {
        if (taskId == null || patientId <= 0) {
            throw new IllegalArgumentException("task context is incomplete");
        }
        this.taskId = taskId;
        this.patientId = patientId;
    }

    public synchronized TaskSnapshot snapshot() {
        return new TaskSnapshot(taskId, patientId, status, stepCount, turnCount, facts,
                Optional.ofNullable(activePlan), Optional.ofNullable(lastToolResult), turns);
    }

    /** Restores committed data without producing new events or replaying transitions. */
    public static TaskContext restore(TaskSnapshot snapshot, long stateVersion, int toolCallCount) {
        if (stateVersion < 0 || toolCallCount < 0) {
            throw new IllegalArgumentException("invalid persisted task version or tool count");
        }
        TaskContext context = new TaskContext(snapshot.taskId(), snapshot.patientId());
        context.status = snapshot.status();
        context.stepCount = snapshot.stepCount();
        context.turnCount = snapshot.turnCount();
        context.facts.putAll(snapshot.facts());
        context.activePlan = snapshot.activePlan().orElse(null);
        context.planVersion = context.activePlan == null ? 0 : context.activePlan.version();
        context.lastToolResult = snapshot.lastToolResult().orElse(null);
        context.turns.addAll(snapshot.turns());
        context.stateVersion = stateVersion;
        context.toolCallCount = toolCallCount;
        return context;
    }

    public synchronized void beginTurn(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (status == TaskStatus.NEED_MORE_INFO) {
            transitionTo(TaskStatus.UNDERSTANDING);
        }
        if (status == TaskStatus.AWAITING_CONFIRMATION) {
            setPlanStatus(PlanStatus.INVALIDATED);
            transitionTo(TaskStatus.SEARCHING);
        }
        if (status != TaskStatus.UNDERSTANDING && status != TaskStatus.SEARCHING) {
            throw new IllegalStateException("task cannot accept a message in state " + status);
        }
        turnCount++;
        toolCallCount = 0;
        turns.add(new TaskTurn(turnCount, UUID.randomUUID().toString(), message, null, "PROCESSING"));
    }

    public synchronized void completeTurn(String message) {
        TaskTurn turn = turns.get(turns.size() - 1);
        String turnStatus = status == TaskStatus.FAILED ? "FAILED"
                : status == TaskStatus.NEED_MORE_INFO || status == TaskStatus.AWAITING_CONFIRMATION
                ? "WAITING_USER" : "COMPLETED";
        turns.set(turns.size() - 1, new TaskTurn(turn.turnNo(), turn.clientRequestId(),
                turn.userMessage(), message, turnStatus));
    }

    public synchronized int startTool(ToolCall call) {
        int callNo = ++toolCallCount;
        pendingTools.add(new ToolExecution(turnCount, callNo, call, null, "STARTED", 0));
        return callNo;
    }

    public synchronized void finishTool(int callNo, ToolCall call, ToolResult result, long durationMs) {
        pendingTools.removeIf(tool -> tool.turnNo() == turnCount && tool.callNo() == callNo);
        pendingTools.add(new ToolExecution(turnCount, callNo, call, result,
                result.success() ? "SUCCEEDED" : "FAILED", durationMs));
        lastToolResult = result;
    }

    public synchronized long stateVersion() {
        return stateVersion;
    }

    public synchronized List<StateTransition> pendingTransitions() {
        return List.copyOf(pendingTransitions);
    }

    public synchronized List<ToolExecution> pendingTools() {
        return List.copyOf(pendingTools);
    }

    /** Called by the store only after the database transaction commits successfully. */
    public synchronized void acknowledgeSave(long newVersion) {
        stateVersion = newVersion;
        pendingTransitions.clear();
        pendingTools.clear();
    }

    public synchronized void setPlanStatus(PlanStatus planStatus) {
        AppointmentPlan plan = activePlan();
        activePlan = new AppointmentPlan(plan.id(), plan.version(), plan.draft(), planStatus);
    }

    public synchronized void countStep() {
        stepCount++;
    }

    public synchronized void putFact(String key, Object value) {
        facts.put(key, value);
    }

    public synchronized void setRecommendation(TriageRecommendation recommendation) {
        facts.remove("triageRecommendation");
        if (recommendation != null) {
            facts.put("triageRecommendation", Map.of("departmentId", recommendation.departmentId(),
                    "departmentName", recommendation.departmentName(), "reason", recommendation.reason(),
                    "turnNo", turnCount));
        }
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
        pendingTransitions.add(new StateTransition(status, next));
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
