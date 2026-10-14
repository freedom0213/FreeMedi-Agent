package com.mediflow.agent.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/** Real MySQL tests. The supplied URL must point to an isolated, initially empty test schema. */
@SpringBootTest(properties = "spring.flyway.enabled=true")
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

    private long patientId;
    private long sessionId;
    private long departmentId;
    private long doctorId;
    private long slotId;
    private TaskId taskId;
    private Principal principal;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
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
        jdbc.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, status)
                VALUES (?, 'synthetic-test-only', '测试患者', 'PATIENT', 'ACTIVE')
                """, marker);
        patientId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, marker);
        jdbc.update("INSERT INTO agent_session(patient_id, title, status) VALUES (?, ?, 'ACTIVE')",
                patientId, marker);
        sessionId = jdbc.queryForObject("SELECT id FROM agent_session WHERE title = ?", Long.class, marker);
        jdbc.update("INSERT INTO department(code, name, status) VALUES (?, '测试科室', 'ACTIVE')", marker);
        departmentId = jdbc.queryForObject("SELECT id FROM department WHERE code = ?", Long.class, marker);
        jdbc.update("INSERT INTO doctor(department_id, name, status) VALUES (?, '测试医生', 'ACTIVE')", departmentId);
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
        assertThat(flyway.info().applied()).hasSize(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
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
}
