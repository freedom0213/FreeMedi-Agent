package com.mediflow.agent.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.agent.*;
import com.mediflow.agent.appointment.AppointmentWriteService;
import com.mediflow.agent.appointment.MySqlAppointmentApplicationService;
import com.mediflow.agent.appointment.MySqlReadToolExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;

/** Real MySQL tests. The supplied URL must point to an isolated, initially empty test schema. */
@SpringBootTest(properties = "spring.flyway.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mysql"})
@EnabledIfSystemProperty(named = "mediflow.test.db.url", matches = ".+")
class MySqlTaskStateStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

    @Autowired MySqlTaskStateStore store;
    @Autowired TaskPersistenceMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired MySqlAppointmentApplicationService appointmentService;
    @Autowired AppointmentWriteService appointmentWriteService;
    @Autowired MySqlReadToolExecutor readToolExecutor;
    @Autowired MockMvc mockMvc;
    @Autowired AgentRuntime runtime;

    private long patientId;
    private long sessionId;
    private long departmentId;
    private long doctorId;
    private long slotId;
    private String departmentName;
    private String doctorName;
    private TaskId taskId;
    private Principal principal;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("mediflow.llm.api-key", () -> "");
        registry.add("spring.datasource.url", () -> System.getProperty("mediflow.test.db.url"));
        registry.add("spring.datasource.username", () ->
                System.getProperty("mediflow.test.db.username", "root"));
        registry.add("spring.datasource.password", () ->
                System.getProperty("mediflow.test.db.password", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void setUp() {
        String marker = UUID.randomUUID().toString();
        departmentName = "测试科室-" + marker;
        doctorName = "测试医生-" + marker;
        jdbc.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, status)
                VALUES (?, 'synthetic-test-only', '测试患者', 'PATIENT', 'ACTIVE')
                """, marker);
        patientId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, marker);
        jdbc.update("INSERT INTO agent_session(patient_id, title, status) VALUES (?, ?, 'ACTIVE')",
                patientId, marker);
        sessionId = jdbc.queryForObject("SELECT id FROM agent_session WHERE title = ?", Long.class, marker);
        jdbc.update("INSERT INTO department(code, name, status) VALUES (?, ?, 'ACTIVE')", marker, departmentName);
        departmentId = jdbc.queryForObject("SELECT id FROM department WHERE code = ?", Long.class, marker);
        jdbc.update("INSERT INTO doctor(department_id, name, status) VALUES (?, ?, 'ACTIVE')",
                departmentId, doctorName);
        doctorId = jdbc.queryForObject("SELECT id FROM doctor WHERE department_id = ?", Long.class, departmentId);
        jdbc.update("""
                INSERT INTO doctor_schedule(doctor_id, schedule_date, clinic_period, status)
                VALUES (?, '2026-10-21', 'AFTERNOON', 'OPEN')
                """, doctorId);
        long scheduleId = jdbc.queryForObject("SELECT id FROM doctor_schedule WHERE doctor_id = ?",
                Long.class, doctorId);
        jdbc.update("""
                INSERT INTO appointment_slot(schedule_id, slot_no, start_at, end_at, status)
                VALUES (?, 1, '2026-10-21 06:00:00', '2026-10-21 06:30:00', 'AVAILABLE')
                """, scheduleId);
        slotId = jdbc.queryForObject("SELECT id FROM appointment_slot WHERE schedule_id = ?",
                Long.class, scheduleId);
        taskId = store.create(sessionId, patientId);
        principal = new Principal(patientId, "PATIENT");
    }

    @Test
    void migrationsCanBeValidatedAndReappliedWithoutChanges() {
        flyway.validate();
        // Derived from the migration resources so adding a migration cannot silently pass here.
        assertThat(flyway.info().applied()).hasSize(availableMigrationCount());
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
    }

    @Test
    void portalBookingSkipsAgentPlanAndRecordsItsSource() {
        AppointmentWriteService.Actor actor = new AppointmentWriteService.Actor(patientId, "PATIENT");
        AppointmentWriteService.BookingCommand command =
                AppointmentWriteService.BookingCommand.forSlot(actor, "portal-idem-1", slotId);

        AppointmentWriteService.BookingResult booked = appointmentWriteService.book(command);

        assertThat(booked.success()).isTrue();
        assertThat(booked.outcome()).isEqualTo(AppointmentWriteService.BookingResult.Outcome.CREATED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM appointment
                WHERE patient_id = ? AND plan_id IS NULL AND booked_via = 'PORTAL' AND confirmed_version = 0
                """, Long.class, patientId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation_audit_log WHERE patient_id = ? AND plan_id IS NULL",
                Long.class, patientId)).isEqualTo(1);

        AppointmentWriteService.BookingResult repeated = appointmentWriteService.book(command);
        assertThat(repeated.outcome()).isEqualTo(AppointmentWriteService.BookingResult.Outcome.EXISTING);
        assertThat(repeated.appointmentNo()).isEqualTo(booked.appointmentNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class,
                patientId)).isEqualTo(1);
    }

    @Test
    void portalBookingRefusesAnUnavailableSlotWithoutWritingAnything() {
        jdbc.update("UPDATE appointment_slot SET status = 'RESERVED' WHERE id = ?", slotId);

        AppointmentWriteService.BookingResult result = appointmentWriteService.book(
                AppointmentWriteService.BookingCommand.forSlot(
                        new AppointmentWriteService.Actor(patientId, "PATIENT"), "portal-idem-2", slotId));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("SLOT_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class,
                patientId)).isZero();
    }

    @Test
    void portalBookingRefusesToReuseAnIdempotencyKeyForAnotherSlot() {
        long scheduleId = jdbc.queryForObject("SELECT schedule_id FROM appointment_slot WHERE id = ?",
                Long.class, slotId);
        jdbc.update("""
                INSERT INTO appointment_slot(schedule_id, slot_no, start_at, end_at, status)
                VALUES (?, 2, '2026-10-21 06:30:00', '2026-10-21 07:00:00', 'AVAILABLE')
                """, scheduleId);
        long otherSlotId = jdbc.queryForObject(
                "SELECT id FROM appointment_slot WHERE schedule_id = ? AND slot_no = 2", Long.class, scheduleId);
        AppointmentWriteService.Actor actor = new AppointmentWriteService.Actor(patientId, "PATIENT");
        assertThat(appointmentWriteService.book(AppointmentWriteService.BookingCommand
                .forSlot(actor, "portal-idem-3", slotId)).success()).isTrue();

        AppointmentWriteService.BookingResult conflict = appointmentWriteService.book(
                AppointmentWriteService.BookingCommand.forSlot(actor, "portal-idem-3", otherSlotId));

        assertThat(conflict.success()).isFalse();
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class,
                patientId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class,
                otherSlotId)).isEqualTo("AVAILABLE");
    }

    private static int availableMigrationCount() {
        try {
            return new PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/*.sql").length;
        } catch (IOException exception) {
            throw new IllegalStateException("could not list migration resources", exception);
        }
    }

    @Test
    void reloadedTaskKeepsConversationToolsAndPlanAcrossAdapters() {
        AgentRuntime runtime = runtime(model(
                new AgentDecision.AskUser("请提供预约日期", Set.of("date"))));
        runtime.handleMessage(taskId, principal, "我想预约测试科室");

        MySqlTaskStateStore freshStore = new MySqlTaskStateStore(mapper, json, transactionManager);
        DecisionModel resumedModel = model(
                new AgentDecision.CallReadTool(new ToolCall("getAppointmentRules", Map.of())),
                new AgentDecision.ProposePlan(plan(slotId), "请确认方案"));
        AgentRuntime resumed = new AgentRuntimeService(freshStore, (context, tools) -> {
            assertThat(context.task().turns()).hasSize(2);
            assertThat(context.task().turns().get(0).userMessage()).isEqualTo("我想预约测试科室");
            assertThat(context.task().turns().get(0).assistantMessage()).isEqualTo("请提供预约日期");
            return resumedModel.decide(context, tools);
        }, new InMemoryReadToolExecutor(), new InMemoryAppointmentApplicationService(), 5, CLOCK);
        resumed.handleMessage(taskId, principal, "10月21日下午");

        TaskContext restored = freshStore.load(taskId);
        assertThat(restored.snapshot().status()).isEqualTo(TaskStatus.AWAITING_CONFIRMATION);
        assertThat(restored.snapshot().turnCount()).isEqualTo(2);
        assertThat(restored.snapshot().stepCount()).isEqualTo(3);
        assertThat(restored.snapshot().activePlan().orElseThrow().draft()).isEqualTo(plan(slotId));
        assertThat(restored.snapshot().lastToolResult().orElseThrow().success()).isTrue();
        assertThat(count("agent_turn")).isEqualTo(2);
        assertThat(count("agent_tool_call")).isEqualTo(1);
        assertThat(count("agent_plan")).isEqualTo(1);
        assertThat(count("agent_state_event")).isEqualTo(6);
        long rowId = mapper.findTask(taskId.value()).id();
        assertThat(jdbc.queryForObject("SELECT status FROM agent_tool_call WHERE task_id = ?", String.class, rowId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT assistant_message FROM agent_turn WHERE task_id = ? AND turn_no = 2",
                String.class, rowId)).isEqualTo("请确认方案");
    }

    @Test
    void simultaneousSavesAllowOnlyOneWriterAndDoNotDuplicateHistory() throws Exception {
        TaskContext first = store.load(taskId);
        TaskContext second = store.load(taskId);
        first.beginTurn("first request");
        second.beginTurn("second request");
        first.transitionTo(TaskStatus.NEED_MORE_INFO);
        second.transitionTo(TaskStatus.NEED_MORE_INFO);
        first.completeTurn("first question");
        second.completeTurn("second question");
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> saveFirst = () -> saveAfterBarrier(first, start);
            Callable<Boolean> saveSecond = () -> saveAfterBarrier(second, start);
            Future<Boolean> firstResult = workers.submit(saveFirst);
            Future<Boolean> secondResult = workers.submit(saveSecond);
            assertThat((firstResult.get() ? 1 : 0) + (secondResult.get() ? 1 : 0)).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
        assertThat(store.load(taskId).stateVersion()).isEqualTo(1);
        assertThat(count("agent_turn")).isEqualTo(1);
        assertThat(count("agent_state_event")).isEqualTo(2);
    }

    @Test
    void invalidForeignKeyRollsBackTaskPlanTurnAndEventsTogether() {
        TaskContext context = store.load(taskId);
        context.beginTurn("预约不存在的号源");
        context.transitionTo(TaskStatus.PROPOSAL_READY);
        context.createPlan(plan(Long.MAX_VALUE));
        context.transitionTo(TaskStatus.AWAITING_CONFIRMATION);
        context.completeTurn("错误方案");

        assertThatThrownBy(() -> store.save(context)).isInstanceOf(DataIntegrityViolationException.class);

        TaskContext restored = store.load(taskId);
        assertThat(restored.snapshot().status()).isEqualTo(TaskStatus.UNDERSTANDING);
        assertThat(restored.stateVersion()).isZero();
        assertThat(context.stateVersion()).isZero();
        assertThat(context.pendingTransitions()).hasSize(2);
        assertThat(count("agent_turn")).isZero();
        assertThat(count("agent_plan")).isZero();
        assertThat(count("agent_state_event")).isEqualTo(1);
    }

    @Test
    void sessionOwnershipIsCheckedBeforeCreatingTask() {
        assertThatThrownBy(() -> store.create(sessionId, patientId + 1))
                .isInstanceOf(SecurityException.class);
        jdbc.update("UPDATE agent_session SET status = 'CLOSED' WHERE id = ?", sessionId);
        assertThatThrownBy(() -> store.create(sessionId, patientId))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_task WHERE session_id = ?",
                Long.class, sessionId)).isEqualTo(1);
    }

    @Test
    void expiredPlanIsSavedAndNextProposalGetsANewVersion() {
        PlanDraft expired = new PlanDraft(patientId, departmentId, doctorId, slotId,
                LocalDate.of(2026, 10, 21), "14:00-14:30", CLOCK.instant());
        AgentRuntime runtime = runtime(model(new AgentDecision.ProposePlan(expired, "过期方案")));
        AppointmentPlan initial = runtime.handleMessage(taskId, principal, "预约")
                .task().activePlan().orElseThrow();
        ConfirmationResult rejected = runtime.confirmPlan(taskId, principal, initial.id(), 1, "expire-key");
        assertThat(rejected.success()).isFalse();
        assertThat(store.load(taskId).snapshot().activePlan().orElseThrow().status()).isEqualTo(PlanStatus.EXPIRED);

        AgentRuntime next = runtime(model(new AgentDecision.ProposePlan(plan(slotId), "新方案")));
        AppointmentPlan revised = next.handleMessage(taskId, principal, "重新查询")
                .task().activePlan().orElseThrow();
        assertThat(revised.version()).isEqualTo(2);
        assertThat(count("agent_plan")).isEqualTo(2);
        assertThat(mapper.findPlan(mapper.findTask(taskId.value()).id(), 1).status()).isEqualTo("EXPIRED");
    }

    @Test
    void thrownToolExceptionLeavesAnAuditableFailedCall() {
        ReadToolExecutor brokenTool = new ReadToolExecutor() {
            @Override public java.util.List<ReadToolSpec> tools() {
                return java.util.List.of(new ReadToolSpec("broken", "模拟失败"));
            }
            @Override public ToolResult execute(ToolCall call, TaskSnapshot task) {
                throw new IllegalStateException("internal-secret-should-not-be-persisted");
            }
        };
        AgentRuntime runtime = new AgentRuntimeService(store, model(
                new AgentDecision.CallReadTool(new ToolCall("broken", Map.of()))),
                brokenTool, new InMemoryAppointmentApplicationService(), 5, CLOCK);
        assertThat(runtime.handleMessage(taskId, principal, "预约").status()).isEqualTo(TaskStatus.FAILED);
        long rowId = mapper.findTask(taskId.value()).id();
        assertThat(jdbc.queryForObject("SELECT status FROM agent_tool_call WHERE task_id = ?", String.class, rowId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT result_summary FROM agent_tool_call WHERE task_id = ?",
                String.class, rowId)).doesNotContain("internal-secret");
        assertThat(store.load(taskId).snapshot().turns().get(0).status()).isEqualTo("FAILED");
    }

    @Test
    void confirmedAppointmentLocksSlotAndRetriesByIdempotencyKey() {
        PlanDraft draft = futurePlan(slotId);
        AgentRuntime runtime = new AgentRuntimeService(store,
                model(new AgentDecision.ProposePlan(draft, "请确认方案")),
                new InMemoryReadToolExecutor(), appointmentService, 5, CLOCK);
        AgentTurnResult proposed = runtime.handleMessage(taskId, principal, "预约测试科室");
        AppointmentPlan proposedPlan = proposed.task().activePlan().orElseThrow();

        ConfirmationResult confirmed = runtime.confirmPlan(
                taskId, principal, proposedPlan.id(), proposedPlan.version(), "mysql-idem-1");

        assertThat(confirmed.success()).isTrue();
        String appointmentNo = confirmed.events().stream()
                .filter(event -> "EXECUTION_VERIFIED".equals(event.type()))
                .findFirst().orElseThrow().summary();
        assertThat(appointmentService.verify(appointmentNo, principal).success()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class,
                patientId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE patient_id = ?", Long.class,
                patientId)).isEqualTo(1);

        AppointmentPlan confirmedPlan = new AppointmentPlan(proposedPlan.id(), proposedPlan.version(),
                draft, PlanStatus.CONFIRMED);
        AppointmentExecutionResult repeated = appointmentService.createConfirmedAppointment(
                confirmedPlan, principal, "mysql-idem-1");
        assertThat(repeated.success()).isTrue();
        assertThat(repeated.message()).contains("已存在");

        TaskId competingTaskId = store.create(sessionId, patientId);
        AgentRuntime competingRuntime = new AgentRuntimeService(store,
                model(new AgentDecision.ProposePlan(draft, "同一号源的另一个候选方案")),
                new InMemoryReadToolExecutor(), appointmentService, 5, CLOCK);
        AppointmentPlan competingPlan = competingRuntime
                .handleMessage(competingTaskId, principal, "再试一次预约")
                .task().activePlan().orElseThrow();

        AppointmentExecutionResult conflictingKey = appointmentService.createConfirmedAppointment(
                new AppointmentPlan(competingPlan.id(), competingPlan.version(), draft, PlanStatus.CONFIRMED),
                principal, "mysql-idem-1");
        assertThat(conflictingKey.success()).isFalse();
        assertThat(conflictingKey.code()).isEqualTo("IDEMPOTENCY_CONFLICT");

        ConfirmationResult occupied = competingRuntime.confirmPlan(competingTaskId, principal,
                competingPlan.id(), competingPlan.version(), "mysql-idem-2");
        assertThat(occupied.success()).isFalse();
        assertThat(occupied.message()).contains("号源已被占用");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class,
                patientId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE patient_id = ?",
                Long.class, patientId)).isEqualTo(1);
    }

    @Test
    void readToolQueriesOnlyAvailableSlotsAndReturnsStructuredData() {
        ToolResult result = readToolExecutor.execute(
                new ToolCall("findAvailableSlots", Map.of(
                        "departmentId", departmentId,
                        "appointmentDate", "2026-10-21",
                        "clinicPeriod", "afternoon")),
                store.load(taskId).snapshot());

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> slot = ((java.util.List<Map<String, Object>>) result.data().get("slots"))
                .get(0);
        assertThat(slot).containsEntry("slotId", slotId)
                .containsEntry("doctorId", doctorId)
                .containsEntry("departmentId", departmentId)
                .containsEntry("timeRange", "14:00-14:30");
        assertThat(readToolExecutor.tools()).extracting(ReadToolSpec::name)
                .containsExactly("findAvailableSlots", "getAppointmentRules");
    }

    @Test
    void readToolCanResolveDepartmentAndDoctorNamesWithoutInternalIds() {
        ToolResult result = readToolExecutor.execute(
                new ToolCall("findAvailableSlots", Map.of(
                        "departmentName", departmentName,
                        "doctorName", doctorName,
                        "appointmentDate", "2026-10-21",
                        "clinicPeriod", "AFTERNOON")),
                store.load(taskId).snapshot());

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> slot = ((java.util.List<Map<String, Object>>) result.data().get("slots"))
                .get(0);
        assertThat(slot).containsEntry("departmentId", departmentId)
                .containsEntry("doctorId", doctorId)
                .containsEntry("departmentName", departmentName);
    }

    @Test
    void readToolRejectsInvalidArgumentsWithoutRunningUnsafeSql() {
        ToolResult result = readToolExecutor.execute(
                new ToolCall("findAvailableSlots", Map.of("clinicPeriod", "night")),
                store.load(taskId).snapshot());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_TOOL_ARGUMENT");
    }

    @Test
    void readToolAcceptsChineseDateAndPeriodWithoutAskingPatientForFormats() {
        ToolResult result = readToolExecutor.execute(new ToolCall("findAvailableSlots", Map.of(
                "departmentId", departmentId, "appointmentDate", "10月21号", "clinicPeriod", "下午")),
                store.load(taskId).snapshot());

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("count")).isEqualTo(1);
        assertThat(result.data().get("exactMatch")).isEqualTo(true);
    }

    @Test
    void noExactSlotReturnsSameDepartmentAlternativesAndExplainsChangedPreferences() {
        ToolResult result = readToolExecutor.execute(new ToolCall("findAvailableSlots", Map.of(
                "departmentId", departmentId, "appointmentDate", "2026-10-20", "clinicPeriod", "MORNING")),
                store.load(taskId).snapshot());

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("exactMatch")).isEqualTo(false);
        assertThat(result.data().get("requestedDate")).isEqualTo("2026-10-20");
        assertThat(result.data().get("requestedPeriod")).isEqualTo("MORNING");
        @SuppressWarnings("unchecked")
        var slots = (java.util.List<Map<String, Object>>) result.data().get("slots");
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0)).containsEntry("slotId", slotId).containsEntry("departmentId", departmentId)
                .containsEntry("appointmentDate", "2026-10-21").containsEntry("clinicPeriod", "AFTERNOON");
    }

    @Test
    void readToolNeverRecommendsPastSlotsForEarliestRequest() {
        ToolResult result = readToolExecutor.execute(new ToolCall("findAvailableSlots", Map.of(
                "departmentId", departmentId)), store.load(taskId).snapshot());
        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var slots = (java.util.List<Map<String, Object>>) result.data().get("slots");
        assertThat(slots).allSatisfy(slot -> assertThat(LocalDate.parse((String) slot.get("appointmentDate")))
                .isAfterOrEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
    }

    @Test
    void rulesToolReturnsConfirmationGate() {
        ToolResult result = readToolExecutor.execute(
                new ToolCall("getAppointmentRules", Map.of()), store.load(taskId).snapshot());

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("confirmationRequired", true)
                .containsEntry("syntheticData", true);
    }

    @Test
    void agentRuntimeCanContinueAfterMysqlReadToolResult() {
        AgentRuntime runtime = new AgentRuntimeService(store,
                model(
                        new AgentDecision.CallReadTool(new ToolCall("findAvailableSlots", Map.of(
                                "departmentId", departmentId,
                                "appointmentDate", "2026-10-21"))),
                        new AgentDecision.ProposePlan(futurePlan(slotId), "已找到可用号源，请确认")),
                readToolExecutor, new InMemoryAppointmentApplicationService(), 5, CLOCK);

        AgentTurnResult result = runtime.handleMessage(taskId, principal, "帮我找测试科室的号");

        assertThat(result.status()).isEqualTo(TaskStatus.AWAITING_CONFIRMATION);
        assertThat(result.task().lastToolResult().orElseThrow().data().get("count")).isEqualTo(1);
        assertThat(result.task().activePlan()).isPresent();
    }

    @Test
    void changingPendingPlanPersistsInvalidationAndRejectsOldConfirmation() {
        AgentRuntime agent = runtime(model(new AgentDecision.ProposePlan(futurePlan(slotId), "请核对"),
                new AgentDecision.ProposePlan(futurePlan(slotId), "请核对调整后的方案")));
        AppointmentPlan oldPlan = agent.handleMessage(taskId, principal, "预约")
                .task().activePlan().orElseThrow();
        agent.handleMessage(taskId, principal, "改成下午");

        assertThat(mapper.findPlan(mapper.findTask(taskId.value()).id(), oldPlan.version()).status())
                .isEqualTo("INVALIDATED");
        assertThat(store.load(taskId).activePlan().version()).isEqualTo(oldPlan.version() + 1);
        assertThat(agent.confirmPlan(taskId, principal, oldPlan.id(), oldPlan.version(), "stale-plan").success())
                .isFalse();
    }

    @Test
    void httpApiCompletesDemoAppointmentAndReplaysSseEvents() throws Exception {
        String created = mockMvc.perform(post("/api/v1/tasks")
                        .header("X-Demo-User-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNDERSTANDING"))
                .andReturn().getResponse().getContentAsString();
        String apiTaskId = json.readTree(created).get("taskId").asText();

        mockMvc.perform(post("/api/v1/tasks/{taskId}/messages", apiTaskId)
                        .header("X-Demo-User-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"帮我预约心内科的号\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"));

        Principal demoPatient = new Principal(1001L, "PATIENT");
        AppointmentPlan apiPlan = runtime.getTask(new TaskId(apiTaskId), demoPatient)
                .activePlan().orElseThrow();
        String confirm = "{\"planId\":\"" + apiPlan.id().value()
                + "\",\"planVersion\":" + apiPlan.version()
                + ",\"idempotencyKey\":\"api-idem-1\"}";
        mockMvc.perform(post("/api/v1/tasks/{taskId}/confirm", apiTaskId)
                        .header("X-Demo-User-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/events", apiTaskId)
                        .header("X-Demo-User-Id", "1001")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("READ_TOOL_SUCCEEDED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("STREAM_COMPLETED")));
    }

    @Test
    void httpApiStreamsPatientReplyAndReturnsFinalBusinessResult() throws Exception {
        String created = mockMvc.perform(post("/api/v1/tasks")
                        .header("X-Demo-User-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String apiTaskId = json.readTree(created).get("taskId").asText();

        var streamRequest = mockMvc.perform(post("/api/v1/tasks/{taskId}/messages/stream", apiTaskId)
                        .header("X-Demo-User-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"我最近头疼，想先看看该挂哪个科室\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(streamRequest))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MESSAGE_DELTA")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RESULT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("NEED_MORE_INFO")));
    }

    private boolean saveAfterBarrier(TaskContext context, CyclicBarrier start) throws Exception {
        start.await();
        try {
            store.save(context);
            return true;
        } catch (OptimisticLockingFailureException expected) {
            return false;
        }
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id = ?", Long.class,
                mapper.findTask(taskId.value()).id());
    }

    private AgentRuntime runtime(DecisionModel model) {
        return new AgentRuntimeService(store, model, new InMemoryReadToolExecutor(),
                new InMemoryAppointmentApplicationService(), 5, CLOCK);
    }

    private DecisionModel model(AgentDecision... decisions) {
        Queue<AgentDecision> sequence = new ArrayDeque<>(Arrays.asList(decisions));
        return (context, tools) -> sequence.remove();
    }

    private PlanDraft plan(long targetSlotId) {
        return new PlanDraft(patientId, departmentId, doctorId, targetSlotId, LocalDate.of(2026, 10, 21),
                "14:00-14:30", Instant.parse("2026-10-13T01:00:00Z"));
    }

    private PlanDraft futurePlan(long targetSlotId) {
        return new PlanDraft(patientId, departmentId, doctorId, targetSlotId, LocalDate.of(2027, 1, 15),
                "14:00-14:30", Instant.parse("2027-01-01T00:00:00Z"));
    }
}
