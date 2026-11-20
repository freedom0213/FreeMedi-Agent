package com.mediflow.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AgentCallPolicyTest {
    @Test
    void twoTransientFailuresExhaustTheRetryLimit() {
        var calls = new AtomicInteger();
        var attempts = new ArrayList<AgentCallPolicy.Attempt>();
        var policy = new AgentCallPolicy(Runnable::run, 15000, 5000, 45000);

        assertThatThrownBy(() -> policy.run(AgentCallPolicy.Kind.MODEL, policy.deadline(), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException(new SocketTimeoutException());
        }, value -> null, (attempt, value) -> attempts.add(attempt)))
                .isInstanceOf(AgentCallPolicy.Failure.class).hasMessage("MODEL_TIMEOUT");

        assertThat(calls.get()).isEqualTo(2);
        assertThat(attempts).extracting(AgentCallPolicy.Attempt::reason)
                .containsExactly("MODEL_TIMEOUT", "MODEL_TIMEOUT");
    }

    @Test
    void permanentModelAndToolFailuresAreAttemptedOnce() {
        var reasons = new ArrayList<String>();
        var policy = new AgentCallPolicy(Runnable::run, 15000, 5000, 45000);
        for (var reason : List.of(ModelDecisionException.Reason.MODEL_AUTH_REJECTED,
                ModelDecisionException.Reason.MODEL_QUOTA_REJECTED)) {
            assertThatThrownBy(() -> policy.run(AgentCallPolicy.Kind.MODEL, policy.deadline(),
                    () -> { throw new ModelDecisionException(reason); },
                    value -> null, (attempt, value) -> reasons.add(attempt.reason())))
                    .hasMessage(reason.name());
        }
        var rejected = policy.run(AgentCallPolicy.Kind.TOOL, policy.deadline(),
                () -> ToolResult.failure("INVALID_TOOL_ARGUMENT", "bad input"),
                ToolResult::errorCode, (attempt, value) -> reasons.add(attempt.reason()));

        assertThat(rejected.success()).isFalse();
        assertThat(reasons).containsExactly("MODEL_AUTH_REJECTED", "MODEL_QUOTA_REJECTED", "INVALID_TOOL_ARGUMENT");
    }

    @Test
    void requestBudgetCanExpireBeforeThePerCallTimeout() {
        var worker = Executors.newSingleThreadExecutor();
        var attempts = new ArrayList<AgentCallPolicy.Attempt>();
        try {
            var policy = new AgentCallPolicy(worker, 20000, 20000, 100);
            assertThatThrownBy(() -> policy.run(AgentCallPolicy.Kind.TOOL, policy.deadline(), () -> {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "late";
            }, value -> null, (attempt, value) -> attempts.add(attempt)))
                    .hasMessage("AGENT_DEADLINE");
            assertThat(attempts).singleElement().satisfies(attempt ->
                    assertThat(attempt.durationMs()).isLessThan(2000));
        } finally {
            worker.shutdownNow();
        }
    }
}
