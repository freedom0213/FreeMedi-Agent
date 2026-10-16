package com.mediflow.agent.appointment;

import java.util.List;

import javax.sql.DataSource;

import com.mediflow.agent.agent.DepartmentCatalog;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads the synthetic hospital's department and doctor catalog.
 *
 * <p>It serves two callers: the agent resolves a triage recommendation's department name through
 * {@link DepartmentCatalog}, and the portal browses the published directory. Both only ever see
 * active rows, so a disabled department or clinician disappears from every path at once.
 */
@Service
@Profile("mysql")
public final class MySqlHospitalDirectory implements DepartmentCatalog {

    public record DepartmentView(long id, String code, String name, String description) {
    }

    public record DoctorView(long id, long departmentId, String name, String title,
                             String specialty, String introduction) {
    }

    private final JdbcTemplate jdbc;

    public MySqlHospitalDirectory(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public long findActiveDepartmentId(String departmentName) {
        if (departmentName == null || departmentName.isBlank()) {
            return 0L;
        }
        // Matches the read tool's lookup: a patient may name either the display name or the code.
        List<Long> matches = jdbc.query("""
                SELECT id FROM department
                WHERE status = 'ACTIVE' AND (name = ? OR code = ?)
                ORDER BY id
                LIMIT 1
                """, (result, rowNum) -> result.getLong("id"), departmentName.trim(), departmentName.trim());
        return matches.isEmpty() ? 0L : matches.get(0);
    }

    public List<DepartmentView> listActiveDepartments() {
        return jdbc.query("""
                SELECT id, code, name, description FROM department
                WHERE status = 'ACTIVE'
                ORDER BY id
                """, (result, rowNum) -> new DepartmentView(
                result.getLong("id"), result.getString("code"), result.getString("name"),
                result.getString("description")));
    }

    public List<DoctorView> listActiveDoctors(long departmentId) {
        return jdbc.query("""
                SELECT id, department_id, name, title, specialty, introduction FROM doctor
                WHERE department_id = ? AND status = 'ACTIVE'
                ORDER BY id
                """, (result, rowNum) -> new DoctorView(
                result.getLong("id"), result.getLong("department_id"), result.getString("name"),
                result.getString("title"), result.getString("specialty"),
                result.getString("introduction")), departmentId);
    }
}
