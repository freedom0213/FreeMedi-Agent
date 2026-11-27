package com.mediflow.agent.mapper.guide;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface GuideSessionMapper {
    @Insert("INSERT INTO agent_session(patient_id, title, status) VALUES (#{patientId}, #{title}, 'ACTIVE')")
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    void insertSession(@Param("patientId") long patientId, @Param("title") String title,
                       @Param("key") SessionKey key);

    @Delete("DELETE FROM agent_session WHERE id = #{id}")
    void deleteSession(@Param("id") long id);

    List<ConversationRow> history(@Param("patientId") long patientId, @Param("before") Long before,
                                  @Param("limit") int limit);

    final class SessionKey {
        private Long id;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    record ConversationRow(long id, String taskId, String title, String status, Instant updatedAt) {}
}
