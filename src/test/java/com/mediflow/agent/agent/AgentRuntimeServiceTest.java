package com.mediflow.agent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRuntimeServiceTest {
    private static final Principal OWNER = new Principal(42L, "PATIENT");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

    private InMemoryTaskStateStore stateStore;
    private InMemoryAppointmentApplicationService appointmentService;
    private TaskId taskId;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryTaskStateStore();
        appointmentService = new InMemoryAppointmentApplicationService();
        taskId = stateStore.create(OWNER.userId());
    }

    @Test
    void asksForMissingInformationAndPauses() {
        AgentRuntimeService runtime = runtime(
                new AgentDecision.AskUser("请提供预约日期", Set.of("appointmentDate")));

        AgentTurnResult result = runtime.handleMessage(taskId, OWNER, "我想预约心内科");

        assertEquals(TaskStatus.NEED_MORE_INFO, result.status());
        assertEquals(1, result.task().turnCount());
        assertTrue(result.events().stream().anyMatch(event -> "QUESTION_ASKED".equals(event.type())));
    }

    @Test
    void canProposeDirectlyAfterUnderstanding() {
        AgentRuntimeService runtime = runtime(new AgentDecision.ProposePlan(plan(), "找到一个可预约号源"));

        AgentTurnResult result = runtime.handleMessage(taskId, OWNER, "预约明天下午的心内科");

        assertEquals(TaskStatus.AWAITING_CONFIRMATION, result.status());
        assertTrue(result.task().activePlan().isPresent());
    }

    @Test
    void executesReadToolThenWaitsForExplicitConfirmation() {
        AgentRuntimeService runtime = runtime(
                new AgentDecision.CallReadTool(new ToolCall("findAvailableSlots", Map.of())),
                new AgentDecision.ProposePlan(plan(), "找到一个可预约号源"));

        AgentTurnResult result = runtime.handleMessage(taskId, OWNER, "帮我找号");

        assertEquals(TaskStatus.AWAITING_CONFIRMATION, result.status());
        assertEquals(2, result.task().stepCount());
        assertTrue(result.task().lastToolResult().orElseThrow().success());
        assertEquals(0, appointmentService.createdAppointmentCount());
    }

    @Test
    void unknownToolProducesControlledFailure() {
        AgentRuntimeService runtime = runtime(
                new AgentDecision.CallReadTool(new ToolCall("deleteAppointment", Map.of())));

        AgentTurnResult result = runtime.handleMessage(taskId, OWNER, "删除预约");

        assertEquals(TaskStatus.FAILED, result.status());
        assertTrue(result.events().stream().anyMatch(event -> "READ_TOOL_FAILED".equals(event.type())));
    }

    @Test
    void invalidPlanProducesControlledFailure() {
        PlanDraft planForAnotherPatient = new PlanDraft(
                43L, 10L, 20L, 30L, LocalDate.of(2026, 10, 21),
                "14:00-14:30", Instant.parse("2026-10-13T01:00:00Z"));
        AgentRuntimeService runtime = runtime(
                new AgentDecision.ProposePlan(planForAnotherPatient, "请确认预约方案"));

        AgentTurnResult result = runtime.handleMessage(taskId, OWNER, "预约");

        assertEquals(TaskStatus.FAILED, result.status());
        assertTrue(result.message().contains("方案无效"));
        assertEquals(0, appointmentService.createdAppointmentCount());
    }

    @Test
    void modelExceptionProducesControlledFailure() {
        AgentRuntimeService runtime = new AgentRuntimeService(
                stateStore,
                (context, tools) -> {
                    throw new IllegalStateException("model unavailable");
                },
                new InMemoryReadToolExecutor(),
                appointmentService,
                5,
                CLOCK);

        AgentTurnResult result = runtime.handleMessage(taskId, OWNER, "预约");

        assertEquals(TaskStatus.FAILED, result.status());
        assertEquals("模型决策失败，请稍后重试", result.message());
    }

    @Test
    void validConfirmationExecutesAndVerifiesAppointment() {
        AgentRuntimeService runtime = runtime(new AgentDecision.ProposePlan(plan(), "请确认预约方案"));
        AgentTurnResult proposed = runtime.handleMessage(taskId, OWNER, "预约");
        AppointmentPlan activePlan = proposed.task().activePlan().orElseThrow();

        ConfirmationResult result = runtime.confirmPlan(
                taskId, OWNER, activePlan.id(), activePlan.version(), "idem-1");

        assertTrue(result.success());
        assertEquals(TaskStatus.COMPLETED, result.status());
        assertEquals(1, appointmentService.createdAppointmentCount());
        assertTrue(result.events().stream().anyMatch(event -> "EXECUTION_VERIFIED".equals(event.type())));
    }

    @Test
    void confirmationIsRequiredBeforeAnyWrite() {
        AgentRuntimeService runtime = runtime(new AgentDecision.ProposePlan(plan(), "请确认预约方案"));

        runtime.handleMessage(taskId, OWNER, "预约");

        assertEquals(0, appointmentService.createdAppointmentCount());
    }

    @Test
    void rejectsConfirmationFromAnotherUser() {
        AgentRuntimeService runtime = runtime(new AgentDecision.ProposePlan(plan(), "请确认预约方案"));
        AppointmentPlan activePlan = runtime.handleMessage(taskId, OWNER, "预约")
                .task().activePlan().orElseThrow();

        assertThrows(SecurityException.class, () -> runtime.confirmPlan(
                taskId, new Principal(43L, "PATIENT"), activePlan.id(), activePlan.version(), "idem-2"));
        assertEquals(0, appointmentService.createdAppointmentCount());
    }

    @Test
    void rejectsStalePlanVersionWithoutChangingTaskState() {
        AgentRuntimeService runtime = runtime(new AgentDecision.ProposePlan(plan(), "请确认预约方案"));
        AppointmentPlan activePlan = runtime.handleMessage(taskId, OWNER, "预约")
                .task().activePlan().orElseThrow();

        ConfirmationResult result = runtime.confirmPlan(
                taskId, OWNER, activePlan.id(), activePlan.version() + 1, "idem-3");

        assertFalse(result.success());
        assertEquals(TaskStatus.AWAITING_CONFIRMATION, result.status());
        assertEquals(0, appointmentService.createdAppointmentCount());
    }

    @Test
    void expiredPlanReturnsToSearching() {
        AgentRuntimeService runtime = runtime(new AgentDecision.ProposePlan(expiredPlan(), "请确认预约方案"));
        AppointmentPlan activePlan = runtime.handleMessage(taskId, OWNER, "预约")
                .task().activePlan().orElseThrow();

        ConfirmationResult result = runtime.confirmPlan(
                taskId, OWNER, activePlan.id(), activePlan.version(), "idem-4");

        assertFalse(result.success());
        assertEquals(TaskStatus.SEARCHING, result.status());
        assertEquals(0, appointmentService.createdAppointmentCount());
    }

    @Test
    void occupiedSlotFailsDuringConfirmation() {
        appointmentService.occupySlot(30L);
        AgentRuntimeService runtime = runtime(new AgentDecision.ProposePlan(plan(), "请确认预约方案"));
        AppointmentPlan activePlan = runtime.handleMessage(taskId, OWNER, "预约")
                .task().activePlan().orElseThrow();

        ConfirmationResult result = runtime.confirmPlan(
                taskId, OWNER, activePlan.id(), activePlan.version(), "idem-5");

        assertFalse(result.success());
        assertEquals(TaskStatus.FAILED, result.status());
    }

    @Test
    void repeatedConfirmationDoesNotCreateAnotherAppointment() {
        AgentRuntimeService runtime = runtime(new AgentDecision.ProposePlan(plan(), "请确认预约方案"));
        AppointmentPlan activePlan = runtime.handleMessage(taskId, OWNER, "预约")
                .task().activePlan().orElseThrow();

        runtime.confirmPlan(taskId, OWNER, activePlan.id(), activePlan.version(), "idem-6");
        ConfirmationResult repeated = runtime.confirmPlan(
                taskId, OWNER, activePlan.id(), activePlan.version(), "idem-6");

        assertFalse(repeated.success());
        assertEquals(1, appointmentService.createdAppointmentCount());
    }

    @Test
    void stopsAfterMaximumSteps() {
        AgentRuntimeService runtime = runtime(2,
                new AgentDecision.CallReadTool(new ToolCall("findAvailableSlots", Map.of())),
                new AgentDecision.CallReadTool(new ToolCall("findAvailableSlots", Map.of())),
                new AgentDecision.CallReadTool(new ToolCall("findAvailableSlots", Map.of())));

        AgentTurnResult result = runtime.handleMessage(taskId, OWNER, "持续查询");

        assertEquals(TaskStatus.FAILED, result.status());
        assertEquals(2, result.task().stepCount());
        assertEquals(0, appointmentService.createdAppointmentCount());
    }

    private AgentRuntimeService runtime(AgentDecision... decisions) {
        return runtime(5, decisions);
    }

    private AgentRuntimeService runtime(int maxSteps, AgentDecision... decisions) {
        return new AgentRuntimeService(
                stateStore,
                new ScriptedDecisionModel(decisions),
                new InMemoryReadToolExecutor(),
                appointmentService,
                maxSteps,
                CLOCK);
    }

    private static PlanDraft plan() {
        return new PlanDraft(42L, 10L, 20L, 30L,
                LocalDate.of(2026, 10, 21), "14:00-14:30", Instant.parse("2026-10-13T01:00:00Z"));
    }

    private static PlanDraft expiredPlan() {
        return new PlanDraft(42L, 10L, 20L, 31L,
                LocalDate.of(2026, 10, 21), "15:00-15:30", Instant.parse("2026-10-12T23:00:00Z"));
    }
}
