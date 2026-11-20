package com.mediflow.agent.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.agent.AgentEvent;
import com.mediflow.agent.agent.Principal;
import com.mediflow.agent.agent.TaskId;
import com.mediflow.agent.agent.TaskAction;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class MySqlAgentEventHistory {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MySqlAgentEventHistory(DataSource dataSource, ObjectMapper json) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.json = json;
    }

    public List<AgentEvent> replay(TaskId taskId, Principal principal) {
        List<HistoryRow> rows = new ArrayList<>();
        rows.addAll(jdbc.query("""
                SELECT e.event_no, e.event_type, e.to_status AS summary, e.created_at
                FROM agent_state_event e
                JOIN agent_task t ON t.id = e.task_id
                WHERE t.task_no = ? AND t.patient_id = ? AND e.metadata IS NULL
                """, (result, rowNum) -> new HistoryRow(
                result.getTimestamp("created_at").toInstant().toEpochMilli(),
                result.getLong("event_no"), result.getString("event_type"),
                result.getString("summary")), taskId.value(), principal.userId()));
        rows.addAll(jdbc.query("""
                SELECT tc.call_no, tc.tool_name, tc.status, tc.error_code, tc.created_at
                FROM agent_tool_call tc
                JOIN agent_task t ON t.id = tc.task_id
                WHERE t.task_no = ? AND t.patient_id = ?
                """, (result, rowNum) -> {
            String type = switch (result.getString("status")) {
                case "SUCCEEDED" -> "READ_TOOL_SUCCEEDED";
                case "FAILED" -> "READ_TOOL_FAILED";
                default -> "READ_TOOL_STARTED";
            };
            String errorCode = result.getString("error_code");
            String summary = errorCode == null
                    ? result.getString("tool_name") : result.getString("tool_name") + ": " + errorCode;
            return new HistoryRow(result.getTimestamp("created_at").toInstant().toEpochMilli(),
                    result.getLong("call_no"), type, summary);
        }, taskId.value(), principal.userId()));
        return rows.stream().sorted(Comparator.comparingLong(HistoryRow::createdAt)
                .thenComparingLong(HistoryRow::sequence))
                .map(row -> new AgentEvent(row.type(), row.summary()))
                .toList();
    }

    public TaskTrace trace(TaskId taskId, Principal principal) {
        List<TraceEntry> entries = jdbc.query("""
                SELECT e.event_no, e.event_type, e.from_status, e.to_status, e.reason_code, e.metadata, e.created_at
                FROM agent_state_event e
                JOIN agent_task t ON t.id = e.task_id
                WHERE t.task_no = ? AND t.patient_id = ?
                ORDER BY e.event_no
                """, (result, rowNum) -> new TraceEntry(result.getLong("event_no"),
                result.getTimestamp("created_at").toInstant(), result.getString("event_type"),
                result.getString("from_status"), result.getString("to_status"), result.getString("reason_code"),
                readAction(result.getString("metadata"))), taskId.value(), principal.userId());
        return new TaskTrace(taskId.value(), entries);
    }

    private TaskAction readAction(String value) {
        if (value == null) return null;
        try {
            return json.readValue(value, TaskAction.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid persisted task trace action", invalid);
        }
    }

    public record TaskTrace(String taskId, List<TraceEntry> entries) { }

    public record TraceEntry(long sequence, Instant recordedAt, String type, String fromStatus,
                             String toStatus, String reason, TaskAction action) { }

    private record HistoryRow(long createdAt, long sequence, String type, String summary) { }
}
