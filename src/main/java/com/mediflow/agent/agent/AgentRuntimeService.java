package com.mediflow.agent.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentRuntimeService implements AgentRuntime {
    private static final Logger logger = LoggerFactory.getLogger(AgentRuntimeService.class);

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
        return handleMessage(taskId, principal, message, event -> { });
    }

    @Override
    public AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message,
                                         java.util.function.Consumer<AgentEvent> progress) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        TaskContext context = stateStore.load(taskId);
        assertOwner(context, principal);
        context.beginTurn(message);
        context.setRecommendation(null);
        stateStore.save(context);

        List<AgentEvent> events = new ArrayList<>();
        List<ToolResult> toolResults = new ArrayList<>();
        events.add(new AgentEvent("TASK_STARTED", "开始处理用户消息"));

        for (int i = 0; i < maxSteps; i++) {
            context.countStep();
            AgentDecision decision;
            try {
                decision = decisionModel.decide(
                        new DecisionContext(context.snapshot(), message, toolResults), toolExecutor.tools(), progress);
            } catch (RuntimeException exception) {
                Throwable cause = exception;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                logger.warn("Agent decision failed: taskId={}, exceptionType={}, rootCauseType={}, reason={}",
                        taskId.value(), exception.getClass().getSimpleName(), cause.getClass().getSimpleName(),
                        exception instanceof ModelDecisionException failure ? failure.reason() : "PROVIDER_ERROR");
                if (context.snapshot().status() != TaskStatus.NEED_MORE_INFO) {
                    context.transitionTo(TaskStatus.NEED_MORE_INFO);
                }
                events.add(new AgentEvent("MODEL_RETRY_REQUIRED", "暂时无法完成处理，请重试"));
                return result("刚才暂时没能完成处理，预约尚未提交。请稍后重试，或继续告诉我您的就诊需求。",
                        context, events);
            }
            if (decision == null) {
                return failTurn(context, events, "模型返回了空决策");
            }
            if (decision instanceof AgentDecision.AskUser askUser) {
                context.setRecommendation(askUser.recommendation());
                context.transitionTo(TaskStatus.NEED_MORE_INFO);
                events.add(new AgentEvent("QUESTION_ASKED", askUser.question()));
                return result(askUser.question(), context, events);
            }
            if (decision instanceof AgentDecision.CallReadTool callReadTool) {
                if (context.snapshot().status() != TaskStatus.SEARCHING) {
                    context.transitionTo(TaskStatus.SEARCHING);
                }
                ToolCall call = callReadTool.call();
                events.add(new AgentEvent("READ_TOOL_STARTED", call.name()));
                progress.accept(new AgentEvent("READ_TOOL_STARTED", call.name()));
                int callNo = context.startTool(call);
                stateStore.save(context);
                long started = System.nanoTime();
                ToolResult toolResult;
                try {
                    toolResult = toolExecutor.execute(call, context.snapshot());
                } catch (RuntimeException exception) {
                    context.finishTool(callNo, call, ToolResult.failure("TOOL_ERROR", "工具执行失败"),
                            (System.nanoTime() - started) / 1_000_000);
                    return failTurn(context, events, "工具执行失败，请稍后重试");
                }
                if (toolResult == null) {
                    context.finishTool(callNo, call, ToolResult.failure("EMPTY_TOOL_RESULT", "工具返回空结果"),
                            (System.nanoTime() - started) / 1_000_000);
                    return failTurn(context, events, "工具返回了空结果");
                }
                toolResults.add(toolResult);
                context.finishTool(callNo, call, toolResult, (System.nanoTime() - started) / 1_000_000);
                if (!toolResult.success()) {
                    // A rejected argument means the patient's wording was not understood, not that the
                    // task is dead. Keep it retryable so the next turn can ask again instead of
                    // forcing the patient to start over.
                    if (isRecoverableToolFailure(toolResult)) {
                        if (context.snapshot().status() != TaskStatus.NEED_MORE_INFO) {
                            context.transitionTo(TaskStatus.NEED_MORE_INFO);
                        }
                        events.add(new AgentEvent("READ_TOOL_FAILED", toolResult.errorMessage()));
                        return result("我还需要再确认一下：" + toolResult.errorMessage(), context, events);
                    }
                    context.transitionTo(TaskStatus.FAILED);
                    events.add(new AgentEvent("READ_TOOL_FAILED", toolResult.errorMessage()));
                    return result("查询失败：" + toolResult.errorMessage(), context, events);
                }
                events.add(new AgentEvent("READ_TOOL_SUCCEEDED", call.name()));
                progress.accept(new AgentEvent("READ_TOOL_SUCCEEDED", call.name()));
                stateStore.save(context);
                continue;
            }
            if (decision instanceof AgentDecision.ProposePlan proposePlan) {
                context.setRecommendation(proposePlan.recommendation());
                AppointmentPlan plan;
                try {
                    TaskStatus currentStatus = context.snapshot().status();
                    if (currentStatus == TaskStatus.UNDERSTANDING || currentStatus == TaskStatus.SEARCHING) {
                        context.transitionTo(TaskStatus.PROPOSAL_READY);
                    }
                    // Preserve the queried slot metadata for the confirmation card if rules were fetched later.
                    toolResults.stream().filter(toolResult -> toolResult.success()
                            && toolResult.data().get("slots") instanceof List<?> slots
                            && slots.stream().anyMatch(value -> value instanceof java.util.Map<?, ?> slot
                                    && String.valueOf(proposePlan.draft().slotId()).equals(String.valueOf(slot.get("slotId")))))
                            .reduce((previous, current) -> current).ifPresent(context::setLastToolResult);
                    plan = context.createPlan(proposePlan.draft());
                    context.transitionTo(TaskStatus.AWAITING_CONFIRMATION);
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    return failTurn(context, events, "预约方案无效，无法继续处理");
                }
                events.add(new AgentEvent("PLAN_PROPOSED", plan.id().value()));
                events.add(new AgentEvent("CONFIRMATION_REQUIRED", plan.id().value()));
                return result(proposePlan.message(), context, events);
            }
            if (decision instanceof AgentDecision.Finish finish) {
                context.transitionTo(TaskStatus.COMPLETED);
                events.add(new AgentEvent("TASK_COMPLETED", finish.message()));
                return result(finish.message(), context, events);
            }
        }

        context.transitionTo(TaskStatus.FAILED);
        events.add(new AgentEvent("TASK_FAILED", "超过最大 Agent 步骤数"));
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
        if (!plan.draft().expiresAt().isAfter(Instant.now(clock))) {
            context.setPlanStatus(PlanStatus.EXPIRED);
            context.transitionTo(TaskStatus.SEARCHING);
            stateStore.save(context);
            return confirmationFailure("预约方案已过期，请重新查询号源", context);
        }

        List<AgentEvent> events = new ArrayList<>();
        context.transitionTo(TaskStatus.EXECUTING);
        context.setPlanStatus(PlanStatus.CONFIRMED);
        events.add(new AgentEvent("EXECUTION_STARTED", plan.id().value()));
        // Persist the ownership claim before invoking a write; a competing request must fail its CAS.
        stateStore.save(context);
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
        context.setPlanStatus(PlanStatus.EXECUTED);
        context.putFact("appointmentId", execution.appointmentId());
        stateStore.save(context);
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
        context.completeTurn(message);
        stateStore.save(context);
        return new AgentTurnResult(message, context.snapshot().status(), events, context.snapshot());
    }

    private AgentTurnResult failTurn(TaskContext context, List<AgentEvent> events, String message) {
        context.transitionTo(TaskStatus.FAILED);
        events.add(new AgentEvent("TASK_FAILED", message));
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

    /** Tool rejections caused by how the patient phrased a request can be corrected in a later turn. */
    private static boolean isRecoverableToolFailure(ToolResult result) {
        return "INVALID_TOOL_ARGUMENT".equals(result.errorCode());
    }
}
