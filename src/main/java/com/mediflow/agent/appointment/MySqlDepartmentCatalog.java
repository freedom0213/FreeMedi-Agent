package com.mediflow.agent.appointment;

import java.util.List;

import javax.sql.DataSource;

import com.mediflow.agent.agent.DepartmentCatalog;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves department names against the synthetic hospital catalog. */
@Service
@Profile("mysql")
public final class MySqlDepartmentCatalog implements DepartmentCatalog {
    private final JdbcTemplate jdbc;

    public MySqlDepartmentCatalog(DataSource dataSource) {
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
}
