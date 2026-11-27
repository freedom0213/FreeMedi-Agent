package com.mediflow.agent.service.guide;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.agent.runtime.AgentEvent;
import com.mediflow.agent.agent.runtime.Principal;
import com.mediflow.agent.agent.task.TaskId;
import com.mediflow.agent.agent.task.TaskAction;
import com.mediflow.agent.mapper.guide.GuideEventMapper;
import com.mediflow.agent.mapper.guide.GuideEventMapper.TaskSummaryRow;
import com.mediflow.agent.mapper.guide.GuideEventMapper.TraceRow;

import org.springframework.context.annotation.Profile;

import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public class MySqlAgentEventHistory {
    private final GuideEventMapper events;
    private final ObjectMapper json;

    public MySqlAgentEventHistory(GuideEventMapper events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    public List<AgentEvent> replay(TaskId taskId, Principal principal) {
        List<HistoryRow> rows = new ArrayList<>();
        for (var event : events.stateEvents(taskId.value(), principal.userId())) {
            rows.add(new HistoryRow(event.createdAt().toEpochMilli(), event.sequence(), event.type(), event.summary()));
        }
        for (var call : events.toolCalls(taskId.value(), principal.userId())) {
            String type = switch (call.status()) {
                case "SUCCEEDED" -> "READ_TOOL_SUCCEEDED";
                case "FAILED" -> "READ_TOOL_FAILED";
                default -> "READ_TOOL_STARTED";
            };
            String errorCode = call.errorCode();
            String summary = errorCode == null
                    ? call.toolName() : call.toolName() + ": " + errorCode;
            rows.add(new HistoryRow(call.createdAt().toEpochMilli(), call.sequence(), type, summary));
        }
        return rows.stream().sorted(Comparator.comparingLong(HistoryRow::createdAt)
                .thenComparingLong(HistoryRow::sequence))
                .map(row -> new AgentEvent(row.type(), row.summary()))
                .toList();
    }

    public TaskTrace trace(TaskId taskId, Principal principal) {
        var entries = events.patientTrace(taskId.value(), principal.userId()).stream().map(this::traceEntry).toList();
        return new TaskTrace(taskId.value(), entries);
    }

    public List<TaskSummary> recentTaskSummaries(int limit) {
        return events.recentTaskSummaries(limit).stream().map(MySqlAgentEventHistory::taskSummary).toList();
    }

    public Optional<TaskSummary> taskSummary(String taskId) {
        return events.taskSummary(taskId).map(MySqlAgentEventHistory::taskSummary);
    }

    public List<TraceEntry> adminTraceActions(String taskId, long after, int limit) {
        return events.adminTraceActions(taskId, after, limit).stream().map(this::traceEntry).toList();
    }

    private TraceEntry traceEntry(TraceRow row) {
        return new TraceEntry(row.sequence(), row.recordedAt(), row.type(), row.fromStatus(),
                row.toStatus(), row.reason(), readAction(row.metadata()));
    }

    private static TaskSummary taskSummary(TaskSummaryRow row) {
        return new TaskSummary(row.taskId(), row.taskType(), row.status(), row.turnCount(), row.updatedAt());
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

    public record TaskSummary(String taskId, String taskType, String status, int turnCount, Instant updatedAt) { }

    public record TraceEntry(long sequence, Instant recordedAt, String type, String fromStatus,
                             String toStatus, String reason, TaskAction action) { }

    private record HistoryRow(long createdAt, long sequence, String type, String summary) { }
}
