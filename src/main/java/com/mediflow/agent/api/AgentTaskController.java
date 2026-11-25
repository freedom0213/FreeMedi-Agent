package com.mediflow.agent.api;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import com.mediflow.agent.agent.AgentRuntime;
import com.mediflow.agent.agent.AgentTurnResult;
import com.mediflow.agent.agent.ConfirmationResult;
import com.mediflow.agent.agent.PlanId;
import com.mediflow.agent.agent.Principal;
import com.mediflow.agent.agent.TaskId;
import com.mediflow.agent.agent.TaskSnapshot;
import com.mediflow.agent.agent.AgentEvent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Qualifier;

@RestController
@RequestMapping("/api/v1/tasks")
@Profile("mysql")
public class AgentTaskController {
    private static final MediaType EVENT_STREAM_UTF8 = new MediaType(MediaType.TEXT_EVENT_STREAM,
            java.nio.charset.StandardCharsets.UTF_8);
    private final AgentRuntime runtime;
    private final MySqlTaskApplicationService tasks;
    private final MySqlAgentEventHistory history;
    private final Executor agentStreamExecutor;

    public AgentTaskController(AgentRuntime runtime,
                               MySqlTaskApplicationService tasks, MySqlAgentEventHistory history,
                               @Qualifier("applicationTaskExecutor") Executor agentStreamExecutor) {
        this.runtime = runtime;
        this.tasks = tasks;
        this.history = history;
        this.agentStreamExecutor = agentStreamExecutor;
    }

    @PostMapping
    public ResponseEntity<TaskCreatedResponse> createTask(
            @AuthenticationPrincipal(expression = "patient") Principal principal,
            @Valid @RequestBody(required = false) CreateTaskRequest request) {
        TaskId taskId = tasks.createTask(principal, request == null ? null : request.title());
        return ResponseEntity.status(201).body(new TaskCreatedResponse(taskId.value(), "UNDERSTANDING"));
    }

    @PostMapping("/{taskId}/messages")
    public AgentTurnResult message(@PathVariable String taskId,
                                   @AuthenticationPrincipal(expression = "patient") Principal principal,
                                   @Valid @RequestBody MessageRequest request) {
        return runtime.handleMessage(new TaskId(taskId), principal, request.message(), request.clientRequestId(), event -> {});
    }

    @PostMapping(value = "/{taskId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamMessage(@PathVariable String taskId,
                                    @AuthenticationPrincipal(expression = "patient") Principal principal,
                                    @Valid @RequestBody MessageRequest request) throws IOException {
        TaskId id = new TaskId(taskId);
        runtime.getTask(id, principal);
        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.send(SseEmitter.event().name("STREAM_STARTED").data(Map.of("message", request.message())));
        try {
            agentStreamExecutor.execute(streamingTask(emitter, id, principal, request));
        } catch (RejectedExecutionException rejected) {
            // Pool saturation must still reach the client as a stream event, not an HTTP 500.
            send(emitter, "STREAM_ERROR", Map.of("message", "当前处理请求较多，请稍后重试"));
            emitter.complete();
        }
        return ResponseEntity.ok().contentType(EVENT_STREAM_UTF8).body(emitter);
    }

    private Runnable streamingTask(SseEmitter emitter, TaskId id, Principal principal, MessageRequest request) {
        return () -> {
            try {
                AgentTurnResult result = runtime.handleMessage(id, principal, request.message(), request.clientRequestId(),
                        event -> send(emitter, "AGENT_EVENT", event));
                send(emitter, "MESSAGE_STARTED", Map.of());
                send(emitter, "MESSAGE_DELTA", Map.of("text", result.message()));
                completeReply(emitter, result);
            } catch (Throwable error) {
                send(emitter, "STREAM_ERROR", Map.of("message", "本次回复未完成，请重试"));
                emitter.completeWithError(error);
            }
        };
    }

