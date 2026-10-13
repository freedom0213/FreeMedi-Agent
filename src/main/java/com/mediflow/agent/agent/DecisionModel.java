package com.mediflow.agent.agent;

import java.util.List;

public interface DecisionModel {
    AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools);
}
