package com.mediflow.agent.api;

import java.io.IOException;

import com.mediflow.agent.agent.AgentRuntime;
import com.mediflow.agent.agent.AgentTurnResult;
import com.mediflow.agent.agent.ConfirmationResult;
import com.mediflow.agent.agent.PlanId;
import com.mediflow.agent.agent.Principal;
import com.mediflow.agent.agent.TaskId;
import com.mediflow.agent.agent.TaskSnapshot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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

@RestController
@RequestMapping("/api/v1/tasks")
@Profile("mysql")
public class AgentTaskController {
    private final AgentRuntime runtime;
    private final DemoIdentityService identity;
    private final MySqlTaskApplicationService tasks;
    private final MySqlAgentEventHistory history;

    public AgentTaskController(AgentRuntime runtime, DemoIdentityService identity,
                               MySqlTaskApplicationService tasks, MySqlAgentEventHistory history) {
        this.runtime = runtime;
        this.identity = identity;
        this.tasks = tasks;
        this.history = history;
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
                                 @NotBlank String idempotencyKey) { }

    public record TaskCreatedResponse(String taskId, String status) { }
}
