package com.mediflow.agent.api;

import com.mediflow.agent.agent.ReadToolExecutor;
import com.mediflow.agent.agent.ReadToolSpec;
import com.mediflow.agent.api.MySqlAgentEventHistory.TaskSummary;
import com.mediflow.agent.api.MySqlAgentEventHistory.TraceEntry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/agent/tasks")
@Profile("mysql")
public class AdminAgentTraceController {
    private static final int RECENT_LIMIT = 24;
    private static final int TRACE_LIMIT = 50;
    private static final Set<String> TIMED_ACTIONS = Set.of(
            "MODEL_DECISION", "READ_TOOL_ATTEMPT", "APPOINTMENT_WRITE", "APPOINTMENT_VERIFY", "APPOINTMENT_LOOKUP");

    private final MySqlAgentEventHistory history;
    private final Set<String> tools;

    public AdminAgentTraceController(MySqlAgentEventHistory history,
                                     @Qualifier("readToolExecutor") ReadToolExecutor executor) {
        this.history = history;
        this.tools = executor.tools().stream().map(ReadToolSpec::name).collect(Collectors.toUnmodifiableSet());
    }

    @GetMapping
    public List<TaskSummary> tasks() {
        return history.recentTaskSummaries(RECENT_LIMIT);
    }

    @GetMapping("/{taskId}/trace")
    @Transactional(readOnly = true)
    public TracePage trace(@PathVariable String taskId, @RequestParam(defaultValue = "0") long after) {
        if (taskId.length() > 64 || after < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        TaskSummary task = history.taskSummary(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var rows = history.adminTraceActions(task.taskId(), after, TRACE_LIMIT + 1);
        var entries = rows.stream().limit(TRACE_LIMIT).map(this::entry).toList();
        Long next = rows.size() > TRACE_LIMIT ? entries.get(entries.size() - 1).sequence() : null;
        return new TracePage(task, entries, next);
    }

    private AdminTraceEntry entry(TraceEntry row) {
        var action = row.action();
        var proposedTool = action.summary().get("tool");
        boolean readAttempt = "READ_TOOL_ATTEMPT".equals(row.type());
        String tool = readAttempt && proposedTool instanceof String name && tools.contains(name) ? name : null;
        var count = action.summary().get("resultCount");
        var version = action.summary().get("modelVersion");
        return new AdminTraceEntry(row.sequence(), row.recordedAt(), row.type(), action.turnNo(),
                action.stepNo(), action.attempt(), action.outcome(), safeCode(row.reason()),
                TIMED_ACTIONS.contains(row.type()) ? action.durationMs() : null, tool,
                readAttempt && count instanceof Number number ? number.intValue() : null,
                "MODEL_DECISION".equals(row.type()) && version instanceof String value
                        && value.matches("[a-z0-9/-]{1,64}") ? value : null);
    }

    private static String safeCode(String reason) {
        return reason != null && reason.matches("[A-Z][A-Z0-9_]{0,63}") ? reason : null;
    }

    public record TracePage(TaskSummary task, List<AdminTraceEntry> entries, Long nextAfter) {}

    public record AdminTraceEntry(long sequence, Instant recordedAt, String type, int turnNo, int stepNo,
                                  int attempt, String outcome, String reason, Long durationMs,
                                  String tool, Integer resultCount, String modelVersion) {}
}
