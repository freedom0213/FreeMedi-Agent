package com.mediflow.agent.api;

import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import com.mediflow.agent.agent.Principal;
import com.mediflow.agent.agent.TaskId;
import com.mediflow.agent.agent.persistence.MySqlTaskStateStore;
import com.mediflow.agent.appointment.AppointmentQueryService;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class MySqlTaskApplicationService {
    private final JdbcTemplate jdbc;
    private final MySqlTaskStateStore stateStore;
    private final AppointmentQueryService appointments;

    public MySqlTaskApplicationService(DataSource dataSource, MySqlTaskStateStore stateStore,
                                       AppointmentQueryService appointments) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.stateStore = stateStore;
        this.appointments = appointments;
    }

    public TaskId createTask(Principal principal, String title) {
        if (principal == null) {
            throw new IllegalArgumentException("principal is required");
        }
        long sessionId = createSession(principal.userId(), title);
        try {
            return stateStore.create(sessionId, principal.userId());
        } catch (RuntimeException exception) {
            jdbc.update("DELETE FROM agent_session WHERE id = ?", sessionId);
            throw exception;
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<TaskId> openVisitConsultation(Principal principal, String appointmentNo) {
        long patientId = principal.userId();
        if (!appointments.lockBookedForPatient(appointmentNo, patientId)) return Optional.empty();
        var appointment = appointments.findForPatient(appointmentNo, patientId).orElseThrow();
        var existing = stateStore.findVisitConsultation(patientId, appointment.appointmentNo());
        if (existing.isPresent()) return existing;
        long sessionId = createSession(patientId, "就诊准备咨询");
        return Optional.of(stateStore.createVisitConsultation(sessionId, patientId, Map.of(
                "appointmentNo", appointment.appointmentNo(), "departmentName", appointment.departmentName(),
                "doctorName", appointment.doctorName(), "appointmentDate", appointment.appointmentDate(),
                "timeRange", appointment.timeRange(), "bookedVia", appointment.bookedVia())));
    }

    public record GuideConversation(String taskId, String title, String status, Instant updatedAt) {}
    public record GuideHistory(List<GuideConversation> items, Long nextCursor) {}
    private record HistoryRow(long id, GuideConversation conversation) {}

    public GuideHistory guideHistory(Principal principal, Long before) {
        if (before != null && before < 1) throw new IllegalArgumentException("无效的历史会话分页位置");
        int pageSize = 20;
        String sql = """
                SELECT t.id, t.task_no, t.status, t.updated_at,
                       COALESCE(NULLIF(LEFT(TRIM(first_turn.user_message), 64), ''),
                                NULLIF(s.title, ''), '智能导诊与预约') AS title
                FROM agent_task t
                JOIN agent_session s ON s.id = t.session_id AND s.patient_id = t.patient_id
                LEFT JOIN agent_turn first_turn ON first_turn.task_id = t.id AND first_turn.turn_no = 1
                WHERE t.patient_id = ? AND t.task_type = 'APPOINTMENT_BOOKING'
                """;
        var params = before == null ? new Object[]{principal.userId(), pageSize + 1}
                : new Object[]{principal.userId(), before, pageSize + 1};
        if (before != null) sql += " AND t.id < ?";
        var rows = jdbc.query(sql + " ORDER BY t.id DESC LIMIT ?", (row, index) -> new HistoryRow(row.getLong("id"),
                new GuideConversation(row.getString("task_no"), row.getString("title"), row.getString("status"),
                        row.getTimestamp("updated_at").toInstant())), params);
        var items = rows.stream().limit(pageSize).map(HistoryRow::conversation).toList();
        return new GuideHistory(items, rows.size() > pageSize ? rows.get(pageSize - 1).id() : null);
    }

    private long createSession(long patientId, String title) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO agent_session(patient_id, title, status)
                    VALUES (?, ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, patientId);
            statement.setString(2, title == null || title.isBlank() ? "预约助手演示会话" : title.trim());
            return statement;
        }, keyHolder);
        Number sessionId = keyHolder.getKey();
        if (sessionId == null) {
            throw new IllegalStateException("could not create agent session");
        }
        return sessionId.longValue();
    }
}
