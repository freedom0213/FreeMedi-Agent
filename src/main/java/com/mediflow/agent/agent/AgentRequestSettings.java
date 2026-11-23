package com.mediflow.agent.agent;

import java.util.Map;

/** One immutable set of runtime choices for a patient request. */
public record AgentRequestSettings(DecisionModel model, String modelVersion, AgentCallPolicy calls,
                                   Map<String, ToolPolicy> tools, AgentPrompt prompt) {
    public AgentRequestSettings(DecisionModel model, String modelVersion, AgentCallPolicy calls,
                                Map<String, ToolPolicy> tools) {
        this(model, modelVersion, calls, tools, AgentPrompt.BUILT_IN);
    }
    public AgentRequestSettings {
        tools = Map.copyOf(tools);
    }

    public boolean toolEnabled(String name) {
        return !tools.containsKey(name) || tools.get(name).enabled();
    }

    public Long toolTimeout(String name) {
        return tools.containsKey(name) ? tools.get(name).timeoutMs() : null;
    }

    public record ToolPolicy(boolean enabled, Long timeoutMs) {}
}
