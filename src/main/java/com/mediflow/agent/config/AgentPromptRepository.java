package com.mediflow.agent.config;

import com.mediflow.agent.agent.AgentPrompt;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@Profile("mysql")
final class AgentPromptRepository {
    private static final String SELECT = """
            SELECT p.id, p.triage_guidance, p.selection_guidance, p.note, p.created_at, u.display_name,
                   p.trial_fingerprint, p.trial_passed, p.trial_report, p.tested_at
            FROM agent_prompt_revision p JOIN sys_user u ON u.id = p.created_by
            """;
    private static final RowMapper<Version> VERSION = (row, index) -> new Version(row.getLong("id"),
            prompt(row), row.getString("note"), row.getString("display_name"), instant(row.getTimestamp("created_at")),
            row.getString("trial_fingerprint"), row.getObject("trial_passed", Boolean.class),
            row.getString("trial_report"), instant(row.getTimestamp("tested_at")));
    private final JdbcTemplate jdbc;

    AgentPromptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Selection selection() {
        return jdbc.queryForObject("""
                SELECT s.revision, s.active_revision_id, s.activated_at, p.id, p.triage_guidance, p.selection_guidance
                FROM agent_prompt_settings s LEFT JOIN agent_prompt_revision p ON s.active_revision_id = p.id
                WHERE s.id = 1
                """, (rs, row) -> {
            Long id = rs.getObject("active_revision_id", Long.class);
            return new Selection(rs.getLong("revision"), id, instant(rs.getTimestamp("activated_at")),
                    id == null ? AgentPrompt.BUILT_IN : prompt(rs));
        });
    }

    List<Version> versions() { return jdbc.query(SELECT + " ORDER BY p.id DESC", VERSION); }

    Optional<Version> version(long id) {
        return jdbc.query(SELECT + " WHERE p.id = ?", VERSION, id).stream().findFirst();
    }

    void save(String triage, String selection, String note, long actor) {
        jdbc.update("""
                INSERT INTO agent_prompt_revision (triage_guidance, selection_guidance, note, created_by)
                VALUES (?, ?, ?, ?)
                """, triage, selection, note, actor);
    }

    void recordTrial(long id, String fingerprint, boolean passed, String report) {
        jdbc.update("""
                UPDATE agent_prompt_revision SET trial_fingerprint = ?, trial_passed = ?, trial_report = ?,
                    tested_at = CURRENT_TIMESTAMP(3) WHERE id = ?
                """, fingerprint, passed, report, id);
    }

    boolean activate(Long id, long expectedRevision) {
        return jdbc.update("""
                UPDATE agent_prompt_settings SET active_revision_id = ?, revision = revision + 1,
                    activated_at = CURRENT_TIMESTAMP(3) WHERE id = 1 AND revision = ?
                """, id, expectedRevision) == 1;
    }

    private static AgentPrompt prompt(ResultSet row) throws SQLException {
        return new AgentPrompt("v" + row.getLong("id"), row.getString("triage_guidance"), row.getString("selection_guidance"));
    }

    private static Instant instant(Timestamp time) { return time == null ? null : time.toInstant(); }

    record Selection(long revision, Long activeId, Instant activatedAt, AgentPrompt prompt) {}
    record Version(long id, AgentPrompt prompt, String note, String createdBy, Instant createdAt,
                   String fingerprint, Boolean passed, String report, Instant testedAt) {}
}
