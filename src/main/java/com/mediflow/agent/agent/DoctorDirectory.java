package com.mediflow.agent.agent;

import java.util.Optional;

/**
 * Verifies that a clinician named in hospital material really is a registered, active clinician in
 * the department that material claims.
 *
 * <p>Material outlives the catalog it describes: a doctor can be deregistered or move department
 * while their introduction is still stored and still semantically similar to a patient's question.
 * Retrieval cannot notice that, so anything that names a clinician has to be checked against the live
 * catalog before it is shown — the same rule the appointment path already follows, where a
 * recommendation that is not in the catalog is reported as such rather than invented.
 */
public interface DoctorDirectory {
    record Clinician(long id, long departmentId) { }

    /** Resolves a single unambiguous active name from the patient's wording. */
    Optional<Clinician> findActiveClinicianMentionedIn(String question);

    /** True only when the doctor exists, is active, and belongs to that department. */
    boolean isActiveDoctorInDepartment(long doctorId, long departmentId);
}
