package com.mediflow.agent.agent;

/**
 * Resolves a triage recommendation's department name to a real catalog id.
 *
 * <p>A recommendation is produced from the patient's own words, so the department may not exist in
 * this hospital's catalog. Implementations return {@code 0} in that case, which lets the runtime keep
 * an honest "unresolved" value instead of inventing an id that points at nothing.
 */
@FunctionalInterface
public interface DepartmentCatalog {
    long findActiveDepartmentId(String departmentName);
}
