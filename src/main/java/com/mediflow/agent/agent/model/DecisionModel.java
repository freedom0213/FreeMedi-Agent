package com.mediflow.agent.agent.model;

import com.mediflow.agent.agent.runtime.AgentEvent;
import com.mediflow.agent.agent.tool.ReadToolSpec;
import java.util.List;

public interface DecisionModel {
    AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools);

    default AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools, AgentPrompt prompt) {
        return decide(context, tools);
    }

    default AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools,
                                 java.util.function.Consumer<AgentEvent> progress) {
        return decide(context, tools);
    }
}
