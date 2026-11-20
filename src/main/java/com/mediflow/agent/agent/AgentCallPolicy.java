package com.mediflow.agent.agent;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Runs model decisions and read-only work against one shared deadline per request. */
public final class AgentCallPolicy {
    public enum Kind { MODEL, TOOL }

    public record Attempt(int number, long durationMs, String reason) { }

    public static final class Failure extends RuntimeException {
        private final String code;

        private Failure(String code) {
            this(code, null);
        }

        private Failure(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() { return code; }
    }

    private final Executor executor;
    private final long modelNanos;
    private final long toolNanos;
    private final long requestNanos;

    public AgentCallPolicy(Executor executor, long modelMs, long toolMs, long requestMs) {
        if (modelMs <= 0 || toolMs <= 0 || requestMs <= 0) {
            throw new IllegalArgumentException("Agent deadlines must be positive");
        }
        this.executor = executor;
        this.modelNanos = TimeUnit.MILLISECONDS.toNanos(modelMs);
        this.toolNanos = TimeUnit.MILLISECONDS.toNanos(toolMs);
        this.requestNanos = TimeUnit.MILLISECONDS.toNanos(requestMs);
    }

    long deadline() {
        return System.nanoTime() + requestNanos;
    }

    <T> T run(Kind kind, long deadline, Supplier<T> call, Function<T, String> errorCode,
              BiConsumer<Attempt, T> observe) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            long start = System.nanoTime();
            T value = null;
            RuntimeException original = null;
            String reason;
            try {
                value = invoke(kind, deadline, call);
                reason = errorCode.apply(value);
            } catch (RuntimeException failure) {
                reason = classify(kind, failure);
                original = failure;
            }
            observe.accept(new Attempt(attempt, elapsed(start), reason), value);
            if (attempt == 1 && retryable(reason) && System.nanoTime() < deadline) continue;
            if (value != null) return value;
            throw new Failure(reason == null ? kind + "_EMPTY_RESULT" : reason, original);
        }
        throw new IllegalStateException("No agent call result");
    }

    private <T> T invoke(Kind kind, long deadline, Supplier<T> call) {
        if (System.nanoTime() >= deadline) throw new Failure("AGENT_DEADLINE");
        if (Thread.currentThread().isInterrupted()) throw new Failure("AGENT_INTERRUPTED");
        // A direct executor is used only by the in-memory fixtures.
        FutureTask<T> work = new FutureTask<>(call::get);
        executor.execute(work);
        long remaining = Math.min(deadline - System.nanoTime(), kind == Kind.MODEL ? modelNanos : toolNanos);
        try {
            return work.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            work.cancel(true);
            throw new Failure(System.nanoTime() >= deadline ? "AGENT_DEADLINE" : kind + "_TIMEOUT");
        } catch (InterruptedException interrupted) {
            work.cancel(true);
            Thread.currentThread().interrupt();
            throw new Failure("AGENT_INTERRUPTED");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new Failure(kind + "_ERROR");
        }
    }

    private static String classify(Kind kind, RuntimeException error) {
        if (error instanceof Failure failure) return failure.code();
        if (error instanceof RejectedExecutionException) return "AGENT_BUSY";
        if (kind == Kind.TOOL && error instanceof IllegalArgumentException) return "INVALID_TOOL_ARGUMENT";
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelDecisionException model) return model.reason().name();
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof SQLTimeoutException) {
                return kind + "_TIMEOUT";
            }
            if (cause instanceof ConnectException || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException) return kind + "_TRANSIENT";
            if (cause == cause.getCause()) break;
        }
        return kind + "_ERROR";
    }

    static boolean retryable(String code) {
        return code != null && switch (code) {
            case "MODEL_TIMEOUT", "MODEL_TRANSIENT", "TOOL_TIMEOUT", "TOOL_TRANSIENT",
                    "KNOWLEDGE_INDEX_UNAVAILABLE" -> true;
            default -> false;
        };
    }

    static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
