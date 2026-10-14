package com.mediflow.agent.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.sql.DataSource;

import com.mediflow.agent.agent.AgentEvent;
import com.mediflow.agent.agent.Principal;
import com.mediflow.agent.agent.TaskId;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class MySqlAgentEventHistory {
    private final JdbcTemplate jdbc;

    public MySqlAgentEventHistory(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public List<AgentEvent> replay(TaskId taskId, Principal principal) {
        List<HistoryRow> rows = new ArrayList<>();
        rows.addAll(jdbc.query("""
                SELECT e.event_no, e.event_type, e.to_status AS summary, e.created_at
                FROM agent_state_event e
                JOIN agent_task t ON t.id = e.task_id
                WHERE t.task_no = ? AND t.patient_id = ?
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

    private record HistoryRow(long createdAt, long sequence, String type, String summary) { }
}
