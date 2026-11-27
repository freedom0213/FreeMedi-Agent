package com.mediflow.agent.mapper.admin.agent;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AgentPromptMapper {
    Selection selection();
    List<Version> versions();
    Optional<Version> version(@Param("id") long id);

    @Insert("""
            INSERT INTO agent_prompt_revision (triage_guidance, selection_guidance, note, created_by)
            VALUES (#{triage}, #{selection}, #{note}, #{actor})
            """)
    void save(@Param("triage") String triage, @Param("selection") String selection, @Param("note") String note,
              @Param("actor") long actor);

    @Update("""
            UPDATE agent_prompt_revision SET trial_fingerprint = #{fingerprint}, trial_passed = #{passed}, trial_report = #{report},
                tested_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}
            """)
    void recordTrial(@Param("id") long id, @Param("fingerprint") String fingerprint,
                     @Param("passed") boolean passed, @Param("report") String report);

    @Update("""
            UPDATE agent_prompt_settings SET active_revision_id = #{id,jdbcType=BIGINT}, revision = revision + 1,
                activated_at = CURRENT_TIMESTAMP(3) WHERE id = 1 AND revision = #{expectedRevision}
            """)
    int activate(@Param("id") Long id, @Param("expectedRevision") long expectedRevision);

    record Selection(long revision, Long activeId, Instant activatedAt, String triage, String selection) {}
    record Version(long id, String triage, String selection, String note, String createdBy, Instant createdAt,
                   String fingerprint, Boolean passed, String report, Instant testedAt) {}
}
