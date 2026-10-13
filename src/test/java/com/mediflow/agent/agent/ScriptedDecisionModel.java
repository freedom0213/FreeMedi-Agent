package com.mediflow.agent.agent;

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
