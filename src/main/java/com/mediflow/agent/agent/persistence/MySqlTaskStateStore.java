package com.mediflow.agent.agent.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mediflow.agent.agent.AppointmentPlan;
import com.mediflow.agent.agent.PlanDraft;
import com.mediflow.agent.agent.PlanId;
import com.mediflow.agent.agent.PlanStatus;
import com.mediflow.agent.agent.StateTransition;
import com.mediflow.agent.agent.TaskContext;
import com.mediflow.agent.agent.TaskId;
import com.mediflow.agent.agent.TaskSnapshot;
import com.mediflow.agent.agent.TaskStateStore;
import com.mediflow.agent.agent.TaskStatus;
import com.mediflow.agent.agent.TaskTurn;
import com.mediflow.agent.agent.ToolExecution;
import com.mediflow.agent.agent.ToolResult;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("mysql")
public class MySqlTaskStateStore implements TaskStateStore {
    private static final TypeReference<Map<String, Object>> FACTS_TYPE = new TypeReference<>() { };
    private final TaskPersistenceMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public MySqlTaskStateStore(TaskPersistenceMapper mapper, ObjectMapper json,
                              PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.transactions = new TransactionTemplate(transactionManager);
        // Each checkpoint commits before acknowledging it in the mutable in-process context.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public TaskId create(long sessionId, long patientId) {
        if (sessionId <= 0 || patientId <= 0) {
            throw new IllegalArgumentException("session and patient ids must be positive");
        }
        TaskId id = new TaskId(UUID.randomUUID().toString());
        transactions.executeWithoutResult(transaction -> {
            if (mapper.createTask(id.value(), sessionId, patientId) != 1) {
                throw new SecurityException("session is not active or does not belong to current user");
            }
            TaskPersistenceMapper.TaskRow row = mapper.findTask(id.value());
            mapper.insertEvent(row.id(), 1, null, TaskStatus.UNDERSTANDING.name(), "TASK_CREATED");
        });
        return id;
    }

    @Override
    public TaskContext load(TaskId taskId) {
        return transactions.execute(transaction -> {
            TaskPersistenceMapper.TaskRow row = requireTask(taskId);
            TaskPersistenceMapper.PlanRow planRow = mapper.latestPlan(row.id());
            AppointmentPlan plan = null;
            if (planRow != null) {
                PlanDraft draft = read(planRow.planSnapshot(), PlanDraft.class);
                if (!digest(write(draft)).equals(planRow.planDigest())) {
                    throw new DataIntegrityViolationException("stored appointment plan digest does not match");
                }
                plan = new AppointmentPlan(new PlanId(planRow.planNo()), planRow.version(),
                        draft, PlanStatus.valueOf(planRow.status()));
            }
            List<TaskTurn> turns = mapper.turns(row.id()).stream()
                    .map(turn -> new TaskTurn(turn.turnNo(), turn.clientRequestId(), turn.userMessage(),
                            turn.assistantMessage(), turn.status())).toList();
            TaskSnapshot snapshot = new TaskSnapshot(taskId, row.patientId(), TaskStatus.valueOf(row.status()),
                    row.stepCount(), row.currentTurnNo(), readFacts(row.collectedFacts()), Optional.ofNullable(plan),
                    Optional.ofNullable(read(row.lastToolResult(), ToolResult.class)), turns);
            return TaskContext.restore(snapshot, row.stateVersion(),
                    mapper.toolCallCount(row.id(), row.currentTurnNo()));
        });
    }

    @Override
    public void save(TaskContext context) {
        // Also prevent one mutable context from changing while its checkpoint is being committed.
        synchronized (context) {
            TaskSnapshot snapshot = context.snapshot();
            long expectedVersion = context.stateVersion();
            transactions.executeWithoutResult(transaction -> {
                TaskPersistenceMapper.TaskRow row = requireTask(snapshot.taskId());
                int updated = mapper.updateTask(row.id(), snapshot.patientId(), snapshot.status().name(),
                        snapshot.turnCount(), snapshot.stepCount(), write(snapshot.facts()),
                        snapshot.lastToolResult().map(this::write).orElse(null), expectedVersion);
                if (updated != 1) {
                    throw new OptimisticLockingFailureException("task has changed; reload before retrying");
                }
                snapshot.activePlan().ifPresent(plan -> savePlan(row.id(), plan));
                if (!snapshot.turns().isEmpty()) {
                    TaskTurn turn = snapshot.turns().get(snapshot.turns().size() - 1);
                    mapper.saveTurn(row.id(), turn.turnNo(), turn.clientRequestId(), turn.userMessage(),
                            turn.assistantMessage(), turn.status());
                }
                for (ToolExecution tool : context.pendingTools()) {
                    mapper.saveTool(row.id(), mapper.turnId(row.id(), tool.turnNo()), tool.callNo(),
                            tool.call().name(), write(tool.call().arguments()),
                            tool.result() == null ? null : write(tool.result()), tool.status(),
                            tool.result() == null ? null : tool.result().errorCode(), tool.durationMs());
                }
                long eventNo = mapper.lastEventNo(row.id());
                for (StateTransition transition : context.pendingTransitions()) {
                    mapper.insertEvent(row.id(), ++eventNo, transition.from().name(),
                            transition.to().name(), "STATE_CHANGED");
                }
            });
            context.acknowledgeSave(expectedVersion + 1);
        }
    }

    private void savePlan(long taskRowId, AppointmentPlan plan) {
        String snapshot = write(plan.draft());
        String digest = digest(snapshot);
        TaskPersistenceMapper.PlanRow previous = mapper.findPlan(taskRowId, plan.version());
        if (previous != null) {
            if (!previous.planNo().equals(plan.id().value()) || !previous.planDigest().equals(digest)) {
                throw new DataIntegrityViolationException("a saved plan version is immutable");
            }
            mapper.updatePlanStatus(taskRowId, plan.version(), plan.status().name());
            return;
        }
        PlanDraft draft = plan.draft();
        mapper.insertPlan(plan.id().value(), taskRowId, draft.patientId(), plan.version(), draft.departmentId(),
                draft.doctorId(), draft.slotId(), snapshot, digest, plan.status().name(),
                LocalDateTime.ofInstant(draft.expiresAt(), ZoneOffset.UTC));
    }

    private TaskPersistenceMapper.TaskRow requireTask(TaskId id) {
        if (id == null) {
            throw new IllegalArgumentException("task id is required");
        }
        TaskPersistenceMapper.TaskRow row = mapper.findTask(id.value());
        if (row == null) {
            throw new IllegalArgumentException("task not found: " + id.value());
        }
        return row;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new DataIntegrityViolationException("task checkpoint cannot be serialized", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new DataIntegrityViolationException("stored task checkpoint is invalid", exception);
        }
    }

    private Map<String, Object> readFacts(String value) {
        try {
            return json.readValue(value, FACTS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new DataIntegrityViolationException("stored task facts are invalid", exception);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
