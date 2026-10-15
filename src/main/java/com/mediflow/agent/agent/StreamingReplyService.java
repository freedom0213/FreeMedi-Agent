package com.mediflow.agent.agent;

import java.util.function.Consumer;

/** Streams the patient-facing wording after the bounded agent turn has finished. */
public interface StreamingReplyService {
    void stream(AgentTurnResult result, Consumer<String> onDelta,
                Runnable onComplete, Consumer<Throwable> onError);
}
