package com.mediflow.agent.agent.booking;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Validates changes to saved requirements, returns real options and rechecks a selected slot. */
public interface AppointmentOptions {
    Search search(Map<String, Object> saved, Map<String, Object> changes, String source);

    PlanDraft draft(long patientId, Map<?, ?> candidate, Instant expiresAt);

    record Search(Map<String, Object> requirements, List<Map<String, Object>> slots, String message) { }
}
