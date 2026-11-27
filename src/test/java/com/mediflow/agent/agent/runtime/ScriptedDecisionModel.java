package com.mediflow.agent.agent.runtime;

import com.mediflow.agent.agent.model.AgentDecision;
import com.mediflow.agent.agent.model.DecisionContext;
import com.mediflow.agent.agent.model.DecisionModel;
import com.mediflow.agent.agent.tool.ReadToolSpec;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

final class ScriptedDecisionModel implements DecisionModel {
    private final Queue<AgentDecision> decisions;

    ScriptedDecisionModel(AgentDecision... decisions) {
        this.decisions = new ArrayDeque<>(Arrays.asList(decisions));
    }

    @Override
    public AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools) {
        AgentDecision decision = decisions.poll();
        if (decision == null) {
            throw new AssertionError("scripted model ran out of decisions");
        }
        return decision;
    }
}
