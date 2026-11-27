package com.mediflow.agent.agent.persistence;

import com.mediflow.agent.agent.booking.AppointmentApplicationService;
import com.mediflow.agent.agent.booking.AppointmentExecutionResult;
import com.mediflow.agent.agent.booking.AppointmentPlan;
import com.mediflow.agent.agent.booking.AppointmentVerificationResult;
import com.mediflow.agent.agent.booking.DepartmentCatalog;
import com.mediflow.agent.agent.booking.InMemoryAppointmentApplicationService;
import com.mediflow.agent.agent.booking.PlanDraft;
import com.mediflow.agent.agent.booking.PlanId;
import com.mediflow.agent.agent.booking.PlanStatus;
import com.mediflow.agent.agent.model.AgentDecision;
import com.mediflow.agent.agent.model.DecisionModel;
import com.mediflow.agent.agent.runtime.AgentCallPolicy;
import com.mediflow.agent.agent.runtime.AgentRuntime;
import com.mediflow.agent.agent.runtime.AgentRuntimeService;
import com.mediflow.agent.agent.runtime.AgentTurnResult;
import com.mediflow.agent.agent.runtime.ConfirmationResult;
import com.mediflow.agent.agent.runtime.Principal;
import com.mediflow.agent.agent.task.TaskContext;
import com.mediflow.agent.agent.task.TaskId;
import com.mediflow.agent.agent.task.TaskSnapshot;
import com.mediflow.agent.agent.task.TaskStatus;
import com.mediflow.agent.agent.task.TriageRecommendation;
import com.mediflow.agent.agent.tool.InMemoryReadToolExecutor;
import com.mediflow.agent.agent.tool.ReadToolExecutor;
import com.mediflow.agent.agent.tool.ReadToolSpec;
import com.mediflow.agent.agent.tool.ToolCall;
import com.mediflow.agent.agent.tool.ToolResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.service.guide.MySqlTaskApplicationService;
import com.mediflow.agent.service.appointment.lifecycle.AppointmentWriteService;
import com.mediflow.agent.agent.booking.MySqlAppointmentApplicationService;
import com.mediflow.agent.service.appointment.query.MySqlHospitalDirectory;
import com.mediflow.agent.agent.tool.MySqlReadToolExecutor;
import com.mediflow.agent.service.appointment.query.SlotCriteria;
import com.mediflow.agent.mapper.appointment.AppointmentLockMapper;
import com.mediflow.agent.service.appointment.policy.HospitalRules;
import com.mediflow.agent.service.appointment.query.SlotQueryService;
import com.mediflow.agent.service.appointment.query.SlotSearch;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real MySQL tests. The supplied URL must point to an isolated, initially empty test schema. */
@SpringBootTest(properties = "spring.flyway.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mysql"})
@EnabledIfSystemProperty(named = "mediflow.test.db.url", matches = ".+")
class MySqlTaskStateStoreTest {
    private static final String DEMO_A_PASSWORD = "Mediflow-Demo-A!2026";
    private static final String DEMO_B_PASSWORD = "Mediflow-Demo-B!2026";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);
    /** Slot searches use the real clock, so the fixture clinic must stay ahead of today. */
    private static final LocalDate FIXTURE_DATE = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(5);
    private static final String FIXTURE_DAY = FIXTURE_DATE.toString();

    @Autowired MySqlTaskStateStore store;
    @Autowired TaskPersistenceMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AppointmentLockMapper appointmentLocks;
    @Autowired HospitalRules hospitalRules;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired MySqlAppointmentApplicationService appointmentService;
    @Autowired AppointmentWriteService appointmentWriteService;
    @Autowired DepartmentCatalog departmentCatalog;
    @Autowired MySqlReadToolExecutor readToolExecutor;
    @Autowired SlotQueryService slotQueryService;
    @Autowired MySqlHospitalDirectory directory;
    @Autowired MockMvc mockMvc;
    @Autowired AgentRuntime runtime;
    @Autowired MySqlTaskApplicationService taskApplication;

    private String fixtureUsername;
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
        registry.add("mediflow.knowledge.index", () -> "none");
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
        fixtureUsername = marker;
        departmentName = "测试科室-" + marker;
        doctorName = "测试医生-" + marker;
        jdbc.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, status)
                SELECT ?, password_hash, '测试患者', 'PATIENT', 'ACTIVE' FROM sys_user WHERE id = 1001
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
                VALUES (?, ?, 'AFTERNOON', 'OPEN')
                """, doctorId, FIXTURE_DATE);
        long scheduleId = jdbc.queryForObject("SELECT id FROM doctor_schedule WHERE doctor_id = ?",
                Long.class, doctorId);
        jdbc.update("""
                INSERT INTO appointment_slot(schedule_id, slot_no, start_at, end_at, status)
                VALUES (?, 1, ?, ?, 'AVAILABLE')
                """, scheduleId, FIXTURE_DAY + " 06:00:00", FIXTURE_DAY + " 06:30:00");
        slotId = jdbc.queryForObject("SELECT id FROM appointment_slot WHERE schedule_id = ?",
                Long.class, scheduleId);
        taskId = store.create(sessionId, patientId);
        principal = new Principal(patientId, "PATIENT");
    }

    @Test
    void appointmentMapperRowLocksAreHeldUntilTheSpringTransactionEnds() {
        long scheduleId = jdbc.queryForObject("SELECT schedule_id FROM appointment_slot WHERE id = ?", Long.class, slotId);
        var rows = List.of(Map.entry("sys_user", patientId), Map.entry("department", departmentId),
                Map.entry("doctor", doctorId), Map.entry("doctor_schedule", scheduleId));
        var transaction = new TransactionTemplate(transactionManager);
        var contender = new TransactionTemplate(transactionManager);
        contender.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transaction.executeWithoutResult(status -> {
            assertThat(appointmentLocks.lockPatient(patientId)).containsExactly(patientId);
            assertThat(appointmentLocks.lockDepartment(departmentId)).isEqualTo("ACTIVE");
            assertThat(appointmentLocks.lockDoctor(departmentId, doctorId)).isEqualTo("ACTIVE");
            assertThat(appointmentLocks.lockSchedule(doctorId, scheduleId)).isEqualTo("OPEN");

            for (var row : rows) {
                String sql = "SELECT id FROM " + row.getKey() + " WHERE id = ? FOR UPDATE NOWAIT";
                assertThatThrownBy(() -> contender.execute(tx -> jdbc.queryForObject(sql, Long.class, row.getValue())))
                        .isInstanceOfSatisfying(DataAccessException.class, error ->
                                assertThat(error.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,
                                        cause -> assertThat(cause.getErrorCode()).isEqualTo(3572)));
            }
        });

        contender.executeWithoutResult(status -> {
            for (var row : rows) {
                String sql = "SELECT id FROM " + row.getKey() + " WHERE id = ? FOR UPDATE NOWAIT";
                assertThat(jdbc.queryForObject(sql, Long.class, row.getValue())).isEqualTo(row.getValue());
            }
        });
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
    void cancellationDeadlineIsExclusiveAndRejectedAttemptsKeepTheSlot() {
        var booked = appointmentWriteService.book(AppointmentWriteService.BookingCommand.forSlot(
                new AppointmentWriteService.Actor(patientId, "PATIENT"), "cutoff-fixture", slotId));
        var deadline = FIXTURE_DATE.minusDays(1).atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        var atDeadline = new AppointmentWriteService(jdbc.getDataSource(), transactionManager,
                Clock.fixed(deadline, ZoneOffset.UTC), hospitalRules, appointmentLocks);
        assertThat(atDeadline.cancel(booked.appointmentNo(), patientId).code()).isEqualTo("CANCEL_CUTOFF_PASSED");
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("RESERVED");
        var before = new AppointmentWriteService(jdbc.getDataSource(), transactionManager,
                Clock.fixed(deadline.minusMillis(1), ZoneOffset.UTC), hospitalRules, appointmentLocks);
        assertThat(before.cancel(booked.appointmentNo(), patientId).success()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("AVAILABLE");
    }

    @Test
    void portalStatusAndRetryUseTheOriginalSlotAndKey() throws Exception {
        MockHttpSession session = login(fixtureUsername, DEMO_A_PASSWORD);
        String key = "s5-portal-" + UUID.randomUUID();
        String originalBody = json.writeValueAsString(Map.of("slotId", slotId, "idempotencyKey", key));
        mockMvc.perform(postAs(session, "/api/v1/appointments/status")
                        .contentType(MediaType.APPLICATION_JSON).content(originalBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        var created = json.readTree(mockMvc.perform(postAs(session, "/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(originalBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("CREATED"))
                .andReturn().getResponse().getContentAsString());
        String appointmentNo = created.get("appointmentNo").asText();
        for (String path : List.of("/api/v1/appointments/status", "/api/v1/appointments")) {
            mockMvc.perform(postAs(session, path).contentType(MediaType.APPLICATION_JSON).content(originalBody))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.appointmentNo").value(appointmentNo));
            mockMvc.perform(postAs(session, path).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("slotId", slotId + 1, "idempotencyKey", key))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class, patientId))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_COMMIT", "AFTER_COMMIT", "VERIFY"})
    void pendingConfirmationChecksTheOriginalBeforeAnyRetry(String interruption) {
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var verifications = new java.util.concurrent.atomic.AtomicInteger();
        AppointmentApplicationService fault = new AppointmentApplicationService() {
            @Override
            public AppointmentExecutionResult createConfirmedAppointment(AppointmentPlan selected, Principal owner, String key) {
                int call = writes.incrementAndGet();
                if (call == 1 && interruption.equals("BEFORE_COMMIT")) throw new IllegalStateException("did not send");
                var result = appointmentService.createConfirmedAppointment(selected, owner, key);
                if (call == 1 && interruption.equals("AFTER_COMMIT")) throw new IllegalStateException("lost write response");
                return result;
            }

            @Override
            public AppointmentVerificationResult verify(String number, Principal owner) {
                if (verifications.incrementAndGet() == 1 && interruption.equals("VERIFY")) {
                    throw new IllegalStateException("verification unavailable");
                }
                return appointmentService.verify(number, owner);
            }

            @Override
            public java.util.Optional<AppointmentExecutionResult> findOriginal(
                    PlanId planId, int version, Principal owner, String key) {
                return appointmentService.findOriginal(planId, version, owner, key);
            }
        };
        AgentRuntime agent = new AgentRuntimeService(store, model(new AgentDecision.ProposePlan(futurePlan(slotId),
                "请核对预约")), readToolExecutor, fault, 5, Clock.systemUTC());
        var plan = agent.handleMessage(taskId, principal, "预约").task().activePlan().orElseThrow();
        String key = "s5-agent-" + UUID.randomUUID();
        var uncertain = agent.confirmPlan(taskId, principal, plan.id(), plan.version(), key);
        assertThat(uncertain.success()).isFalse();
        assertThat(uncertain.code()).isEqualTo(interruption.equals("VERIFY") ? "VERIFY_PENDING" : "OUTCOME_UNKNOWN");
        assertThat(agent.checkConfirmation(taskId, principal, plan.id(), plan.version(), key + "x").code())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        var checked = agent.checkConfirmation(taskId, principal, plan.id(), plan.version(), key);
        assertThat(writes.get()).isEqualTo(1);
        if (interruption.equals("BEFORE_COMMIT")) {
            assertThat(checked.code()).isEqualTo("NOT_FOUND");
            assertThat(agent.confirmPlan(taskId, principal, plan.id(), plan.version(), key).code()).isEqualTo("NOT_FOUND");
            assertThat(writes.get()).isEqualTo(1);
            checked = agent.confirmPlan(taskId, principal, plan.id(), plan.version(), key, true);
            assertThat(writes.get()).isEqualTo(2);
        }
        assertThat(checked.success()).isTrue();
        assertThat(checked.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(agent.confirmPlan(taskId, principal, plan.id(), plan.version(), key).task().facts()
                .get("appointmentId")).isEqualTo(checked.task().facts().get("appointmentId"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class, patientId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_audit_log WHERE patient_id = ? AND result_status = 'SUCCEEDED'
                """, Long.class, patientId)).isEqualTo(1);
    }

    @Test
    void legacyVerificationFailureRecoversByExactPlanWithoutAKey() throws Exception {
        AgentRuntime agent = runtime(model(new AgentDecision.ProposePlan(futurePlan(slotId), "请核对预约")));
        var plan = agent.handleMessage(taskId, principal, "预约").task().activePlan().orElseThrow();
        var legacy = store.load(taskId);
        legacy.transitionTo(TaskStatus.EXECUTING);
        legacy.setPlanStatus(PlanStatus.CONFIRMED);
        store.save(legacy);
        var booking = appointmentService.createConfirmedAppointment(plan, principal, "legacy-before-s5");
        assertThat(booking.success()).isTrue();
        legacy = store.load(taskId);
        legacy.transitionTo(TaskStatus.VERIFYING);
        legacy.putFact("appointmentId", booking.appointmentId());
        legacy.transitionTo(TaskStatus.FAILED);
        store.save(legacy);
        MockHttpSession session = login(fixtureUsername, DEMO_A_PASSWORD);
        mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/confirm/status", taskId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "planId", plan.id().value(), "planVersion", plan.version()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.task.facts.appointmentId").value(booking.appointmentId()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?",
                Long.class, patientId)).isEqualTo(1);
    }

    @Test
    void messageRequestIdReplaysSavedTurnWithoutAnotherToolCall() throws Exception {
        MockHttpSession session = login(fixtureUsername, DEMO_A_PASSWORD);
        String path = "/api/v1/tasks/{taskId}/messages";
        String body = "{\"message\":\"就诊要带什么证件？\",\"clientRequestId\":\"same-s5-message\"}";
        mockMvc.perform(postAs(session, path, taskId.value()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.turnCount").value(1));
        mockMvc.perform(postAs(session, path, taskId.value()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.turnCount").value(1))
                .andExpect(jsonPath("$.events[0].type").value("MESSAGE_REPLAYED"));
        mockMvc.perform(postAs(session, path, taskId.value()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("就诊要带什么证件？", "查另一个号源")))
                .andExpect(status().isBadRequest());
        assertThat(count("agent_tool_call")).isEqualTo(1);
    }

    @Test
    void openingVisitPreparationWaitsForCancellationWithoutLockingTheAppointmentFirst() throws Exception {
        var booked = appointmentWriteService.book(AppointmentWriteService.BookingCommand.forSlot(
                new AppointmentWriteService.Actor(patientId, "PATIENT"), "visit-cancel-race", slotId));
        assertThat(booked.success()).isTrue();
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        try {
            var tx = new TransactionTemplate(transactionManager);
            var consultation = tx.execute(status -> {
                jdbc.queryForList("SELECT id FROM sys_user WHERE id = ? FOR UPDATE", Long.class, patientId);
                var pending = executor.submit(() -> {
                    entered.countDown();
                    return taskApplication.openVisitConsultation(principal, booked.appointmentNo());
                });
                assertThatCode(() -> {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                }).doesNotThrowAnyException();
                assertThatThrownBy(() -> pending.get(750, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(jdbc.queryForList("SELECT id FROM appointment WHERE appointment_no = ? FOR UPDATE NOWAIT",
                        Long.class, booked.appointmentNo())).hasSize(1);
                assertThat(appointmentWriteService.cancel(booked.appointmentNo(), patientId).success()).isTrue();
                return pending;
            });
            assertThat(consultation.get(10, TimeUnit.SECONDS)).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void guideHistoryPaginatesSessionsAndOnlyReturnsTheAuthenticatedPatientsTasks() throws Exception {
        // setUp already created the oldest task for this patient.
        for (int i = 1; i < 20; i++) {
            taskApplication.createTask(principal, "导诊会话 " + i);
        }
        var newest = taskApplication.createTask(principal, "最新的导诊会话");

        var owner = login(fixtureUsername, DEMO_A_PASSWORD);
        var firstPage = mockMvc.perform(get("/api/v1/tasks").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].taskId").value(newest.value()))
                .andExpect(jsonPath("$.items[0].title").value("最新的导诊会话"))
                .andExpect(jsonPath("$.nextCursor").isNumber())
                .andReturn();
        String before = json.readTree(firstPage.getResponse().getContentAsString()).get("nextCursor").asText();
        mockMvc.perform(get("/api/v1/tasks").param("before", before).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].taskId").value(taskId.value()))
                .andExpect(jsonPath("$.items[0].title").value(fixtureUsername))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        var other = login("demo-patient-b", DEMO_B_PASSWORD);
        mockMvc.perform(get("/api/v1/tasks").session(other))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(newest.value(), taskId.value()));
    }

    @Test
    void visitConsultationReusesPortalAppointmentAndIsReadOnlyForItsOwner() throws Exception {
        var booked = appointmentWriteService.book(AppointmentWriteService.BookingCommand.forSlot(
                new AppointmentWriteService.Actor(patientId, "PATIENT"), "visit-portal", slotId));
        assertThat(booked.success()).isTrue();
        String number = booked.appointmentNo();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        TaskId consultation;
        try {
            Callable<TaskId> open = () -> {
                start.await();
                return taskApplication.openVisitConsultation(principal, number).orElseThrow();
            };
            var first = executor.submit(open);
            var second = executor.submit(open);
            consultation = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(second.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(consultation);
        } finally {
            executor.shutdownNow();
        }
        MockHttpSession owner = login(fixtureUsername, DEMO_A_PASSWORD);
        MockHttpSession other = login("demo-patient-b", DEMO_B_PASSWORD);
        String id = consultation.value();
        mockMvc.perform(postAs(owner, "/api/v1/appointments/{appointmentNo}/consultation", number))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(id));
        mockMvc.perform(postAs(other, "/api/v1/appointments/{appointmentNo}/consultation", number))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/tasks/{taskId}", id).session(other))
                .andExpect(status().isForbidden());
        mockMvc.perform(postAs(other, "/api/v1/tasks/{taskId}/messages/stream", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"这次去哪儿？\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/tasks/{taskId}", id).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType").value("VISIT_CONSULTATION"))
                .andExpect(jsonPath("$.facts.visitAppointment.appointmentNo").value(number))
                .andExpect(jsonPath("$.facts.visitAppointment.bookedVia").value("PORTAL"));
        var factReply = runtime.handleMessage(consultation, principal, "我约的是哪位医生？");
        assertThat(factReply.message()).contains(doctorName, departmentName, FIXTURE_DAY, "14:00-14:30", number);
        assertThat(runtime.handleMessage(consultation, principal, "帮我再约一个号").message())
                .contains("另一笔预约");
        assertThat(runtime.handleMessage(consultation, principal, "这次去要带什么？").task().facts())
                .containsKey("knowledgeReplies");
        for (var entry : Map.of("requirements", "{}", "selection", "{\"batchId\":\"batch\",\"slotId\":1}",
                "confirm", "{\"planId\":\"old\",\"planVersion\":1,\"idempotencyKey\":\"visit\"}").entrySet()) {
            mockMvc.perform(postAs(owner, "/api/v1/tasks/{taskId}/" + entry.getKey(), id)
                            .contentType(MediaType.APPLICATION_JSON).content(entry.getValue()))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?",
                Long.class, patientId)).isEqualTo(1);
        assertThat(runtime.getTask(consultation, principal).activePlan()).isEmpty();
    }

    @Test
    void portalBookingRefusesAnUnavailableSlotAndAuditsTheRefusal() {
        jdbc.update("UPDATE appointment_slot SET status = 'RESERVED' WHERE id = ?", slotId);

        AppointmentWriteService.BookingResult result = appointmentWriteService.book(
                AppointmentWriteService.BookingCommand.forSlot(
                        new AppointmentWriteService.Actor(patientId, "PATIENT"), "portal-idem-2", slotId));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("SLOT_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class,
                patientId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_audit_log
                WHERE patient_id = ? AND result_status = 'REJECTED' AND result_code = 'SLOT_UNAVAILABLE'
                  AND target_type = 'APPOINTMENT_SLOT' AND target_id = ?
                """, Long.class, patientId, slotId)).isEqualTo(1);
    }

    @ParameterizedTest(name = "portal booking rejects {0}")
    @ValueSource(strings = {"CLOSED_SCHEDULE", "DISABLED_DOCTOR", "DISABLED_DEPARTMENT", "PAST_SLOT"})
    void portalBookingRechecksTheClinicBeforeReservingASlot(String invalidation) {
        invalidateFixtureClinic(invalidation);

        AppointmentWriteService.BookingResult result = appointmentWriteService.book(
                AppointmentWriteService.BookingCommand.forSlot(
                        new AppointmentWriteService.Actor(patientId, "PATIENT"), "invalid-clinic", slotId));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("SLOT_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class,
                patientId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_audit_log
                WHERE patient_id = ? AND result_status = 'REJECTED' AND result_code = 'SLOT_NOT_FOUND'
                  AND target_type = 'APPOINTMENT_SLOT' AND target_id = ?
                """, Long.class, patientId, slotId)).isEqualTo(1);
    }

    @Test
    void agentConfirmationRejectsAClinicThatClosedAfterTheProposal() {
        PlanDraft draft = futurePlan(slotId);
        AgentRuntime bookingRuntime = new AgentRuntimeService(store,
                model(new AgentDecision.ProposePlan(draft, "请确认方案")),
                new InMemoryReadToolExecutor(), appointmentService, 5, CLOCK);
        AppointmentPlan proposed = bookingRuntime.handleMessage(taskId, principal, "预约测试科室")
                .task().activePlan().orElseThrow();
        invalidateFixtureClinic("CLOSED_SCHEDULE");

        ConfirmationResult result = bookingRuntime.confirmPlan(
                taskId, principal, proposed.id(), proposed.version(), "closed-after-proposal");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("号源已失效");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class,
                patientId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_audit_log
                WHERE patient_id = ? AND result_status = 'REJECTED' AND result_code = 'SLOT_INVALID'
                  AND target_type = 'APPOINTMENT_SLOT' AND target_id = ?
                """, Long.class, patientId, slotId)).isEqualTo(1);
    }

    @Test
    void portalBookingReplaysTheOriginalResultAfterItsDepartmentIsDisabled() {
        AppointmentWriteService.BookingCommand command = AppointmentWriteService.BookingCommand.forSlot(
                new AppointmentWriteService.Actor(patientId, "PATIENT"), "replay-disabled-clinic", slotId);
        AppointmentWriteService.BookingResult created = appointmentWriteService.book(command);
        assertThat(created.outcome()).isEqualTo(AppointmentWriteService.BookingResult.Outcome.CREATED);
        invalidateFixtureClinic("DISABLED_DEPARTMENT");

        AppointmentWriteService.BookingResult replayed = appointmentWriteService.book(command);

        assertThat(replayed.outcome()).isEqualTo(AppointmentWriteService.BookingResult.Outcome.EXISTING);
        assertThat(replayed.appointmentNo()).isEqualTo(created.appointmentNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class,
                patientId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE patient_id = ?", Long.class,
                patientId)).isEqualTo(1);
    }

    @Test
    void aFailedWriteRollsBackAndLeavesNoHalfWrittenAppointment() {
        // An over-long operator type breaks the audit insert, which is the last step of the same
        // transaction. The booking must roll back completely rather than survive unrecorded, and the
        // original error must reach the caller instead of being swallowed by the audit retry.
        AppointmentWriteService.BookingCommand command = AppointmentWriteService.BookingCommand.forSlot(
                new AppointmentWriteService.Actor(patientId, "P".repeat(40)), "portal-idem-broken", slotId);

        assertThatThrownBy(() -> appointmentWriteService.book(command))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class,
                patientId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("AVAILABLE");
    }

    @Test
    void portalBookingRefusesToReuseAnIdempotencyKeyForAnotherSlot() {
        long scheduleId = jdbc.queryForObject("SELECT schedule_id FROM appointment_slot WHERE id = ?",
                Long.class, slotId);
        jdbc.update("""
                INSERT INTO appointment_slot(schedule_id, slot_no, start_at, end_at, status)
                VALUES (?, 2, ?, ?, 'AVAILABLE')
                """, scheduleId, FIXTURE_DAY + " 06:30:00", FIXTURE_DAY + " 07:00:00");
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
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_audit_log
                WHERE patient_id = ? AND result_status = 'REJECTED'
                  AND result_code = 'IDEMPOTENCY_CONFLICT' AND target_id = ?
                """, Long.class, patientId, otherSlotId)).isEqualTo(1);
    }

    @Test
    void triageRecommendationIsPersistedWithTheRealDepartmentId() {
        AgentRuntimeService catalogRuntime = new AgentRuntimeService(store,
                model(new AgentDecision.AskUser("您哪里不舒服？", Set.of(),
                        new TriageRecommendation(0, departmentName, "根据描述先由该科室了解情况"))),
                new InMemoryReadToolExecutor(), new InMemoryAppointmentApplicationService(),
                5, CLOCK, departmentCatalog);

        catalogRuntime.handleMessage(taskId, principal, "我最近老是头痛");

        Map<?, ?> recommendation = (Map<?, ?>) store.load(taskId).snapshot().facts().get("triageRecommendation");
        assertThat(recommendation).isNotNull();
        assertThat(((Number) recommendation.get("departmentId")).longValue()).isEqualTo(departmentId);
        assertThat(recommendation.get("departmentName")).isEqualTo(departmentName);
    }

    @Test
    void triageRecommendationKeepsZeroWhenTheCatalogHasNoSuchDepartment() {
        AgentRuntimeService catalogRuntime = new AgentRuntimeService(store,
                model(new AgentDecision.AskUser("您哪里不舒服？", Set.of(),
                        new TriageRecommendation(0, "本院未开设的科室-" + patientId, "仅供演示"))),
                new InMemoryReadToolExecutor(), new InMemoryAppointmentApplicationService(),
                5, CLOCK, departmentCatalog);

        catalogRuntime.handleMessage(taskId, principal, "我最近老是头痛");

        Map<?, ?> recommendation = (Map<?, ?>) store.load(taskId).snapshot().facts().get("triageRecommendation");
        assertThat(recommendation).isNotNull();
        assertThat(((Number) recommendation.get("departmentId")).longValue()).isZero();
    }

    @Test
    void strictSlotSearchNeverSubstitutesAnotherDateOrPeriod() {
        SlotSearch exact = slotQueryService.searchStrict(
                new SlotCriteria(departmentId, null, null, null, FIXTURE_DATE, "AFTERNOON", 20));
        assertThat(exact.slots()).hasSize(1);
        assertThat(exact.exactMatch()).isTrue();

        // The fixture publishes no morning clinic, so a morning request must come back empty.
        SlotSearch strictMorning = slotQueryService.searchStrict(
                new SlotCriteria(departmentId, null, null, null, FIXTURE_DATE, "MORNING", 20));
        assertThat(strictMorning.slots()).isEmpty();
        assertThat(strictMorning.exactMatch()).isTrue();

        // Only the agent path may fall back, and it must say the result is not an exact match.
        SlotSearch relaxed = slotQueryService.searchWithFallback(
                new SlotCriteria(departmentId, null, null, null, FIXTURE_DATE, "MORNING", 20));
        assertThat(relaxed.slots()).isNotEmpty();
        assertThat(relaxed.exactMatch()).isFalse();
    }

    @Test
    void portalSearchPaginatesOnlySlotsWithinThePatientWindow() throws Exception {
        var secondDate = slotQueryService.today().plusDays(27);
        var excluded = secondDate.plusDays(1);
        for (var day : java.util.List.of(secondDate, excluded)) {
            jdbc.update("INSERT INTO doctor_schedule(doctor_id, schedule_date, clinic_period, status) VALUES (?, ?, 'AFTERNOON', 'OPEN')",
                    doctorId, day);
            long schedule = jdbc.queryForObject("SELECT id FROM doctor_schedule WHERE doctor_id = ? AND schedule_date = ?",
                    Long.class, doctorId, day);
            jdbc.update("INSERT INTO appointment_slot(schedule_id, slot_no, start_at, end_at, status) VALUES (?, 1, ?, ?, 'AVAILABLE')",
                    schedule, day + " 06:00:00", day + " 06:30:00");
        }
        String doctor = Long.toString(doctorId);
        mockMvc.perform(get("/api/v1/slots").param("doctorId", doctor).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.slots[0].slotId").value(slotId));
        mockMvc.perform(get("/api/v1/slots").param("doctorId", doctor).param("limit", "1").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.searchEndDate").value(secondDate.toString()))
                .andExpect(jsonPath("$.slots[0].appointmentDate").value(secondDate.toString()));
        mockMvc.perform(get("/api/v1/slots").param("doctorId", doctor).param("date", excluded.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get("/api/v1/slots/window"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.searchEndDate").value(secondDate.toString()));
        var boundary = new SlotCriteria(departmentId, null, doctorId, null, secondDate, null, 20);
        var after = new SlotCriteria(departmentId, null, doctorId, null, excluded, null, 20);
        assertThat(slotQueryService.searchStrict(boundary).slots()).hasSize(1);
        assertThat(slotQueryService.searchStrict(after).slots()).isEmpty();
        assertThat(slotQueryService.searchWithFallback(after).slots()).isEmpty();
        var session = login(fixtureUsername, DEMO_A_PASSWORD);
        mockMvc.perform(postAs(session, "/api/v1/tasks/" + taskId.value() + "/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("departmentId", departmentId, "appointmentDate", excluded.toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("当前可查询至 " + secondDate + "，请在范围内选择日期。"));
    }

    @Test
    void patientOverviewFindsTheNextVisitOutsideTheFirstTwentyRecentRecords() throws Exception {
        String sql = """
                INSERT INTO appointment(appointment_no, patient_id, department_id, doctor_id, slot_id,
                                        status, booked_via, idempotency_key, confirmed_version, booked_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PORTAL', ?, 0, ?)
                """;
        String numberPrefix = "OVERVIEW-" + patientId + "-";
        jdbc.update(sql, numberPrefix + "NEXT", patientId, departmentId, doctorId, slotId, "BOOKED",
                "overview-next", "2025-01-01 00:00:00");
        for (int index = 1; index <= 22; index++) {
            jdbc.update(sql, numberPrefix + index, patientId, departmentId, doctorId, slotId, "CANCELLED",
                    "overview-" + index, "2025-01-02 00:00:00");
        }
        var session = login(fixtureUsername, DEMO_A_PASSWORD);
        String overview = "/api/v1/appointments/mine/overview";
        mockMvc.perform(get(overview).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.next.appointmentNo").value(numberPrefix + "NEXT"))
                .andExpect(jsonPath("$.upcoming.totalCount").value(0))
                .andExpect(jsonPath("$.history.totalCount").value(22))
                .andExpect(jsonPath("$.history.items.length()").value(20));
        mockMvc.perform(get(overview).session(session).param("historyPage", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.history.page").value(2))
                .andExpect(jsonPath("$.history.items.length()").value(2))
                .andExpect(jsonPath("$.history.items[1].appointmentNo").value(numberPrefix + "1"));
        var other = login("demo-patient", DEMO_A_PASSWORD);
        var otherResponse = mockMvc.perform(get(overview).session(other)).andExpect(status().isOk()).andReturn();
        assertThat(otherResponse.getResponse().getContentAsString()).doesNotContain(numberPrefix);
    }

    @Test
    void patientCanListAndRestoreOnlyTheirOwnGuideConversations() throws Exception {
        var current = login(fixtureUsername, DEMO_A_PASSWORD);
        var other = login("demo-patient", DEMO_A_PASSWORD);
        String history = "/api/v1/tasks";
        String firstMessage = "第一轮历史导诊验收";
        runtime.handleMessage(taskId, principal, firstMessage);
        var newer = taskApplication.createTask(principal, "另一轮合成会话");
        mockMvc.perform(get(history)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(history).session(current)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].taskId").value(newer.value()))
                .andExpect(jsonPath("$.items[1].taskId").value(taskId.value()))
                .andExpect(jsonPath("$.items[1].title").value(firstMessage));
        mockMvc.perform(get(history + "/" + taskId.value()).session(current)).andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].userMessage").value(firstMessage));
        var otherHistory = mockMvc.perform(get(history).session(other)).andExpect(status().isOk()).andReturn();
        assertThat(otherHistory.getResponse().getContentAsString()).doesNotContain(taskId.value(), newer.value());
        mockMvc.perform(get(history + "/" + taskId.value()).session(other)).andExpect(status().isForbidden());
    }

    @Test
    void directoryOnlyPublishesActiveDepartmentsAndDoctors() {
        String disabledDepartment = "停用科室-" + patientId;
        jdbc.update("INSERT INTO department(code, name, status) VALUES (?, ?, 'DISABLED')",
                "DISABLED-" + patientId, disabledDepartment);
        long disabledDepartmentId = jdbc.queryForObject(
                "SELECT id FROM department WHERE name = ?", Long.class, disabledDepartment);
        jdbc.update("""
                INSERT INTO doctor(department_id, name, title, status)
                VALUES (?, ?, '主治医师', 'DISABLED')
                """, departmentId, "停用医生-" + patientId);

        assertThat(directory.listActiveDepartments())
                .anyMatch(view -> view.id() == departmentId)
                .noneMatch(view -> disabledDepartment.equals(view.name()));
        assertThat(directory.listActiveDepartments())
                .noneMatch(view -> view.id() == disabledDepartmentId);
        assertThat(directory.listActiveDoctors(disabledDepartmentId)).isEmpty();
        assertThat(directory.listActiveDoctors(departmentId))
                .anyMatch(view -> doctorName.equals(view.name()))
                .noneMatch(view -> view.name().startsWith("停用医生-"));
    }

    @Test
    void directoryQueriesReflectStatusChangesWithinTheSameTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            String code = jdbc.queryForObject("SELECT code FROM department WHERE id = ?", String.class, departmentId);
            assertThat(directory.findActiveDepartmentId(code)).isEqualTo(departmentId);
            assertThat(directory.findActiveDepartmentIdMentionedIn("请查" + departmentName)).isEqualTo(departmentId);

            String question = "请查" + doctorName;
            assertThat(directory.findActiveClinicianMentionedIn(question)).hasValueSatisfying(clinician -> {
                assertThat(clinician.id()).isEqualTo(doctorId);
                assertThat(clinician.departmentId()).isEqualTo(departmentId);
            });
            assertThat(directory.isActiveDoctorInDepartment(doctorId, departmentId)).isTrue();

            jdbc.update("UPDATE doctor SET status = 'DISABLED' WHERE id = ?", doctorId);
            assertThat(directory.findActiveClinicianMentionedIn(question)).isEmpty();
            assertThat(directory.isActiveDoctorInDepartment(doctorId, departmentId)).isFalse();
            transaction.setRollbackOnly();
        });
    }

    @Test
    void httpCatalogEndpointsBrowseTheDirectoryAndSearchSlotsStrictly() throws Exception {
        List<Map<String, Object>> departments = json.readValue(
                mockMvc.perform(get("/api/v1/departments")).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                new TypeReference<>() { });
        assertThat(departments).anyMatch(view -> departmentName.equals(view.get("name")));

        List<Map<String, Object>> doctors = json.readValue(
                mockMvc.perform(get("/api/v1/departments/" + departmentId + "/doctors"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                new TypeReference<>() { });
        assertThat(doctors).anyMatch(view -> doctorName.equals(view.get("name")));

        Map<String, Object> matched = json.readValue(
                mockMvc.perform(get("/api/v1/slots")
                                .param("departmentId", String.valueOf(departmentId))
                                .param("date", FIXTURE_DAY)
                                .param("period", "afternoon"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                new TypeReference<>() { });
        assertThat(matched.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matchedSlots = (List<Map<String, Object>>) matched.get("slots");
        assertThat(matchedSlots.get(0).get("timeRange")).isEqualTo("14:00-14:30");
        assertThat(((Number) matchedSlots.get(0).get("doctorId")).longValue()).isEqualTo(doctorId);

        // A period with no clinic is answered with nothing rather than a substitute.
        mockMvc.perform(get("/api/v1/slots")
                        .param("departmentId", String.valueOf(departmentId))
                        .param("date", FIXTURE_DAY)
                        .param("period", "MORNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        // Browsing without a destination is refused instead of dumping arbitrary slots.
        mockMvc.perform(get("/api/v1/slots"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void httpPortalBookingCreatesAndReplaysAnAppointment() throws Exception {
        MockHttpSession session = login(fixtureUsername, DEMO_A_PASSWORD);
        String key = "portal-http-" + UUID.randomUUID();
        String body = "{\"slotId\":" + slotId + ",\"idempotencyKey\":\"" + key + "\"}";
        var created = mockMvc.perform(postAs(session, "/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andReturn();
        String appointmentNo = json.readTree(created.getResponse().getContentAsString())
                .get("appointmentNo").asText();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM appointment
                WHERE patient_id = ? AND plan_id IS NULL AND booked_via = 'PORTAL'
                """, Long.class, patientId)).isEqualTo(1);

        mockMvc.perform(postAs(session, "/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("EXISTING"))
                .andExpect(jsonPath("$.appointmentNo").value(appointmentNo));

        mockMvc.perform(get("/api/v1/appointments/mine")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].appointmentNo").value(appointmentNo))
                .andExpect(jsonPath("$[0].bookedVia").value("PORTAL"))
                .andExpect(jsonPath("$[0].timeRange").value("14:00-14:30"));

        mockMvc.perform(get("/api/v1/appointments/" + appointmentNo)
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentName").value(departmentName))
                .andExpect(jsonPath("$.doctorName").value(doctorName));
    }

    @Test
    void anotherPatientCannotSeeOrBookSomeoneElsesAppointment() throws Exception {
        MockHttpSession session = login(fixtureUsername, DEMO_A_PASSWORD);
        String key = "portal-isolation-" + UUID.randomUUID();
        var created = mockMvc.perform(postAs(session, "/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":" + slotId + ",\"idempotencyKey\":\"" + key + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String appointmentNo = json.readTree(created.getResponse().getContentAsString())
                .get("appointmentNo").asText();
        MockHttpSession otherSession = login("demo-patient-b", DEMO_B_PASSWORD);

        mockMvc.perform(get("/api/v1/appointments/mine")
                        .session(otherSession).header("X-Demo-User-Id", String.valueOf(patientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/appointments/" + appointmentNo)
                        .session(otherSession).header("X-Demo-User-Id", String.valueOf(patientId)))
                .andExpect(status().isNotFound());
        // The other patient cannot take the already booked slot either.
        mockMvc.perform(postAs(otherSession, "/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":" + slotId + ",\"idempotencyKey\":\"other-"
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));
    }

    @Test
    void patientSessionRequiresCredentialsAndCsrfAndClearsCredentialsOnLogout() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId.value())
                        .header("X-Demo-User-Id", String.valueOf(patientId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/appointments/mine"))
                .andExpect(status().isUnauthorized());

        MockHttpSession anonymous = new MockHttpSession();
        postLogin(anonymous, "demo-patient", "wrong-password")
                .andExpect(status().isUnauthorized());
        postLogin(anonymous, "demo-admin", DEMO_A_PASSWORD)
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/session").session(anonymous))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(postAs(anonymous, "/api/v1/tasks"))
                .andExpect(status().isUnauthorized());

        MockHttpSession authenticated = login("demo-patient", DEMO_A_PASSWORD);
        mockMvc.perform(get("/api/v1/auth/session").session(authenticated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value("1001"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mockMvc.perform(post("/api/v1/appointments").session(authenticated)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("slotId", slotId, "idempotencyKey", "missing-csrf"))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE slot_id = ?", Long.class, slotId))
                .isZero();
        mockMvc.perform(post("/api/v1/auth/logout").session(authenticated))
                .andExpect(status().isForbidden());
        mockMvc.perform(postAs(authenticated, "/api/v1/auth/logout"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/auth/session").session(authenticated))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpPortalBookingReportsAConflictAndRejectsAnIncompleteRequest() throws Exception {
        MockHttpSession session = login(fixtureUsername, DEMO_A_PASSWORD);
        String unusable = "{\"slotId\":" + slotId + ",\"idempotencyKey\":\"conflict-" + patientId + "\"}";
        jdbc.update("UPDATE appointment_slot SET status = 'RESERVED' WHERE id = ?", slotId);

        mockMvc.perform(postAs(session, "/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(unusable))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));

        mockMvc.perform(postAs(session, "/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":" + slotId + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(postAs(session, "/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":" + slotId + ",\"idempotencyKey\":\"" + "x".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?",
                Long.class, patientId)).isZero();
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
        long rowId = mapper.findTask(taskId.value()).id();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_state_event WHERE task_id = ? AND metadata IS NULL",
                Long.class, rowId)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_tool_call WHERE task_id = ?", String.class, rowId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT assistant_message FROM agent_turn WHERE task_id = ? AND turn_no = 2",
                String.class, rowId)).isEqualTo("请确认方案");
    }

    @Test
    void modelAndToolTimeoutsNeverReenterTheAppointmentWritePath() throws Exception {
        var modelCalls = new AtomicInteger();
        var toolCalls = new AtomicInteger();
        var writes = new AtomicInteger();
        var selectedCall = new ToolCall("findAvailableSlots", Map.of("departmentId", departmentId));
        ReadToolExecutor delayedTool = new ReadToolExecutor() {
            @Override public List<ReadToolSpec> tools() { return readToolExecutor.tools(); }
            @Override public ToolResult execute(ToolCall call, TaskSnapshot input) {
                if (toolCalls.incrementAndGet() == 1) waitForTimeout();
                return readToolExecutor.execute(call, input);
            }
        };
        AppointmentApplicationService lostAfterCommit = new AppointmentApplicationService() {
            @Override public AppointmentExecutionResult createConfirmedAppointment(
                    AppointmentPlan plan, Principal patient, String key) {
                writes.incrementAndGet();
                assertThat(appointmentService.createConfirmedAppointment(plan, patient, key).success()).isTrue();
                throw new IllegalStateException("simulated lost write response");
            }

            @Override public AppointmentVerificationResult verify(String number, Principal patient) {
                return appointmentService.verify(number, patient);
            }

            @Override public java.util.Optional<AppointmentExecutionResult> findOriginal(
                    PlanId planId, int version, Principal patient, String key) {
                return appointmentService.findOriginal(planId, version, patient, key);
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            AgentRuntime testRuntime = new AgentRuntimeService(store, (input, tools) -> {
                return switch (modelCalls.incrementAndGet()) {
                    case 1 -> {
                        waitForTimeout();
                        throw new IllegalStateException("interrupted slow model");
                    }
                    case 2 -> new AgentDecision.CallReadTool(selectedCall);
                    default -> new AgentDecision.ProposePlan(futurePlan(slotId), "请核对候选");
                };
            }, delayedTool, lostAfterCommit, 5, CLOCK, departmentCatalog, null,
                    new AgentCallPolicy(workers, 400, 400, 4000));
            long start = System.nanoTime();
            final String userInput = "用于验证不会出现在追踪中的消息";
            AgentTurnResult turn = testRuntime.handleMessage(taskId, principal, userInput);
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(4);
            assertThat(modelCalls.get()).isEqualTo(3);
            assertThat(toolCalls.get()).isEqualTo(2);
            assertThat(writes.get()).isZero();
            assertThat(countAppointmentsForPatient()).isZero();

            AppointmentPlan plan = turn.task().activePlan().orElseThrow();
            String key = "s5-confirm-recovery-key";
            assertThat(testRuntime.confirmPlan(taskId, principal, plan.id(), plan.version(), key).code())
                    .isEqualTo("OUTCOME_UNKNOWN");
            assertThat(countAppointmentsForPatient()).isEqualTo(1);
            var recovered = testRuntime.checkConfirmation(taskId, principal, plan.id(), plan.version(), key);
            assertThat(recovered.success()).isTrue();
            var replay = testRuntime.confirmPlan(taskId, principal, plan.id(), plan.version(), key, true);
            assertThat(replay.task().facts().get("appointmentId")).isEqualTo(recovered.task().facts().get("appointmentId"));
            assertThat(writes.get()).isEqualTo(1);
            assertThat(countAppointmentsForPatient()).isEqualTo(1);

            MockHttpSession owner = login(fixtureUsername, DEMO_A_PASSWORD);
            String trace = mockMvc.perform(get("/api/v1/tasks/{taskId}/trace", taskId.value()).session(owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskId").value(taskId.value()))
                    .andReturn().getResponse().getContentAsString();
            var entries = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            json.readTree(trace).path("entries").forEach(entries::add);
            assertThat(entries.stream().filter(entry -> entry.path("type").asText().equals("MODEL_DECISION"))
                    .map(entry -> entry.path("action").path("attempt").asInt())).containsExactly(1, 2, 1);
            assertThat(entries.stream().map(entry -> entry.path("reason").asText(""))
                    .filter(reason -> reason.endsWith("_TIMEOUT"))).containsExactly("MODEL_TIMEOUT", "TOOL_TIMEOUT");
            assertThat(entries.stream().filter(entry -> entry.path("type").asText().equals("READ_TOOL_ATTEMPT"))
                    .map(entry -> entry.at("/action/summary/tool").asText()))
                    .containsExactly("findAvailableSlots", "findAvailableSlots");
            assertThat(trace).contains("durationMs", "APPOINTMENT_LOOKUP", "APPOINTMENT_VERIFY")
                    .doesNotContain(userInput, key);
        } finally {
            workers.shutdownNow();
        }
    }

    private static void waitForTimeout() {
        try {
            Thread.sleep(30_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Simulated dependency interrupted", interrupted);
        }
    }

    private long countAppointmentsForPatient() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE patient_id = ?", Long.class, patientId);
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
        // The idempotent replay above is not audited again: it created nothing. The two refusals are.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_audit_log
                WHERE patient_id = ? AND result_status = 'SUCCEEDED'
                """, Long.class, patientId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT GROUP_CONCAT(result_code ORDER BY id) FROM operation_audit_log
                WHERE patient_id = ? AND result_status = 'REJECTED'
                """, String.class, patientId)).isEqualTo("IDEMPOTENCY_CONFLICT,SLOT_UNAVAILABLE");
    }

    @Test
    void readToolQueriesOnlyAvailableSlotsAndReturnsStructuredData() {
        ToolResult result = readToolExecutor.execute(
                new ToolCall("findAvailableSlots", Map.of(
                        "departmentId", departmentId,
                        "appointmentDate", FIXTURE_DAY,
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
                        "appointmentDate", FIXTURE_DAY,
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
        // Keep the patient's wording aligned with the rolling fixture, including across year boundaries.
        String chineseFixtureDate = FIXTURE_DATE.getMonthValue() + "月" + FIXTURE_DATE.getDayOfMonth() + "号";
        ToolResult result = readToolExecutor.execute(new ToolCall("findAvailableSlots", Map.of(
                "departmentId", departmentId, "appointmentDate", chineseFixtureDate, "clinicPeriod", "下午")),
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
                .containsEntry("appointmentDate", FIXTURE_DAY).containsEntry("clinicPeriod", "AFTERNOON");
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
                .containsEntry("cancelSupported", true).containsEntry("rescheduleSupported", true)
                .containsEntry("syntheticData", true);
    }

    @Test
    void agentRuntimeCanContinueAfterMysqlReadToolResult() {
        AgentRuntime runtime = new AgentRuntimeService(store,
                model(
                        new AgentDecision.CallReadTool(new ToolCall("findAvailableSlots", Map.of(
                                "departmentId", departmentId,
                                "appointmentDate", FIXTURE_DAY))),
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
    void pageAndConversationShareRequirementsAndOnlyChangesInvalidateThePlan() throws Exception {
        MockHttpSession session = login(fixtureUsername, DEMO_A_PASSWORD);
        String id = taskId.value();
        var initial = json.readTree(mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/requirements", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("departmentId", departmentId, "doctorId", doctorId,
                                "appointmentDate", FIXTURE_DAY))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSAL_READY"))
                .andExpect(jsonPath("$.task.facts.candidateBatch.slots.length()").value(1))
                .andExpect(jsonPath("$.task.activePlan").isEmpty())
                .andReturn().getResponse().getContentAsString());
        String oldBatch = initial.at("/task/facts/candidateBatch/id").asText();
        long taskRowId = mapper.findTask(id).id();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_plan WHERE task_id = ?", Long.class, taskRowId))
                .isZero();

        mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/messages", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"必须上午\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEED_MORE_INFO"))
                .andExpect(jsonPath("$.task.facts.requirements.appointmentDate").value(FIXTURE_DAY))
                .andExpect(jsonPath("$.task.facts.requirements.doctorId").value(doctorId))
                .andExpect(jsonPath("$.task.facts.candidateBatch.slots.length()").value(0));
        var next = json.readTree(mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/messages", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"上午优先，其他时段也可以\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.facts.requirements.sources.clinicPeriod").value("CONVERSATION"))
                .andExpect(jsonPath("$.task.facts.candidateBatch.slots[0].differences[0]").value("时段不同"))
                .andExpect(jsonPath("$.task.facts.candidateBatch.slots[0].appointmentDate").value(FIXTURE_DAY))
                .andReturn().getResponse().getContentAsString());
        String batch = next.at("/task/facts/candidateBatch/id").asText();
        for (var invalid : List.of(Map.of("batchId", oldBatch, "slotId", slotId),
                Map.of("batchId", batch, "slotId", 999999))) {
            mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/selection", id)
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/selection", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("batchId", batch, "slotId", slotId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"));
        AppointmentPlan selected = runtime.getTask(taskId, principal).activePlan().orElseThrow();

        for (String message : List.of("就诊要准备什么材料？", "好的，就按这个方案。", "请查号")) {
            var response = mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/messages", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("message", message))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"))
                    .andExpect(jsonPath("$.task.activePlan.version").value(selected.version()));
            if (message.startsWith("好的")) {
                response.andExpect(jsonPath("$.message").value("已选好号源。请核对确认单上的医生和时间，再点击「确认预约」。"));
            }
        }
        mockMvc.perform(get("/api/v1/tasks/{taskId}", id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.knowledgeReplies['3'].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.facts.knowledgeReplies['3'].sources").isEmpty())
                .andExpect(jsonPath("$.activePlan.version").value(selected.version()));
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slotId))
                .isEqualTo("AVAILABLE");

        mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/requirements", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clinicPeriod\":\"AFTERNOON\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSAL_READY"))
                .andExpect(jsonPath("$.task.activePlan.status").value("INVALIDATED"));
        mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("planId", selected.id().value(),
                                "planVersion", selected.version(), "idempotencyKey", "stale-s2"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void clearingTheRequestedDoctorExplainsSameDepartmentAlternatives() {
        jdbc.update("INSERT INTO doctor(department_id, name, status) VALUES (?, '无号测试医生', 'ACTIVE')", departmentId);
        long unavailable = jdbc.queryForObject(
                "SELECT id FROM doctor WHERE department_id=? AND name='无号测试医生'", Long.class, departmentId);

        var strict = runtime.updateRequirements(taskId, principal, Map.of("departmentId", departmentId,
                "doctorId", unavailable, "appointmentDate", FIXTURE_DAY, "clinicPeriod", "AFTERNOON"));
        assertThat(strict.status()).isEqualTo(TaskStatus.NEED_MORE_INFO);

        var relaxed = runtime.updateRequirements(taskId, principal, Map.of("doctorName", ""));
        var batch = json.valueToTree(relaxed.task().facts().get("candidateBatch"));
        assertThat(batch.at("/slots/0/doctorId").asLong()).isEqualTo(doctorId);
        assertThat(batch.at("/slots/0/appointmentDate").asText()).isEqualTo(FIXTURE_DAY);
        assertThat(batch.at("/slots/0/clinicPeriod").asText()).isEqualTo("AFTERNOON");
        assertThat(batch.at("/slots/0/differences/0").asText()).isEqualTo("备选医生");
        assertThat(relaxed.message()).contains("无号测试医生");
    }

    @Test
    void httpApiCompletesDemoAppointmentAndReplaysSseEvents() throws Exception {
        MockHttpSession session = login("demo-patient", DEMO_A_PASSWORD);
        String created = mockMvc.perform(postAs(session, "/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNDERSTANDING"))
                .andReturn().getResponse().getContentAsString();
        String apiTaskId = json.readTree(created).get("taskId").asText();

        var search = json.readTree(mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/messages", apiTaskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"帮我预约心内科的号\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSAL_READY"))
                .andExpect(jsonPath("$.task.facts.candidateBatch.slots.length()").value(3))
                .andReturn().getResponse().getContentAsString());
        var batch = search.at("/task/facts/candidateBatch");
        for (int index = 0; index < 2; index++) {
            mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/selection", apiTaskId)
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                    "batchId", batch.get("id").asText(),
                                    "slotId", batch.get("slots").get(index).get("slotId").asLong()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"));
        }

        Principal demoPatient = new Principal(1001L, "PATIENT");
        AppointmentPlan apiPlan = runtime.getTask(new TaskId(apiTaskId), demoPatient)
                .activePlan().orElseThrow();
        assertThat(apiPlan.version()).isEqualTo(2);
        assertThat(apiPlan.draft().slotId()).isEqualTo(batch.get("slots").get(1).get("slotId").asLong());
        assertThat(mapper.findPlan(mapper.findTask(apiTaskId).id(), 1).status()).isEqualTo("INVALIDATED");
        String confirm = "{\"planId\":\"" + apiPlan.id().value()
                + "\",\"planVersion\":" + apiPlan.version()
                + ",\"idempotencyKey\":\"api-idem-1\"}";

        MockHttpSession otherSession = login("demo-patient-b", DEMO_B_PASSWORD);
        mockMvc.perform(postAs(otherSession, "/api/v1/tasks/{taskId}/requirements", apiTaskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(postAs(otherSession, "/api/v1/tasks/{taskId}/selection", apiTaskId)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "batchId", batch.get("id").asText(), "slotId", apiPlan.draft().slotId()))))
                .andExpect(status().isForbidden());
        for (String path : List.of("/api/v1/tasks/{taskId}", "/api/v1/tasks/{taskId}/events",
                "/api/v1/tasks/{taskId}/trace")) {
            mockMvc.perform(get(path, apiTaskId).session(otherSession).header("X-Demo-User-Id", "1001"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/v1/tasks/{taskId}/trace", apiTaskId))
                .andExpect(status().isUnauthorized());
        for (String path : List.of("/api/v1/tasks/{taskId}/messages", "/api/v1/tasks/{taskId}/messages/stream")) {
            mockMvc.perform(postAs(otherSession, path, apiTaskId).header("X-Demo-User-Id", "1001")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"切换号源\"}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(postAs(otherSession, "/api/v1/tasks/{taskId}/confirm", apiTaskId)
                        .header("X-Demo-User-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM appointment a JOIN agent_plan p ON p.id = a.plan_id WHERE p.plan_no = ?
                """, Long.class, apiPlan.id().value())).isZero();

        mockMvc.perform(get("/api/v1/tasks/{taskId}", apiTaskId).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/confirm", apiTaskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        TaskSnapshot original = runtime.getTask(new TaskId(apiTaskId), demoPatient);
        String bookedNumber = original.facts().get("appointmentId").toString();
        String consultId = json.readTree(mockMvc.perform(postAs(session,
                        "/api/v1/appointments/{appointmentNo}/consultation", bookedNumber))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("taskId").asText();
        assertThat(consultId).isNotEqualTo(apiTaskId);
        runtime.handleMessage(new TaskId(consultId), demoPatient, "这次是什么就诊时间？");
        assertThat(runtime.getTask(new TaskId(apiTaskId), demoPatient)).isEqualTo(original);

        mockMvc.perform(get("/api/v1/tasks/{taskId}/events", apiTaskId)
                        .session(session)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("READ_TOOL_SUCCEEDED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("STREAM_COMPLETED")));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MockHttpSession anonymous = new MockHttpSession();
        String beforeLogin = anonymous.getId();
        var response = postLogin(anonymous, username, password).andExpect(status().isNoContent()).andReturn();
        MockHttpSession authenticated = (MockHttpSession) response.getRequest().getSession(false);
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getId()).isNotEqualTo(beforeLogin);
        return authenticated;
    }

    private org.springframework.test.web.servlet.ResultActions postLogin(
            MockHttpSession session, String username, String password) throws Exception {
        return mockMvc.perform(postAs(session, "/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", username).param("password", password));
    }

    private MockHttpServletRequestBuilder postAs(MockHttpSession session, String path, Object... vars) throws Exception {
        var csrf = json.readTree(mockMvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return post(path, vars).session(session).header(csrf.get("headerName").asText(), csrf.get("token").asText());
    }

    @Test
    void httpApiStreamsPatientReplyAndReturnsFinalBusinessResult() throws Exception {
        MockHttpSession session = login("demo-patient", DEMO_A_PASSWORD);
        String created = mockMvc.perform(postAs(session, "/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String apiTaskId = json.readTree(created).get("taskId").asText();

        var streamRequest = mockMvc.perform(postAs(session, "/api/v1/tasks/{taskId}/messages/stream", apiTaskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"我最近头疼，想先看看该挂哪个科室\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(streamRequest))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getCharacterEncoding()).isEqualToIgnoringCase("UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MESSAGE_DELTA")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RESULT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("NEED_MORE_INFO")))
                .andExpect(result -> {
                    String saved = runtime.getTask(new TaskId(apiTaskId), new Principal(1001, "PATIENT"))
                            .turns().get(0).assistantMessage();
                    String stream = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    String delta = stream.lines().dropWhile(line -> !line.equals("event:MESSAGE_DELTA"))
                            .skip(1).findFirst().orElseThrow().substring("data:".length());
                    assertThat(json.readTree(delta).path("text").asText()).isEqualTo(saved);
                });
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
        // The real MySQL write service checks wall-clock expiry, unlike the fixed-clock state tests.
        return new PlanDraft(patientId, departmentId, doctorId, targetSlotId, FIXTURE_DATE,
                "14:00-14:30", Instant.now().plusSeconds(3600));
    }

    private void invalidateFixtureClinic(String invalidation) {
        switch (invalidation) {
            case "CLOSED_SCHEDULE" -> jdbc.update(
                    "UPDATE doctor_schedule SET status = 'CLOSED' WHERE doctor_id = ?", doctorId);
            case "DISABLED_DOCTOR" -> jdbc.update(
                    "UPDATE doctor SET status = 'DISABLED' WHERE id = ?", doctorId);
            case "DISABLED_DEPARTMENT" -> jdbc.update(
                    "UPDATE department SET status = 'DISABLED' WHERE id = ?", departmentId);
            case "PAST_SLOT" -> jdbc.update("""
                    UPDATE appointment_slot
                    SET start_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 HOUR),
                        end_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 MINUTE)
                    WHERE id = ?
                    """, slotId);
            default -> throw new IllegalArgumentException("unknown fixture invalidation: " + invalidation);
        }
    }
}
