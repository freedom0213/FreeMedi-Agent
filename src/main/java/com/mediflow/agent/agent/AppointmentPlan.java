package com.mediflow.agent.agent;

public record AppointmentPlan(PlanId id, int version, PlanDraft draft, PlanStatus status) {
    public AppointmentPlan {
        if (id == null || version <= 0 || draft == null || status == null) {
            throw new IllegalArgumentException("invalid appointment plan");
        }
    }
}
