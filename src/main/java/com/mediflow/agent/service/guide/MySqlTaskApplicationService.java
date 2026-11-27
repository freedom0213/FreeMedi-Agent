package com.mediflow.agent.service.guide;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mediflow.agent.agent.runtime.Principal;
import com.mediflow.agent.agent.task.TaskId;
import com.mediflow.agent.agent.persistence.MySqlTaskStateStore;
import com.mediflow.agent.mapper.guide.GuideSessionMapper;
import com.mediflow.agent.mapper.guide.GuideSessionMapper.SessionKey;
import com.mediflow.agent.service.appointment.query.AppointmentQueryService;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class MySqlTaskApplicationService {
    private final GuideSessionMapper sessions;
    private final MySqlTaskStateStore stateStore;
    private final AppointmentQueryService appointments;

    public MySqlTaskApplicationService(GuideSessionMapper sessions, MySqlTaskStateStore stateStore,
                                       AppointmentQueryService appointments) {
        this.sessions = sessions;
        this.stateStore = stateStore;
        this.appointments = appointments;
    }

    public TaskId createTask(Principal principal, String title) {
        if (principal == null) {
            throw new IllegalArgumentException("principal is required");
        }
        String sessionTitle = title == null || title.isBlank() ? "预约助手演示会话" : title.trim();
        long sessionId = createSession(principal.userId(), sessionTitle);
        try {
            return stateStore.create(sessionId, principal.userId());
        } catch (RuntimeException exception) {
            sessions.deleteSession(sessionId);
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

    private long createSession(long patientId, String title) {
        var key = new SessionKey();
        sessions.insertSession(patientId, title, key);
        Long id = key.getId();
        if (id == null) {
            throw new IllegalStateException("could not create agent session");
        }
        return id;
    }

    public record GuideConversation(String taskId, String title, String status, Instant updatedAt) {}
    public record GuideHistory(List<GuideConversation> items, Long nextCursor) {}

    public GuideHistory guideHistory(Principal principal, Long before) {
        if (before != null && before < 1) throw new IllegalArgumentException("无效的历史会话分页位置");
        int pageSize = 20;
        var rows = sessions.history(principal.userId(), before, pageSize + 1);
        var items = rows.stream().limit(pageSize)
                .map(row -> new GuideConversation(row.taskId(), row.title(), row.status(), row.updatedAt()))
                .toList();
        return new GuideHistory(items, rows.size() > pageSize ? rows.get(pageSize - 1).id() : null);
    }
}
