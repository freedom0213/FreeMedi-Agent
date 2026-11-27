package com.mediflow.agent.agent.runtime;

import com.mediflow.agent.agent.booking.PlanId;
import com.mediflow.agent.agent.task.TaskId;
import com.mediflow.agent.agent.task.TaskSnapshot;
import java.util.Map;

public interface AgentRuntime {
    AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message);

    default AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message,
                                          java.util.function.Consumer<AgentEvent> progress) {
        return handleMessage(taskId, principal, message);
    }

    AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message,
                                  String clientRequestId, java.util.function.Consumer<AgentEvent> progress);

    default ConfirmationResult confirmPlan(TaskId taskId, Principal principal, PlanId planId,
                                           int planVersion, String idempotencyKey) {
        return confirmPlan(taskId, principal, planId, planVersion, idempotencyKey, false);
    }

    ConfirmationResult confirmPlan(TaskId taskId, Principal principal, PlanId planId,
                                   int planVersion, String idempotencyKey, boolean retry);

    ConfirmationResult checkConfirmation(TaskId taskId, Principal principal, PlanId planId,
                                         int planVersion, String idempotencyKey);

    TaskSnapshot getTask(TaskId taskId, Principal principal);

    AgentTurnResult updateRequirements(TaskId taskId, Principal principal, Map<String, Object> changes);

    AgentTurnResult selectCandidate(TaskId taskId, Principal principal, String batchId, long slotId);
}
