package com.mediflow.agent.agent.persistence;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** SQL stays inside this adapter; the runtime never sees database row IDs or SQL. */
@Mapper
public interface TaskPersistenceMapper {
    record TaskRow(long id, String taskNo, long patientId, String status, int stepCount,
                   int currentTurnNo, long stateVersion, String collectedFacts, String lastToolResult) { }
    record PlanRow(String planNo, int version, String planSnapshot, String status, String planDigest) { }
    record TurnRow(int turnNo, String clientRequestId, String userMessage, String assistantMessage,
                   String status) { }

    @Select("""
            SELECT id, task_no, patient_id, status, step_count, current_turn_no,
                   state_version, collected_facts, last_tool_result
            FROM agent_task WHERE task_no = #{taskNo}
            """)
    TaskRow findTask(@Param("taskNo") String taskNo);

    @Insert("""
            INSERT INTO agent_task(task_no, session_id, patient_id, task_type, status, collected_facts)
            SELECT #{taskNo}, id, patient_id, 'APPOINTMENT_BOOKING', 'UNDERSTANDING', '{}'
            FROM agent_session WHERE id = #{sessionId} AND patient_id = #{patientId} AND status = 'ACTIVE'
            """)
    int createTask(@Param("taskNo") String taskNo, @Param("sessionId") long sessionId,
                   @Param("patientId") long patientId);

    @Update("""
            UPDATE agent_task SET status = #{status}, current_turn_no = #{turnNo}, step_count = #{stepCount},
                   collected_facts = #{facts}, last_tool_result = #{lastToolResult},
                   state_version = state_version + 1,
                   finished_at = CASE WHEN #{status} IN ('COMPLETED', 'FAILED', 'CANCELLED')
                                      THEN CURRENT_TIMESTAMP(3) ELSE NULL END
            WHERE id = #{id} AND patient_id = #{patientId} AND state_version = #{version}
            """)
    int updateTask(@Param("id") long id, @Param("patientId") long patientId,
                   @Param("status") String status, @Param("turnNo") int turnNo,
                   @Param("stepCount") int stepCount, @Param("facts") String facts,
                   @Param("lastToolResult") String lastToolResult, @Param("version") long version);

    @Select("""
            SELECT plan_no, version, plan_snapshot, status, plan_digest FROM agent_plan
            WHERE task_id = #{taskId} ORDER BY version DESC LIMIT 1
            """)
    PlanRow latestPlan(@Param("taskId") long taskId);

    @Select("""
            SELECT plan_no, version, plan_snapshot, status, plan_digest FROM agent_plan
            WHERE task_id = #{taskId} AND version = #{version}
            """)
    PlanRow findPlan(@Param("taskId") long taskId, @Param("version") int version);

    @Insert("""
            INSERT INTO agent_plan(plan_no, task_id, patient_id, version, department_id, doctor_id, slot_id,
                                   plan_snapshot, plan_digest, status, expires_at)
            VALUES (#{planNo}, #{taskId}, #{patientId}, #{version}, #{departmentId}, #{doctorId}, #{slotId},
                    #{snapshot}, #{digest}, #{status}, #{expiresAt})
            """)
    void insertPlan(@Param("planNo") String planNo, @Param("taskId") long taskId,
                    @Param("patientId") long patientId, @Param("version") int version,
                    @Param("departmentId") long departmentId, @Param("doctorId") long doctorId,
                    @Param("slotId") long slotId, @Param("snapshot") String snapshot,
                    @Param("digest") String digest, @Param("status") String status,
                    @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE agent_plan SET status = #{status},
                   confirmed_at = CASE WHEN #{status} IN ('CONFIRMED', 'EXECUTED')
                                       THEN COALESCE(confirmed_at, CURRENT_TIMESTAMP(3)) ELSE confirmed_at END
            WHERE task_id = #{taskId} AND version = #{version}
            """)
    void updatePlanStatus(@Param("taskId") long taskId, @Param("version") int version,
                          @Param("status") String status);

    @Select("""
            SELECT turn_no, client_request_id, user_message, assistant_message, status
            FROM agent_turn WHERE task_id = #{taskId} ORDER BY turn_no
            """)
    List<TurnRow> turns(@Param("taskId") long taskId);

    @Insert("""
            INSERT INTO agent_turn(task_id, turn_no, client_request_id, user_message, assistant_message,
                                   status, completed_at)
            VALUES (#{taskId}, #{turnNo}, #{requestId}, #{userMessage}, #{assistantMessage}, #{status},
                    CASE WHEN #{status} = 'PROCESSING' THEN NULL ELSE CURRENT_TIMESTAMP(3) END)
            ON DUPLICATE KEY UPDATE assistant_message = #{assistantMessage}, status = #{status},
                completed_at = CASE WHEN #{status} = 'PROCESSING' THEN NULL ELSE CURRENT_TIMESTAMP(3) END
            """)
    void saveTurn(@Param("taskId") long taskId, @Param("turnNo") int turnNo,
                  @Param("requestId") String requestId, @Param("userMessage") String userMessage,
                  @Param("assistantMessage") String assistantMessage, @Param("status") String status);

    @Select("""
            SELECT id FROM agent_turn WHERE task_id = #{taskId} AND turn_no = #{turnNo}
            """)
    long turnId(@Param("taskId") long taskId, @Param("turnNo") int turnNo);

    @Select("""
            SELECT COALESCE(MAX(tc.call_no), 0) FROM agent_tool_call tc JOIN agent_turn t ON t.id = tc.turn_id
            WHERE t.task_id = #{taskId} AND t.turn_no = #{turnNo}
            """)
    int toolCallCount(@Param("taskId") long taskId, @Param("turnNo") int turnNo);

    @Insert("""
            INSERT INTO agent_tool_call(task_id, turn_id, call_no, tool_name, arguments, result_summary,
                                        status, error_code, duration_ms, completed_at)
            VALUES (#{taskId}, #{turnId}, #{callNo}, #{toolName}, #{arguments}, #{result},
                    #{status}, #{errorCode}, #{durationMs},
                    CASE WHEN #{status} = 'STARTED' THEN NULL ELSE CURRENT_TIMESTAMP(3) END)
            ON DUPLICATE KEY UPDATE result_summary = #{result}, status = #{status}, error_code = #{errorCode},
                duration_ms = #{durationMs},
                completed_at = CASE WHEN #{status} = 'STARTED' THEN NULL ELSE CURRENT_TIMESTAMP(3) END
            """)
    void saveTool(@Param("taskId") long taskId, @Param("turnId") long turnId, @Param("callNo") int callNo,
                  @Param("toolName") String toolName, @Param("arguments") String arguments,
                  @Param("result") String result, @Param("status") String status,
                  @Param("errorCode") String errorCode, @Param("durationMs") long durationMs);

    @Select("SELECT COALESCE(MAX(event_no), 0) FROM agent_state_event WHERE task_id = #{taskId}")
    long lastEventNo(@Param("taskId") long taskId);

    @Insert("""
            INSERT INTO agent_state_event(task_id, event_no, from_status, to_status, event_type, actor_type)
            VALUES (#{taskId}, #{eventNo}, #{from}, #{to}, #{type}, 'SYSTEM')
            """)
    void insertEvent(@Param("taskId") long taskId, @Param("eventNo") long eventNo,
                     @Param("from") String from, @Param("to") String to, @Param("type") String type);
}
