package com.mediflow.agent.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentRuntimeService implements AgentRuntime {
    private static final Logger logger = LoggerFactory.getLogger(AgentRuntimeService.class);

    private final TaskStateStore stateStore;
    private final ReadToolExecutor toolExecutor;
    private final AppointmentApplicationService appointmentService;
    private final int maxSteps;
    private final Clock clock;
    private final DepartmentCatalog departmentCatalog;
    private final AppointmentOptions appointmentOptions;
    private final Supplier<AgentRequestSettings> settings;

    private record Request(AgentRequestSettings settings, long deadline) {
        Request(AgentRequestSettings settings) {
            this(settings, settings.calls().deadline());
        }
    }

    public AgentRuntimeService(TaskStateStore stateStore,
                               DecisionModel decisionModel,
                               ReadToolExecutor toolExecutor,
                               AppointmentApplicationService appointmentService,
                               int maxSteps,
                               Clock clock) {
        this(stateStore, decisionModel, toolExecutor, appointmentService, maxSteps, clock, name -> 0L);
    }

    public AgentRuntimeService(TaskStateStore stateStore,
                               DecisionModel decisionModel,
                               ReadToolExecutor toolExecutor,
                               AppointmentApplicationService appointmentService,
                               int maxSteps,
                               Clock clock,
                               DepartmentCatalog departmentCatalog) {
        this(stateStore, decisionModel, toolExecutor, appointmentService, maxSteps, clock, departmentCatalog, null);
    }

    public AgentRuntimeService(TaskStateStore stateStore, DecisionModel decisionModel, ReadToolExecutor toolExecutor,
                               AppointmentApplicationService appointmentService, int maxSteps, Clock clock,
                               DepartmentCatalog departmentCatalog, AppointmentOptions appointmentOptions) {
        this(stateStore, decisionModel, toolExecutor, appointmentService, maxSteps, clock, departmentCatalog,
                appointmentOptions, new AgentCallPolicy(Runnable::run, 15000, 5000, 45000));
    }

    public AgentRuntimeService(TaskStateStore stateStore, DecisionModel decisionModel, ReadToolExecutor toolExecutor,
                               AppointmentApplicationService appointmentService, int maxSteps, Clock clock,
                               DepartmentCatalog departmentCatalog, AppointmentOptions appointmentOptions,
                               AgentCallPolicy calls) {
        this(stateStore, toolExecutor, appointmentService, maxSteps, clock, departmentCatalog, appointmentOptions,
                () -> new AgentRequestSettings(decisionModel, "built-in", calls, Map.of()));
    }

    public AgentRuntimeService(TaskStateStore stateStore, ReadToolExecutor toolExecutor,
                               AppointmentApplicationService appointmentService, int maxSteps, Clock clock,
                               DepartmentCatalog departmentCatalog, AppointmentOptions appointmentOptions,
                               Supplier<AgentRequestSettings> settings) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("max steps must be positive");
        }
        this.stateStore = stateStore;
        this.toolExecutor = toolExecutor;
        this.appointmentService = appointmentService;
        this.maxSteps = maxSteps;
        this.clock = clock;
        this.departmentCatalog = departmentCatalog;
        this.appointmentOptions = appointmentOptions;
        this.settings = settings;
    }

    @Override
    public AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message) {
        return handleMessage(taskId, principal, message, event -> { });
    }

    @Override
    public AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message,
                                         java.util.function.Consumer<AgentEvent> progress) {
        return handleMessage(taskId, principal, message, null, progress);
    }

    @Override
    public AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message,
                                         String clientRequestId, java.util.function.Consumer<AgentEvent> progress) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        TaskContext context = stateStore.load(taskId);
        assertOwner(context, principal);
        if (clientRequestId != null && !clientRequestId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("消息请求标识无效。");
        }
        if (clientRequestId != null) {
            var previous = context.snapshot().turns().stream()
                    .filter(turn -> turn.clientRequestId().equalsIgnoreCase(clientRequestId)).findFirst();
            if (previous.isPresent()) {
                TaskTurn turn = previous.get();
                if (!message.equals(turn.userMessage())) {
                    throw new IllegalArgumentException("此消息标识已用于另一条消息。");
                }
                boolean ready = turn.assistantMessage() != null;
                String reply = ready ? turn.assistantMessage() : "原消息仍在处理，请稍后重试读取结果。";
                return new AgentTurnResult(reply, context.snapshot().status(),
                        List.of(new AgentEvent(ready ? "MESSAGE_REPLAYED" : "MESSAGE_IN_PROGRESS", reply)), context.snapshot());
            }
        }
        var request = new Request(settings.get());
        var decisionModel = request.settings().model();
        context.beginTurn(message, appointmentOptions != null,
                clientRequestId == null ? UUID.randomUUID().toString() : clientRequestId);
        if (appointmentOptions == null) context.setRecommendation(null);
        context.trace("TURN_STARTED", 1, "STARTED", null, 0, Map.of());
        stateStore.save(context);

        List<AgentEvent> events = new ArrayList<>();
        List<ToolResult> toolResults = new ArrayList<>();
        events.add(new AgentEvent("TASK_STARTED", "开始处理用户消息"));

        if (context.snapshot().taskType() == TaskType.VISIT_CONSULTATION) {
            return answerVisitConsultation(context, message, events, progress, request);
        }

        for (int i = 0; i < maxSteps; i++) {
            context.countStep();
            AgentDecision decision;
            try {
                var input = new DecisionContext(context.snapshot(), message, List.copyOf(toolResults), appointmentOptions != null);
                var availableTools = toolExecutor.tools().stream()
                        .filter(tool -> request.settings().toolEnabled(tool.name())).toList();
                decision = request.settings().calls().run(AgentCallPolicy.Kind.MODEL, request.deadline(),
                        () -> decisionModel.decide(input, availableTools),
                        answer -> answer == null ? "MODEL_EMPTY_RESULT" : null,
                        (attempt, answer) -> traceAttempt(context, "MODEL_DECISION", attempt, Map.of(
                                "adapter", decisionModel.getClass().getSimpleName(),
                                "modelVersion", request.settings().modelVersion(),
                                "decision", answer == null ? "" : answer.getClass().getSimpleName())));
            } catch (AgentCallPolicy.Failure failure) {
                logger.warn("Agent decision failed: taskId={}, reason={}", taskId.value(), failure.code());
                if (!preserveOptions(context) && context.snapshot().status() != TaskStatus.NEED_MORE_INFO) {
                    context.transitionTo(TaskStatus.NEED_MORE_INFO);
                }
                events.add(new AgentEvent("MODEL_RETRY_REQUIRED", "暂时无法完成处理，请重试"));
                return result("刚才暂时没能完成处理，预约尚未提交。请稍后重试，或继续告诉我您的就诊需求。",
                        context, events);
            }
            if (decision instanceof AgentDecision.AskUser askUser) {
                if (!preserveOptions(context)) {
                    context.setRecommendation(resolveRecommendation(askUser.recommendation()));
                    context.transitionTo(TaskStatus.NEED_MORE_INFO);
                }
                events.add(new AgentEvent("QUESTION_ASKED", askUser.question()));
                return result(askUser.question(), context, events);
            }
            if (decision instanceof AgentDecision.CallReadTool callReadTool) {
                ToolCall call = callReadTool.call();
                if (KnowledgeConsultation.TOOL_NAME.equals(call.name())) {
                    return consultKnowledge(context, events, call, progress, request);
                }
                if (appointmentOptions != null && call.name().equals("findAvailableSlots")) {
                    return searchOptions(context, events, authorisedFlexibility(call, message), progress, request, "CONVERSATION");
                }
                if (!request.settings().toolEnabled(call.name())) {
                    traceTool(context, call, new AgentCallPolicy.Attempt(1, 0, "TOOL_DISABLED"), "CONVERSATION", null);
                    return result("这项查询暂不可用，请稍后再试。", context, events);
                }
                if (!preserveOptions(context) && context.snapshot().status() != TaskStatus.SEARCHING) {
                    context.transitionTo(TaskStatus.SEARCHING);
                }
                ToolResult originalToolResult = context.snapshot().lastToolResult().orElse(null);
                events.add(new AgentEvent("READ_TOOL_STARTED", call.name()));
                progress.accept(new AgentEvent("READ_TOOL_STARTED", call.name()));
                int callNo = context.startTool(call);
                stateStore.save(context);
                long started = System.nanoTime();
                ToolResult toolResult = executeReadTool(context, call, request);
                toolResults.add(toolResult);
                context.finishTool(callNo, call, toolResult, AgentCallPolicy.elapsed(started));
                if (preserveOptions(context)) context.setLastToolResult(originalToolResult);
                if (!toolResult.success()) {
                    // A rejected argument means the patient's wording was not understood, not that the
                    // task is dead. Keep it retryable so the next turn can ask again instead of
                    // forcing the patient to start over.
                    if (isRecoverableToolFailure(toolResult)) {
                        if (!preserveOptions(context) && context.snapshot().status() != TaskStatus.NEED_MORE_INFO) {
                            context.transitionTo(TaskStatus.NEED_MORE_INFO);
                        }
                        events.add(new AgentEvent("READ_TOOL_FAILED", toolResult.errorMessage()));
                        return result("我还需要再确认一下：" + toolResult.errorMessage(), context, events);
                    }
                    events.add(new AgentEvent("READ_TOOL_FAILED", toolResult.errorMessage()));
                    if (isTemporaryToolFailure(toolResult.errorCode())) {
                        context.setLastToolResult(originalToolResult);
                        events.add(new AgentEvent("TOOL_RETRY_REQUIRED", toolResult.errorMessage()));
                        return result(toolResult.errorMessage(), context, events);
                    }
                    if (!preserveOptions(context)) context.transitionTo(TaskStatus.FAILED);
                    return result("查询失败：" + toolResult.errorMessage(), context, events);
                }
                events.add(new AgentEvent("READ_TOOL_SUCCEEDED", call.name()));
                progress.accept(new AgentEvent("READ_TOOL_SUCCEEDED", call.name()));
                stateStore.save(context);
                continue;
            }
            if (decision instanceof AgentDecision.ProposePlan proposePlan) {
                if (appointmentOptions != null) {
                    return result("请先查询号源并选择一个候选，再核对预约信息。", context, events);
                }
                context.setRecommendation(resolveRecommendation(proposePlan.recommendation()));
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
            if (decision instanceof AgentDecision.Reply reply) {
                return result(reply.message(), context, events);
            }
            if (decision instanceof AgentDecision.Finish finish) {
                if (preserveOptions(context)) return result(finish.message(), context, events);
                context.transitionTo(TaskStatus.COMPLETED);
                events.add(new AgentEvent("TASK_COMPLETED", finish.message()));
                return result(finish.message(), context, events);
            }
        }

        if (!preserveOptions(context)) context.transitionTo(TaskStatus.FAILED);
        context.trace("TURN_LIMIT", 1, "FAILED", "STEP_LIMIT", 0, Map.of());
        events.add(new AgentEvent("TASK_FAILED", "超过最大 Agent 步骤数"));
        return result("任务步骤过多，暂时无法完成预约。", context, events);
    }

    private AgentTurnResult answerVisitConsultation(TaskContext context, String question, List<AgentEvent> events,
                                                     Consumer<AgentEvent> progress, Request request) {
        if (question.matches("(?s).*(再约|重新预约|重新挂号|另约|再挂|确认预约|改成|换成|改到|换到|重选号源|帮我取消).*")) {
            return result("这里可以查询本次预约和就诊准备。要办理另一笔预约，请从挂号入口开始。", context, events);
        }
        if (!question.matches("(?s).*(要带|需要带|带什么|需携带|准备|规则|怎么取号).*")
                && question.matches("(?s).*(预约信息|预约详情|预约编号|我约的|这次看哪|哪个医生|哪位医生|哪个科室|哪天|"
                        + "什么时候看|几点看|几点开始|就诊时间|约的谁).*")) {
            Map<String, Object> appointment = facts(context, "visitAppointment");
            return result("这次预约是 %s %s，%s %s。预约编号 %s。".formatted(
                    appointment.get("appointmentDate"), appointment.get("timeRange"), appointment.get("departmentName"),
                    appointment.get("doctorName"), appointment.get("appointmentNo")), context, events);
        }
        return consultKnowledge(context, events,
                new ToolCall(KnowledgeConsultation.TOOL_NAME, Map.of("query", question)), progress, request);
    }

    private AgentTurnResult consultKnowledge(TaskContext context, List<AgentEvent> events, ToolCall call,
                                              Consumer<AgentEvent> progress, Request request) {
        AgentEvent start = new AgentEvent("READ_TOOL_STARTED", call.name());
        events.add(start);
        progress.accept(start);
        int number = context.startTool(call);
        ToolResult previous = context.snapshot().lastToolResult().orElse(null);
        stateStore.save(context);
        long startedAt = System.nanoTime();
        ToolResult toolResult = executeReadTool(context, call, request);
        context.finishTool(number, call, toolResult, AgentCallPolicy.elapsed(startedAt));
        context.setLastToolResult(previous);
        KnowledgeConsultation consultation = KnowledgeConsultation.from(toolResult);
        var replies = new java.util.LinkedHashMap<>(facts(context, KnowledgeConsultation.FACT_KEY));
        replies.put(Integer.toString(context.snapshot().turnCount()), consultation.toFact());
        context.putFact(KnowledgeConsultation.FACT_KEY, Map.copyOf(replies));
        AgentEvent end = new AgentEvent(toolResult.success() ? "READ_TOOL_SUCCEEDED" : "READ_TOOL_FAILED", call.name());
        events.add(end);
        progress.accept(end);
        if (isTemporaryToolFailure(toolResult.errorCode())) {
            events.add(new AgentEvent("TOOL_RETRY_REQUIRED", consultation.message()));
        }
        return result(consultation.message(), context, events);
    }

    @Override
    public AgentTurnResult updateRequirements(TaskId taskId, Principal principal, Map<String, Object> changes) {
        TaskContext context = stateStore.load(taskId);
        assertOwner(context, principal);
        assertBookingTask(context);
        if (!List.of(TaskStatus.UNDERSTANDING, TaskStatus.NEED_MORE_INFO, TaskStatus.SEARCHING,
                TaskStatus.PROPOSAL_READY, TaskStatus.AWAITING_CONFIRMATION).contains(context.snapshot().status())) {
            throw new IllegalArgumentException("这次任务已结束或正在提交，请新建预约需求。");
        }
        return searchOptions(context, new ArrayList<>(), new ToolCall("findAvailableSlots", changes),
                event -> { }, new Request(settings.get()), "PAGE");
    }

    @Override
    public AgentTurnResult selectCandidate(TaskId taskId, Principal principal, String batchId, long slotId) {
        TaskContext context = stateStore.load(taskId);
        assertOwner(context, principal);
        assertBookingTask(context);
        Map<String, Object> batch = facts(context, "candidateBatch");
        if (!preserveOptions(context) || !batchId.equals(batch.get("id"))
                || !(batch.get("slots") instanceof List<?> slots)) {
            throw new IllegalArgumentException("候选已更新，请重新查找和选择。");
        }
        Map<?, ?> candidate = slots.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .filter(row -> Long.toString(slotId).equals(String.valueOf(row.get("slotId"))))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("请选择本次查询中的号源。"));
        var draft = appointmentOptions.draft(principal.userId(), candidate, Instant.now(clock).plusSeconds(600));
        var previous = context.snapshot().activePlan();
        if (context.snapshot().status() == TaskStatus.AWAITING_CONFIRMATION) {
            if (previous.orElseThrow().draft().slotId() == slotId
                    && previous.orElseThrow().draft().expiresAt().isAfter(Instant.now(clock))) {
                return new AgentTurnResult("请核对预约信息。", context.snapshot().status(), List.of(), context.snapshot());
            }
            context.setPlanStatus(PlanStatus.INVALIDATED);
            context.transitionTo(TaskStatus.SEARCHING);
            context.transitionTo(TaskStatus.PROPOSAL_READY);
        }
        AppointmentPlan plan = context.createPlan(draft);
        context.transitionTo(TaskStatus.AWAITING_CONFIRMATION);
        tracePlan(context, "CANDIDATE_SELECTED", "SUCCEEDED", null, 0);
        stateStore.save(context);
        return new AgentTurnResult("请核对所选号源的医生、日期和时间，再确认预约。", context.snapshot().status(),
                List.of(new AgentEvent("PLAN_PROPOSED", plan.id().value()),
                        new AgentEvent("CONFIRMATION_REQUIRED", plan.id().value())), context.snapshot());
    }

    private AgentTurnResult searchOptions(TaskContext context, List<AgentEvent> events, ToolCall call,
                                         Consumer<AgentEvent> progress, Request request, String source) {
        boolean conversation = source.equals("CONVERSATION");
        AgentEvent started = new AgentEvent("READ_TOOL_STARTED", call.name());
        events.add(started);
        progress.accept(started);
        int number = conversation ? context.startTool(call) : 0;
        long startedAt = System.nanoTime();
        String message;
        ToolResult toolResult;
        try {
            if (!request.settings().toolEnabled(call.name())) {
                traceTool(context, call, new AgentCallPolicy.Attempt(1, 0, "TOOL_DISABLED"), source, null);
                throw AgentCallPolicy.Failure.disabledTool();
            }
            var saved = facts(context, "requirements");
            var search = request.settings().calls().run(AgentCallPolicy.Kind.TOOL, request.deadline(),
                    request.settings().toolTimeout(call.name()),
                    () -> appointmentOptions.search(saved, call.arguments(), source),
                    value -> value == null ? "TOOL_EMPTY_RESULT" : null,
                    (attempt, value) -> traceTool(context, call, attempt, source,
                            value == null ? null : value.slots().size()));
            message = applySearch(context, search);
            toolResult = ToolResult.success(Map.of("slots", search.slots(), "count", search.slots().size(),
                    "notice", search.message(), "exactMatch",
                    search.slots().stream().allMatch(slot -> List.of().equals(slot.get("differences")))));
        } catch (AgentCallPolicy.Failure failure) {
            if (failure.getCause() instanceof IllegalArgumentException invalid) {
                if (!conversation) {
                    stateStore.save(context);
                    throw invalid;
                }
                message = invalid.getMessage();
            } else {
                message = "暂时无法查询号源，已保留原预约条件，请稍后再试。";
            }
            toolResult = ToolResult.failure(failure.code(), message);
        }
        if (conversation) {
            ToolResult previous = context.snapshot().lastToolResult().orElse(null);
            context.finishTool(number, call, toolResult, AgentCallPolicy.elapsed(startedAt));
            if (!toolResult.success() || context.snapshot().status() == TaskStatus.AWAITING_CONFIRMATION) {
                context.setLastToolResult(previous);
            }
        }
        AgentEvent finished = new AgentEvent(toolResult.success() ? "READ_TOOL_SUCCEEDED" : "READ_TOOL_FAILED", call.name());
        events.add(finished);
        progress.accept(finished);
        if (!toolResult.success()) events.add(new AgentEvent("TOOL_RETRY_REQUIRED", message));
        if (conversation) return result(message, context, events);
        stateStore.save(context);
        return new AgentTurnResult(message, context.snapshot().status(), events, context.snapshot());
    }

    private ToolResult executeReadTool(TaskContext context, ToolCall call, Request request) {
        if (!request.settings().toolEnabled(call.name())) {
            traceTool(context, call, new AgentCallPolicy.Attempt(1, 0, "TOOL_DISABLED"), "CONVERSATION", null);
            return ToolResult.failure("TOOL_DISABLED", "这项查询暂不可用，请稍后再试。");
        }
        TaskSnapshot input = context.snapshot();
        try {
            return request.settings().calls().run(AgentCallPolicy.Kind.TOOL, request.deadline(),
                    request.settings().toolTimeout(call.name()), () -> toolExecutor.execute(call, input),
                    value -> value == null ? "EMPTY_TOOL_RESULT" : value.errorCode(),
                    (attempt, value) -> traceTool(context, call, attempt, "CONVERSATION",
                            value != null && value.data().get("count") instanceof Number count ? count : null));
        } catch (AgentCallPolicy.Failure failure) {
            return ToolResult.failure(failure.code(), "暂时无法完成查询，请稍后重试。");
        }
    }

    private void traceTool(TaskContext context, ToolCall call, AgentCallPolicy.Attempt attempt,
                           String source, Number count) {
        var summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("tool", call.name());
        summary.put("source", source);
        summary.put("parameterFields", call.arguments().keySet().stream()
                .filter(java.util.Set.of("query", "departmentId", "departmentName", "doctorId", "doctorName",
                        "appointmentDate", "clinicPeriod", "dateFlexible", "periodFlexible", "limit", "topK", "docType")::contains)
                .sorted().toList());
        var parameters = new java.util.LinkedHashMap<String, Object>();
        call.arguments().forEach((key, value) -> {
            boolean safe = switch (key) {
                case "departmentId", "doctorId", "limit", "topK" -> value instanceof Number;
                case "dateFlexible", "periodFlexible" -> value instanceof Boolean;
                case "appointmentDate" -> value instanceof String text && text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}");
                case "clinicPeriod" -> java.util.Set.of("MORNING", "AFTERNOON", "EVENING").contains(value);
                default -> false;
            };
            if (safe) parameters.put(key, value);
        });
        if (!parameters.isEmpty()) summary.put("parameters", parameters);
        if (count != null) summary.put("resultCount", count);
        traceAttempt(context, "READ_TOOL_ATTEMPT", attempt, summary);
    }

    private void traceAttempt(TaskContext context, String action, AgentCallPolicy.Attempt attempt,
                              Map<String, Object> summary) {
        context.trace(action, attempt.number(), attempt.reason() == null ? "SUCCEEDED" : "FAILED",
                attempt.reason(), attempt.durationMs(), summary);
    }

    private static boolean isTemporaryToolFailure(String code) {
        return AgentCallPolicy.retryable(code)
                || List.of("AGENT_DEADLINE", "AGENT_BUSY", "AGENT_INTERRUPTED").contains(code == null ? "" : code);
    }

    private static ToolCall authorisedFlexibility(ToolCall call, String message) {
        var changes = new java.util.LinkedHashMap<>(call.arguments());
        if (!message.matches("(?s).*(优先|最好|也可以|也行|可换|替代|备选).*")) {
            changes.replaceAll((key, value) -> (key.equals("dateFlexible") || key.equals("periodFlexible"))
                    && Boolean.TRUE.equals(value) ? Boolean.FALSE : value);
        }
        return new ToolCall(call.name(), changes);
    }

    private String applySearch(TaskContext context, AppointmentOptions.Search search) {
        TaskStatus status = context.snapshot().status();
        if (status == TaskStatus.AWAITING_CONFIRMATION
                && comparable(facts(context, "requirements")).equals(comparable(search.requirements()))) {
            return "预约条件未变，已保留当前确认单。";
        }
        if (status == TaskStatus.AWAITING_CONFIRMATION) context.setPlanStatus(PlanStatus.INVALIDATED);
        if (status == TaskStatus.NEED_MORE_INFO) context.transitionTo(TaskStatus.UNDERSTANDING);
        if (context.snapshot().status() != TaskStatus.SEARCHING) context.transitionTo(TaskStatus.SEARCHING);
        context.setRecommendation(null);
        context.putFact("requirements", search.requirements());
        context.putFact("candidateBatch", Map.of("id", UUID.randomUUID().toString(),
                "slots", search.slots(), "message", search.message()));
        context.setLastToolResult(null);
        context.transitionTo(search.slots().isEmpty() ? TaskStatus.NEED_MORE_INFO : TaskStatus.PROPOSAL_READY);
        return search.message();
    }

    private boolean preserveOptions(TaskContext context) {
        TaskStatus status = context.snapshot().status();
        return appointmentOptions != null && (status == TaskStatus.PROPOSAL_READY || status == TaskStatus.AWAITING_CONFIRMATION);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> facts(TaskContext context, String key) {
        Object value = context.snapshot().facts().get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static Map<String, String> comparable(Map<String, Object> requirements) {
        var values = new java.util.HashMap<String, String>();
        requirements.forEach((key, value) -> {
            if (!key.equals("sources")) values.put(key, String.valueOf(value));
        });
        return values;
    }

    @Override
    public ConfirmationResult confirmPlan(TaskId taskId, Principal principal, PlanId planId,
                                          int planVersion, String idempotencyKey, boolean retry) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        TaskContext context = confirmationContext(taskId, principal);
        if (!selectedPlan(context, planId, planVersion)) return changedPlan(context);
        if (context.snapshot().status() != TaskStatus.AWAITING_CONFIRMATION) {
            ConfirmationResult checked = reconcile(context, principal, idempotencyKey);
            if (retry && "NOT_FOUND".equals(checked.code()) && checked.status() == TaskStatus.EXECUTING
                    && idempotencyKey.equals(facts(context, "confirmationAttempt").get("idempotencyKey"))) {
                return executeOriginal(context, principal, idempotencyKey, new ArrayList<>());
            }
            return checked;
        }
        if (retry) {
            ConfirmationResult checked = reconcile(context, principal, idempotencyKey);
            if (!"NOT_FOUND".equals(checked.code())) return checked;
        }
        AppointmentPlan plan = context.activePlan();
        if (!plan.draft().expiresAt().isAfter(Instant.now(clock))) {
            context.setPlanStatus(PlanStatus.EXPIRED);
            context.transitionTo(TaskStatus.SEARCHING);
            context.removeFact("candidateBatch");
            tracePlan(context, "CONFIRMATION_REJECTED", "FAILED", "PLAN_EXPIRED", 0);
            stateStore.save(context);
            return confirmationFailure("PLAN_EXPIRED", "预约方案已过期，请重新查询号源", context);
        }

        context.putFact("confirmationAttempt", Map.of("planId", planId.value(), "version", planVersion,
                "idempotencyKey", idempotencyKey));
        context.transitionTo(TaskStatus.EXECUTING);
        context.setPlanStatus(PlanStatus.CONFIRMED);
        tracePlan(context, "CONFIRMATION_CLAIMED", "STARTED", null, 0);
        stateStore.save(context);
        return executeOriginal(context, principal, idempotencyKey, new ArrayList<>(
                List.of(new AgentEvent("EXECUTION_STARTED", planId.value()))));
    }

    @Override
    public ConfirmationResult checkConfirmation(TaskId taskId, Principal principal, PlanId planId,
                                                 int planVersion, String idempotencyKey) {
        TaskContext context = confirmationContext(taskId, principal);
        if (!selectedPlan(context, planId, planVersion)) return changedPlan(context);
        return reconcile(context, principal, idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey);
    }

    private TaskContext confirmationContext(TaskId taskId, Principal principal) {
        TaskContext context = stateStore.load(taskId);
        assertOwner(context, principal);
        assertBookingTask(context);
        return context;
    }

    private static boolean selectedPlan(TaskContext context, PlanId planId, int version) {
        return context.snapshot().activePlan().filter(plan -> plan.id().equals(planId) && plan.version() == version).isPresent();
    }

    private ConfirmationResult changedPlan(TaskContext context) {
        return confirmationFailure("PLAN_CHANGED", "预约方案已发生变化，请核对当前任务。", context);
    }

    /** A check can read the booking and advance its checkpoint. It never calls the creation service. */
    private ConfirmationResult reconcile(TaskContext context, Principal principal, String idempotencyKey) {
        AppointmentPlan plan = context.activePlan();
        var attempt = facts(context, "confirmationAttempt");
        boolean claimed = plan.id().value().equals(attempt.get("planId"));
        if (claimed && idempotencyKey != null && !idempotencyKey.equals(attempt.get("idempotencyKey"))) {
            return confirmationFailure("IDEMPOTENCY_CONFLICT", "请使用这笔预约原来的请求标识核验。", context);
        }
        if (claimed && attempt.get("code") instanceof String code) {
            return confirmationFailure(code, String.valueOf(attempt.get("message")), context);
        }
        TaskStatus status = context.snapshot().status();
        if (claimed && status == TaskStatus.COMPLETED && context.snapshot().facts().containsKey("appointmentId")) {
            return new ConfirmationResult(true, "已核验原有预约。", status, List.of(), context.snapshot());
        }
        java.util.Optional<AppointmentExecutionResult> original;
        long started = System.nanoTime();
        try {
            String key = idempotencyKey == null && claimed ? (String) attempt.get("idempotencyKey") : idempotencyKey;
            original = appointmentService.findOriginal(plan.id(), plan.version(), principal, key);
        } catch (RuntimeException unavailable) {
            logger.warn("Confirmation lookup unavailable: taskId={}, type={}",
                    context.snapshot().taskId().value(), unavailable.getClass().getSimpleName());
            return confirmationFailure("OUTCOME_UNKNOWN", "暂时无法核验原预约，请稍后查结果。", context);
        }
        if (original.isPresent()) {
            var found = original.get();
            if (status == TaskStatus.EXECUTING && (claimed || found.success())) {
                tracePlan(context, "APPOINTMENT_LOOKUP", found.success() ? "SUCCEEDED" : "FAILED",
                        found.code(), AgentCallPolicy.elapsed(started));
                return applyExecution(context, principal, found, new ArrayList<>());
            }
            if (!found.success()) return confirmationFailure(found.code(), found.message(), context);
            if (status == TaskStatus.COMPLETED) {
                return new ConfirmationResult(true, "已核验原有预约。", status, List.of(), context.snapshot());
            }
            if (status == TaskStatus.VERIFYING || status == TaskStatus.FAILED && plan.status() == PlanStatus.EXECUTED) {
                if (status == TaskStatus.FAILED) {
                    context.transitionTo(TaskStatus.VERIFYING);
                    context.putFact("appointmentId", found.appointmentId());
                    stateStore.save(context);
                }
                return verifyOriginal(context, principal, found.appointmentId(), new ArrayList<>());
            }
        }
        if (claimed && status == TaskStatus.VERIFYING
                && context.snapshot().facts().get("appointmentId") instanceof String existingNo) {
            return verifyOriginal(context, principal, existingNo, new ArrayList<>());
        }
        if (idempotencyKey != null && (status == TaskStatus.AWAITING_CONFIRMATION || status == TaskStatus.EXECUTING && claimed)) {
            return confirmationFailure("NOT_FOUND", "暂未查到原请求的结果。可再次核验，或重试同一次提交。", context);
        }
        return confirmationFailure("OUTCOME_UNKNOWN", "暂时无法核验这笔预约，请先查看我的预约。", context);
    }

    private ConfirmationResult executeOriginal(TaskContext context, Principal principal, String key,
                                               List<AgentEvent> events) {
        AppointmentExecutionResult execution;
        long started = System.nanoTime();
        try {
            execution = appointmentService.createConfirmedAppointment(context.activePlan(), principal, key);
        } catch (RuntimeException unavailable) {
            logger.warn("Appointment write outcome unknown: taskId={}, type={}",
                    context.snapshot().taskId().value(), unavailable.getClass().getSimpleName());
            tracePlan(context, "APPOINTMENT_WRITE", "UNKNOWN", "OUTCOME_UNKNOWN", AgentCallPolicy.elapsed(started));
            stateStore.save(context);
            return confirmationFailure("OUTCOME_UNKNOWN", "提交结果暂未确定，请先核验原预约。", context);
        }
        tracePlan(context, "APPOINTMENT_WRITE", execution.success() ? "SUCCEEDED" : "FAILED",
                execution.success() ? null : execution.code(), AgentCallPolicy.elapsed(started));
        return applyExecution(context, principal, execution, events);
    }

    private ConfirmationResult applyExecution(TaskContext context, Principal principal,
                                               AppointmentExecutionResult execution, List<AgentEvent> events) {
        if (!execution.success()) {
            String code = execution.code() == null ? "BOOKING_REJECTED" : execution.code();
            boolean reselection = List.of("SLOT_UNAVAILABLE", "SLOT_INVALID", "SLOT_NOT_FOUND",
                    "PLAN_EXPIRED", "IDEMPOTENCY_CONFLICT").contains(code);
            context.setPlanStatus(PlanStatus.REJECTED);
            context.removeFact("candidateBatch");
            var attempt = new java.util.LinkedHashMap<>(facts(context, "confirmationAttempt"));
            attempt.put("code", code);
            attempt.put("message", execution.message());
            context.putFact("confirmationAttempt", Map.copyOf(attempt));
            context.transitionTo(reselection ? TaskStatus.SEARCHING : TaskStatus.FAILED);
            events.add(new AgentEvent("CONFIRMATION_REJECTED", code));
            stateStore.save(context);
            return new ConfirmationResult(false, execution.message(), context.snapshot().status(), events,
                    context.snapshot(), code);
        }

        context.transitionTo(TaskStatus.VERIFYING);
        context.setPlanStatus(PlanStatus.EXECUTED);
        context.putFact("appointmentId", execution.appointmentId());
        stateStore.save(context);
        return verifyOriginal(context, principal, execution.appointmentId(), events);
    }

    private ConfirmationResult verifyOriginal(TaskContext context, Principal principal, String appointmentNo,
                                               List<AgentEvent> events) {
        AppointmentVerificationResult verification;
        long started = System.nanoTime();
        try {
            verification = appointmentService.verify(appointmentNo, principal);
        } catch (RuntimeException unavailable) {
            logger.warn("Appointment verification pending: taskId={}, type={}",
                    context.snapshot().taskId().value(), unavailable.getClass().getSimpleName());
            verification = null;
        }
        if (verification == null || !verification.success()) {
            tracePlan(context, "APPOINTMENT_VERIFY", "PENDING", "VERIFY_PENDING", AgentCallPolicy.elapsed(started));
            stateStore.save(context);
            return confirmationFailure("VERIFY_PENDING", "已收到原预约编号，暂未完成核验，请稍后查结果。", context);
        }

        context.transitionTo(TaskStatus.COMPLETED);
        tracePlan(context, "APPOINTMENT_VERIFY", "SUCCEEDED", null, AgentCallPolicy.elapsed(started));
        events.add(new AgentEvent("EXECUTION_VERIFIED", appointmentNo));
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
        context.trace("TURN_REPLY_SAVED", 1, "SUCCEEDED", null, 0,
                Map.of("responseEvents", events.stream().map(AgentEvent::type).toList()));
        stateStore.save(context);
        return new AgentTurnResult(message, context.snapshot().status(), events, context.snapshot());
    }

    private AgentTurnResult failTurn(TaskContext context, List<AgentEvent> events, String message) {
        if (!preserveOptions(context)) context.transitionTo(TaskStatus.FAILED);
        events.add(new AgentEvent("TASK_FAILED", message));
        return result(message, context, events);
    }

    private ConfirmationResult confirmationFailure(String code, String message, TaskContext context) {
        return new ConfirmationResult(false, message, context.snapshot().status(), List.of(), context.snapshot(), code);
    }

    private static void assertOwner(TaskContext context, Principal principal) {
        if (principal == null || context.snapshot().patientId() != principal.userId()) {
            throw new SecurityException("task does not belong to current user");
        }
    }

    private static void assertBookingTask(TaskContext context) {
        if (context.snapshot().taskType() != TaskType.APPOINTMENT_BOOKING) {
            throw new SecurityException("就诊准备咨询只读，无法办理预约。");
        }
    }

    /** Tool rejections caused by how the patient phrased a request can be corrected in a later turn. */
    private static boolean isRecoverableToolFailure(ToolResult result) {
        return "INVALID_TOOL_ARGUMENT".equals(result.errorCode());
    }

    private void tracePlan(TaskContext context, String action, String outcome, String reason, long durationMs) {
        AppointmentPlan plan = context.activePlan();
        context.trace(action, 1, outcome, reason, durationMs,
                Map.of("planVersion", plan.version(), "slotId", plan.draft().slotId()));
    }

    /**
     * Fills in a real department id when the catalog knows the recommended department. A direction
     * this hospital does not offer keeps id 0, which the interface reads as "not in the catalog"
     * rather than as an id that points at nothing.
     */
    private TriageRecommendation resolveRecommendation(TriageRecommendation recommendation) {
        if (recommendation == null || recommendation.departmentId() > 0) {
            return recommendation;
        }
        long resolved = departmentCatalog.findActiveDepartmentId(recommendation.departmentName());
        return resolved > 0
                ? new TriageRecommendation(resolved, recommendation.departmentName(), recommendation.reason())
                : recommendation;
    }
}
