package com.mediflow.agent.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class AgentRuntimeService implements AgentRuntime {
    private final TaskStateStore stateStore;
    private final DecisionModel decisionModel;
    private final ReadToolExecutor toolExecutor;
    private final AppointmentApplicationService appointmentService;
    private final int maxSteps;
    private final Clock clock;

    public AgentRuntimeService(TaskStateStore stateStore,
                               DecisionModel decisionModel,
                               ReadToolExecutor toolExecutor,
                               AppointmentApplicationService appointmentService,
                               int maxSteps,
                               Clock clock) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("max steps must be positive");
        }
        this.stateStore = stateStore;
        this.decisionModel = decisionModel;
        this.toolExecutor = toolExecutor;
        this.appointmentService = appointmentService;
        this.maxSteps = maxSteps;
        this.clock = clock;
    }

    @Override
    public AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        TaskContext context = stateStore.load(taskId);
        assertOwner(context, principal);
        context.beginTurn();
        stateStore.save(context);

        List<AgentEvent> events = new ArrayList<>();
        List<ToolResult> toolResults = new ArrayList<>();
        events.add(new AgentEvent("TASK_STARTED", "开始处理用户消息"));

        for (int i = 0; i < maxSteps; i++) {
            context.countStep();
            AgentDecision decision;
            try {
                decision = decisionModel.decide(
                        new DecisionContext(context.snapshot(), message, toolResults), toolExecutor.tools());
            } catch (RuntimeException exception) {
                return failTurn(context, events, "模型决策失败，请稍后重试");
            }
            if (decision == null) {
                return failTurn(context, events, "模型返回了空决策");
            }
            if (decision instanceof AgentDecision.AskUser askUser) {
                context.transitionTo(TaskStatus.NEED_MORE_INFO);
                events.add(new AgentEvent("QUESTION_ASKED", askUser.question()));
                stateStore.save(context);
                return result(askUser.question(), context, events);
            }
            if (decision instanceof AgentDecision.CallReadTool callReadTool) {
                if (context.snapshot().status() != TaskStatus.SEARCHING) {
                    context.transitionTo(TaskStatus.SEARCHING);
                }
                ToolCall call = callReadTool.call();
                events.add(new AgentEvent("READ_TOOL_STARTED", call.name()));
                ToolResult toolResult;
                try {
                    toolResult = toolExecutor.execute(call, context.snapshot());
                } catch (RuntimeException exception) {
                    return failTurn(context, events, "工具执行失败，请稍后重试");
                }
                toolResults.add(toolResult);
                context.setLastToolResult(toolResult);
                if (!toolResult.success()) {
                    context.transitionTo(TaskStatus.FAILED);
                    events.add(new AgentEvent("READ_TOOL_FAILED", toolResult.errorMessage()));
                    stateStore.save(context);
                    return result("查询失败：" + toolResult.errorMessage(), context, events);
                }
                events.add(new AgentEvent("READ_TOOL_SUCCEEDED", call.name()));
                stateStore.save(context);
                continue;
            }
            if (decision instanceof AgentDecision.ProposePlan proposePlan) {
                try {
                    TaskStatus currentStatus = context.snapshot().status();
                    if (currentStatus == TaskStatus.UNDERSTANDING || currentStatus == TaskStatus.SEARCHING) {
                        context.transitionTo(TaskStatus.PROPOSAL_READY);
                    }
                    AppointmentPlan plan = context.createPlan(proposePlan.draft());
                    context.transitionTo(TaskStatus.AWAITING_CONFIRMATION);
                    events.add(new AgentEvent("PLAN_PROPOSED", plan.id().value()));
                    events.add(new AgentEvent("CONFIRMATION_REQUIRED", plan.id().value()));
                    stateStore.save(context);
                    return result(proposePlan.message(), context, events);
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    return failTurn(context, events, "预约方案无效，无法继续处理");
                }
            }
            if (decision instanceof AgentDecision.Finish finish) {
                context.transitionTo(TaskStatus.COMPLETED);
                events.add(new AgentEvent("TASK_COMPLETED", finish.message()));
                stateStore.save(context);
                return result(finish.message(), context, events);
            }
        }

        context.transitionTo(TaskStatus.FAILED);
        events.add(new AgentEvent("TASK_FAILED", "超过最大 Agent 步骤数"));
        stateStore.save(context);
        return result("任务步骤过多，暂时无法完成预约。", context, events);
    }

    @Override
    public ConfirmationResult confirmPlan(TaskId taskId, Principal principal, PlanId planId,
                                          int planVersion, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        TaskContext context = stateStore.load(taskId);
        assertOwner(context, principal);
        if (context.snapshot().status() != TaskStatus.AWAITING_CONFIRMATION) {
            return confirmationFailure("当前任务不在等待确认状态", context);
        }
        AppointmentPlan plan = context.activePlan();
        if (!plan.id().equals(planId) || plan.version() != planVersion) {
            return confirmationFailure("预约方案已发生变化，请重新查询", context);
        }
        if (plan.draft().expiresAt().isBefore(Instant.now(clock))) {
            context.transitionTo(TaskStatus.SEARCHING);
            stateStore.save(context);
            return confirmationFailure("预约方案已过期，请重新查询号源", context);
        }

        List<AgentEvent> events = new ArrayList<>();
        context.transitionTo(TaskStatus.EXECUTING);
        events.add(new AgentEvent("EXECUTION_STARTED", plan.id().value()));
        AppointmentExecutionResult execution = appointmentService
                .createConfirmedAppointment(plan, principal, idempotencyKey);
        if (!execution.success()) {
            context.transitionTo(TaskStatus.FAILED);
            events.add(new AgentEvent("TASK_FAILED", execution.message()));
            stateStore.save(context);
            return new ConfirmationResult(false, execution.message(), context.snapshot().status(), events,
                    context.snapshot());
        }

        context.transitionTo(TaskStatus.VERIFYING);
        AppointmentVerificationResult verification = appointmentService.verify(execution.appointmentId(), principal);
        if (!verification.success()) {
            context.transitionTo(TaskStatus.FAILED);
            events.add(new AgentEvent("TASK_FAILED", verification.message()));
            stateStore.save(context);
            return new ConfirmationResult(false, verification.message(), context.snapshot().status(), events,
                    context.snapshot());
        }

        context.transitionTo(TaskStatus.COMPLETED);
        events.add(new AgentEvent("EXECUTION_VERIFIED", execution.appointmentId()));
        events.add(new AgentEvent("TASK_COMPLETED", verification.message()));
        stateStore.save(context);
        return new ConfirmationResult(true, verification.message(), context.snapshot().status(), events,
                context.snapshot());
    }

    @Override
    public TaskSnapshot getTask(TaskId taskId, Principal principal) {
        TaskContext context = stateStore.load(taskId);
        assertOwner(context, principal);
        return context.snapshot();
    }

    private AgentTurnResult result(String message, TaskContext context, List<AgentEvent> events) {
        return new AgentTurnResult(message, context.snapshot().status(), events, context.snapshot());
    }

    private AgentTurnResult failTurn(TaskContext context, List<AgentEvent> events, String message) {
        context.transitionTo(TaskStatus.FAILED);
        events.add(new AgentEvent("TASK_FAILED", message));
        stateStore.save(context);
        return result(message, context, events);
    }

    private ConfirmationResult confirmationFailure(String message, TaskContext context) {
        return new ConfirmationResult(false, message, context.snapshot().status(),
                List.of(new AgentEvent("CONFIRMATION_REJECTED", message)), context.snapshot());
    }

    private static void assertOwner(TaskContext context, Principal principal) {
        if (principal == null || context.snapshot().patientId() != principal.userId()) {
            throw new SecurityException("task does not belong to current user");
        }
    }
}
