package com.mediflow.agent.agent;

import java.util.List;

public interface DecisionModel {
    AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools);

    default AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools,
                                 java.util.function.Consumer<AgentEvent> progress) {
        return decide(context, tools);
    }
}
