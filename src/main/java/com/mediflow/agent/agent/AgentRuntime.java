package com.mediflow.agent.agent;

public interface AgentRuntime {
    AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message);

    ConfirmationResult confirmPlan(TaskId taskId, Principal principal, PlanId planId,
                                    int planVersion, String idempotencyKey);

    TaskSnapshot getTask(TaskId taskId, Principal principal);
}
