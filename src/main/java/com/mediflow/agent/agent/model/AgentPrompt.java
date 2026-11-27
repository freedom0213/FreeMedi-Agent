package com.mediflow.agent.agent.model;

/** Immutable conversation guidance captured with a single request. */
public record AgentPrompt(String version, String triage, String selection) {
    public static final AgentPrompt BUILT_IN = new AgentPrompt("builtin-v1", "", "");
}
