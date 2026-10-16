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
import com.mediflow.agent.agent.StreamingReplyService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Qualifier;

@RestController
@RequestMapping("/api/v1/tasks")
@Profile("mysql")
public class AgentTaskController {
    private final AgentRuntime runtime;
    private final DemoIdentityService identity;
    private final MySqlTaskApplicationService tasks;
    private final MySqlAgentEventHistory history;
    private final StreamingReplyService streamingReplies;
    private final Executor agentStreamExecutor;

    public AgentTaskController(AgentRuntime runtime, DemoIdentityService identity,
                               MySqlTaskApplicationService tasks, MySqlAgentEventHistory history,
                               StreamingReplyService streamingReplies,
                               @Qualifier("applicationTaskExecutor") Executor agentStreamExecutor) {
        this.runtime = runtime;
        this.identity = identity;
        this.tasks = tasks;
        this.history = history;
        this.streamingReplies = streamingReplies;
        this.agentStreamExecutor = agentStreamExecutor;
    }

    @PostMapping
    public ResponseEntity<TaskCreatedResponse> createTask(
            @RequestHeader(value = "X-Demo-User-Id", required = false) String userId,
            @Valid @RequestBody(required = false) CreateTaskRequest request) {
        Principal principal = identity.resolve(userId);
        TaskId taskId = tasks.createTask(principal, request == null ? null : request.title());
        return ResponseEntity.status(201).body(new TaskCreatedResponse(taskId.value(), "UNDERSTANDING"));
    }

    @PostMapping("/{taskId}/messages")
    public AgentTurnResult message(@PathVariable String taskId,
                                   @RequestHeader(value = "X-Demo-User-Id", required = false) String userId,
                                   @Valid @RequestBody MessageRequest request) {
        return runtime.handleMessage(new TaskId(taskId), identity.resolve(userId), request.message());
    }

    @PostMapping(value = "/{taskId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@PathVariable String taskId,
                                    @RequestHeader(value = "X-Demo-User-Id", required = false) String userId,
                                    @Valid @RequestBody MessageRequest request) throws IOException {
        TaskId id = new TaskId(taskId);
        Principal principal = identity.resolve(userId);
        runtime.getTask(id, principal);
        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.send(SseEmitter.event().name("STREAM_STARTED").data(Map.of("message", request.message())));
        try {
            agentStreamExecutor.execute(streamingTask(emitter, id, principal, request.message()));
        } catch (RejectedExecutionException rejected) {
            // Pool saturation must still reach the client as a stream event, not an HTTP 500.
            send(emitter, "STREAM_ERROR", Map.of("message", "当前处理请求较多，请稍后重试"));
            emitter.complete();
        }
        return emitter;
    }

    private Runnable streamingTask(SseEmitter emitter, TaskId id, Principal principal, String message) {
        return () -> {
            try {
                AgentTurnResult result = runtime.handleMessage(id, principal, message, event -> send(emitter, "AGENT_EVENT", event));
                send(emitter, "MESSAGE_STARTED", Map.of());
                streamingReplies.stream(result,
                        delta -> send(emitter, "MESSAGE_DELTA", Map.of("text", delta)),
                        () -> {
                            send(emitter, "MESSAGE_COMPLETED", Map.of("message", result.message()));
                            send(emitter, "RESULT", result);
                            emitter.complete();
                        },
                        error -> {
                            send(emitter, "MESSAGE_COMPLETED", Map.of("message", result.message()));
                            send(emitter, "RESULT", result);
                            emitter.complete();
                        });
            } catch (Throwable error) {
                send(emitter, "STREAM_ERROR", Map.of("message", "本次回复未完成，请重试"));
                emitter.completeWithError(error);
            }
        };
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
                                      @RequestHeader(value = "X-Demo-User-Id", required = false) String userId,
                                      @Valid @RequestBody ConfirmRequest request) {
        return runtime.confirmPlan(new TaskId(taskId), identity.resolve(userId),
                new PlanId(request.planId()), request.planVersion(), request.idempotencyKey());
    }

    @GetMapping("/{taskId}")
    public TaskSnapshot getTask(@PathVariable String taskId,
                                @RequestHeader(value = "X-Demo-User-Id", required = false) String userId) {
        return runtime.getTask(new TaskId(taskId), identity.resolve(userId));
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String taskId,
                             @RequestHeader(value = "X-Demo-User-Id", required = false) String userId)
            throws IOException {
        TaskId id = new TaskId(taskId);
        Principal principal = identity.resolve(userId);
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
        return emitter;
    }

    public record CreateTaskRequest(String title) { }

    public record MessageRequest(@NotBlank String message) { }

    public record ConfirmRequest(@NotBlank String planId, @NotNull Integer planVersion,
                                 @NotBlank @Size(max = 64) String idempotencyKey) { }

    public record TaskCreatedResponse(String taskId, String status) { }
}
