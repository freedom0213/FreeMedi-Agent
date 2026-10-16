package com.mediflow.agent.appointment;

import java.time.LocalDate;

/**
 * Slot search input shared by the agent tool and the portal.
 *
 * <p>A null field means "not specified by the patient". Department and doctor may be given either as
 * an id (portal, after browsing the catalog) or as a name (agent, from free text).
 */
public record SlotCriteria(Long departmentId, String departmentName,
                           Long doctorId, String doctorName,
                           LocalDate appointmentDate, String clinicPeriod,
                           int limit) {
}