    private static void completeReply(SseEmitter emitter, AgentTurnResult result) {
        send(emitter, "MESSAGE_COMPLETED", Map.of("message", result.message()));
        send(emitter, "RESULT", result);
        emitter.complete();
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException ignored) {
            emitter.complete();
        }
    }

    @PostMapping("/{taskId}/confirm")
    public ConfirmationResult confirm(@PathVariable String taskId,
                                      @AuthenticationPrincipal(expression = "patient") Principal principal,
                                      @Valid @RequestBody ConfirmRequest request) {
        return runtime.confirmPlan(new TaskId(taskId), principal,
                new PlanId(request.planId()), request.planVersion(), request.idempotencyKey(), request.retry());
    }

    @PostMapping("/{taskId}/confirm/status")
    public ConfirmationResult checkConfirmation(@PathVariable String taskId,
            @AuthenticationPrincipal(expression = "patient") Principal principal,
            @Valid @RequestBody ConfirmationStatusRequest request) {
        return runtime.checkConfirmation(new TaskId(taskId), principal,
                new PlanId(request.planId()), request.planVersion(), request.idempotencyKey());
    }

    @PostMapping("/{taskId}/requirements")
    public AgentTurnResult updateRequirements(@PathVariable String taskId,
            @AuthenticationPrincipal(expression = "patient") Principal principal,
            @RequestBody Map<String, Object> changes) {
        if (!java.util.Set.of("departmentId", "doctorId", "appointmentDate", "clinicPeriod",
                "dateFlexible", "periodFlexible").containsAll(changes.keySet())) {
            throw new IllegalArgumentException("请求包含无法识别的预约条件。");
        }
        return runtime.updateRequirements(new TaskId(taskId), principal, changes);
    }

    @PostMapping("/{taskId}/selection")
    public AgentTurnResult selectCandidate(@PathVariable String taskId,
            @AuthenticationPrincipal(expression = "patient") Principal principal,
            @Valid @RequestBody SelectionRequest request) {
        return runtime.selectCandidate(new TaskId(taskId), principal, request.batchId(), request.slotId());
    }

    @GetMapping
    public MySqlTaskApplicationService.GuideHistory listTasks(
            @AuthenticationPrincipal(expression = "patient") Principal principal,
            @RequestParam(required = false) Long before) {
        return tasks.guideHistory(principal, before);
    }

    @GetMapping("/{taskId}")
    public TaskSnapshot getTask(@PathVariable String taskId,
                                @AuthenticationPrincipal(expression = "patient") Principal principal) {
        return runtime.getTask(new TaskId(taskId), principal);
    }

    @GetMapping("/{taskId}/trace")
    public MySqlAgentEventHistory.TaskTrace trace(@PathVariable String taskId,
            @AuthenticationPrincipal(expression = "patient") Principal principal) {
        TaskId id = new TaskId(taskId);
        runtime.getTask(id, principal);
        return history.trace(id, principal);
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String taskId,
                             @AuthenticationPrincipal(expression = "patient") Principal principal)
            throws IOException {
        TaskId id = new TaskId(taskId);
        runtime.getTask(id, principal);
        SseEmitter emitter = new SseEmitter(10_000L);
        long eventId = 0;
        for (var event : history.replay(id, principal)) {
            emitter.send(SseEmitter.event().id(String.valueOf(++eventId))
                    .name(event.type()).data(event.summary()));
        }
        emitter.send(SseEmitter.event().id(String.valueOf(++eventId))
                .name("STREAM_COMPLETED").data("事件回放完成"));
        emitter.complete();
        return ResponseEntity.ok().contentType(EVENT_STREAM_UTF8).body(emitter);
    }

    public record CreateTaskRequest(String title) { }

    public record MessageRequest(@NotBlank String message, @Size(max = 64) String clientRequestId) { }

    public record ConfirmRequest(@NotBlank String planId, @NotNull Integer planVersion,
                                 @NotBlank @Size(max = 64) String idempotencyKey, boolean retry) { }

    public record ConfirmationStatusRequest(@NotBlank String planId, @NotNull Integer planVersion,
                                            @Size(max = 64) String idempotencyKey) { }

    public record TaskCreatedResponse(String taskId, String status) { }

    public record SelectionRequest(@NotBlank @Size(max = 36) String batchId, @NotNull @Positive Long slotId) { }
}
