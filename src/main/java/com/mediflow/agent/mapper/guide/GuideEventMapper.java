package com.mediflow.agent.mapper.guide;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface GuideEventMapper {
    @Select("""
            SELECT e.created_at, e.event_no, e.event_type, e.to_status AS summary
            FROM agent_state_event e
            JOIN agent_task t ON t.id = e.task_id
            WHERE t.task_no = #{taskId} AND t.patient_id = #{patientId} AND e.metadata IS NULL
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<StateEventRow> stateEvents(@Param("taskId") String taskId, @Param("patientId") long patientId);

    @Select("""
            SELECT tc.created_at, tc.call_no, tc.tool_name, tc.status, tc.error_code
            FROM agent_tool_call tc
            JOIN agent_task t ON t.id = tc.task_id
            WHERE t.task_no = #{taskId} AND t.patient_id = #{patientId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<ToolCallRow> toolCalls(@Param("taskId") String taskId, @Param("patientId") long patientId);

    List<TraceRow> patientTrace(@Param("taskId") String taskId, @Param("patientId") long patientId);

    @Select("""
            SELECT task_no, task_type, status, current_turn_no, updated_at
            FROM agent_task ORDER BY updated_at DESC, id DESC LIMIT #{limit}
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<TaskSummaryRow> recentTaskSummaries(@Param("limit") int limit);

    @Select("""
            SELECT task_no, task_type, status, current_turn_no, updated_at
            FROM agent_task WHERE task_no = #{taskId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Optional<TaskSummaryRow> taskSummary(@Param("taskId") String taskId);

    List<TraceRow> adminTraceActions(@Param("taskId") String taskId, @Param("after") long after,
                                     @Param("limit") int limit);

    record StateEventRow(Instant createdAt, long sequence, String type, String summary) {}
    record ToolCallRow(Instant createdAt, long sequence, String toolName, String status, String errorCode) {}
    record TraceRow(long sequence, Instant recordedAt, String type, String fromStatus,
                    String toStatus, String reason, String metadata) {}
    record TaskSummaryRow(String taskId, String taskType, String status, int turnCount, Instant updatedAt) {}
}
