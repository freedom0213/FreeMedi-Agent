package com.mediflow.agent.agent;

/** Patient-facing direction, grounded in the hospital catalog; never a diagnosis. */
public record TriageRecommendation(long departmentId, String departmentName, String reason) {
    public TriageRecommendation {
        if (departmentId < 0 || departmentName == null || departmentName.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("triage recommendation is incomplete");
        }
    }
}
