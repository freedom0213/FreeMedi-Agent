package com.mediflow.agent.agent;

public record PlanId(String value) {
    public PlanId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("plan id must not be blank");
        }
    }
}
