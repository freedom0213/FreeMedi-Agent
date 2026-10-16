package com.mediflow.agent.appointment;

import java.time.LocalDate;
import java.util.List;

/**
 * Slot search output.
 *
 * <p>{@code exactMatch} is false when the search had to drop a date or period preference to find
 * anything. Only the agent path may produce that, because only the agent explains the change and
 * asks the patient to confirm it.
 */
public record SlotSearch(List<AvailableSlot> slots, boolean exactMatch,
                         LocalDate requestedDate, String requestedPeriod, LocalDate searchEndDate) {

    public int count() {
        return slots.size();
    }
}
