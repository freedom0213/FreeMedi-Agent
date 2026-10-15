package com.mediflow.agent.config;

import com.mediflow.agent.agent.AgentTurnResult;
import com.mediflow.agent.agent.StreamingReplyService;

import java.util.function.Consumer;

/** Deterministic local stream used when no model key is configured. */
public final class DemoStreamingReplyService implements StreamingReplyService {
    @Override
    public void stream(AgentTurnResult result, Consumer<String> onDelta,
                       Runnable onComplete, Consumer<Throwable> onError) {
        try {
            String message = result.message();
            int step = Math.max(8, Math.min(24, message.length() / 4));
            for (int offset = 0; offset < message.length(); offset += step) {
                onDelta.accept(message.substring(offset, Math.min(message.length(), offset + step)));
            }
            onComplete.run();
        } catch (Throwable error) {
            onError.accept(error);
        }
    }
}
