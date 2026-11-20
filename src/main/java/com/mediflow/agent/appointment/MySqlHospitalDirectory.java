package com.mediflow.agent.appointment;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.mediflow.agent.agent.DepartmentCatalog;
import com.mediflow.agent.agent.DoctorDirectory;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads the synthetic hospital's department and doctor catalog.
 *
 * <p>It serves three callers: the agent resolves a triage recommendation's department name through
 * {@link DepartmentCatalog}, the knowledge tool checks that a passage's claimed clinician is really
 * active in the claimed department, and the portal browses the published directory. All of them only
 * ever see active rows, so a disabled department or clinician disappears from every path at once.
 */
@Service
@Profile("mysql")
public final class MySqlHospitalDirectory implements DepartmentCatalog, DoctorDirectory {

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

    @Override
    public long findActiveDepartmentIdMentionedIn(String question) {
        List<Long> matches = jdbc.query("""
                SELECT id FROM department
                WHERE status = 'ACTIVE' AND INSTR(?, name) > 0
                ORDER BY id
                LIMIT 2
                """, (result, rowNum) -> result.getLong("id"), question);
        return matches.size() == 1 ? matches.get(0) : 0L;
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

    @Override
    public Optional<Clinician> findActiveClinicianMentionedIn(String question) {
        List<Clinician> named = jdbc.query("""
                SELECT dr.id, dr.department_id
                FROM doctor dr JOIN department d ON d.id = dr.department_id
                WHERE dr.status = 'ACTIVE' AND d.status = 'ACTIVE' AND INSTR(?, dr.name) > 0
                ORDER BY dr.id
                LIMIT 2
                """, (result, rowNum) -> new Clinician(result.getLong("id"), result.getLong("department_id")),
                question);
        return named.size() == 1 ? Optional.of(named.get(0)) : Optional.empty();
    }

    /**
     * One predicate, both conditions. Asking only "is this doctor active" would accept a passage that
     * attributes a clinician to the wrong department, which is exactly the kind of claim a patient
     * would act on.
     */
    @Override
    public boolean isActiveDoctorInDepartment(long doctorId, long departmentId) {
        if (doctorId <= 0 || departmentId <= 0) {
            return false;
        }
        Integer matches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM doctor dr JOIN department d ON d.id = dr.department_id
                WHERE dr.id = ? AND d.id = ? AND dr.status = 'ACTIVE' AND d.status = 'ACTIVE'
                """, Integer.class, doctorId, departmentId);
        return matches != null && matches > 0;
    }
}
