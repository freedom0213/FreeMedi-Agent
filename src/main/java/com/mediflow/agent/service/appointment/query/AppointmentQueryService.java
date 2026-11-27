package com.mediflow.agent.service.appointment.query;

import com.mediflow.agent.service.appointment.lifecycle.AppointmentWriteService;
import com.mediflow.agent.service.appointment.lifecycle.VisitCompletionService;
import com.mediflow.agent.service.appointment.policy.HospitalRules;
import com.mediflow.agent.security.account.AdminAccess.Scope;
import com.mediflow.agent.mapper.appointment.AppointmentLockMapper;
import com.mediflow.agent.mapper.appointment.AppointmentQueryMapper;
import com.mediflow.agent.mapper.appointment.AppointmentQueryMapper.AppointmentRow;
import com.mediflow.agent.mapper.appointment.AppointmentQueryMapper.RegisterFilters;
import com.mediflow.agent.mapper.appointment.AppointmentQueryMapper.RegisterScope;
import com.mediflow.agent.mapper.appointment.AppointmentQueryMapper.RegisterStatus;
import com.mediflow.agent.mapper.appointment.AppointmentQueryMapper.RegisterView;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Shared read model for patient-owned appointments and the administrator appointment register. */
@Service
@Profile("mysql")
public class AppointmentQueryService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public record AppointmentView(String appointmentNo, String status, String bookedVia, Instant bookedAt,
                                  long slotId, String appointmentDate, String clinicPeriod, String timeRange,
                                  long departmentId, String departmentName,
                                  long doctorId, String doctorName, String doctorTitle, Instant cancellationDeadline,
                                  Instant checkInOpensAt, Instant checkedInAt, Instant cancelledAt, Instant appointmentEndsAt,
                                  boolean canCancel, boolean canCheckIn,
                                  String originalAppointmentNo, String replacementAppointmentNo, Interruption interruption,
                                  VisitProgress visitProgress) {
    }

    public record Interruption(String reason, Instant at, String outcome, String arrangement) {}
    public record VisitProgress(Instant completedAt, VisitCompletionService.Outcome reviewedOutcome, Instant reviewedAt) {}
    public enum Status { BOOKED, CHECKED_IN, VISITED, VISIT_UNCONFIRMED, CANCELLED, EXPIRED, RESCHEDULED, INTERRUPTED }
    public enum Channel { PORTAL, AGENT }
    public enum VisitDesk { TODAY, REVIEW }

    public record Page<T>(List<T> items, int page, int totalCount, int totalPages) {}
    public record PatientOverview(AppointmentView next, Page<AppointmentView> upcoming, Page<AppointmentView> history,
                                  List<AppointmentView> interrupted) {}
    public record AdminAppointment(long scheduleId, AppointmentView appointment, String patientName, boolean checkInAllowed,
                                   boolean visitCompletionAllowed, boolean visitReviewAllowed, Long visitReviewId) {}
    public record AdminFilters(String appointmentNo, LocalDate from, LocalDate to,
                               Long departmentId, Long doctorId, Long scheduleId, Channel channel, Status status,
                               boolean pendingArrangement, VisitDesk visitDesk) {}
    public record AppointmentOperation(Instant time, String action, String operatorType, String operatorName,
                                       String result, String reason) {}
    public record AppointmentOperations(String appointmentNo, String originalAppointmentNo, String replacementAppointmentNo,
                                        Page<AppointmentOperation> events) {}

    public record RegisterAccess(Scope scheduler, Scope triage, Scope reviewer, LocalDate today) {
        boolean canTriage(long department, LocalDate date) {
            return triage.all() || (date.equals(today) && triage.allows(department));
        }
    }

    private AppointmentView mapAppointment(AppointmentRow row, Instant now, HospitalRules.Rules policy) {
        var date = row.scheduleDate();
        var deadline = policy.cancelBefore(date);
        var early = policy.checkInFrom(row.startAt());
        var end = row.endAt();
        var status = row.status();
        // Expiry is derived at read time so existing appointments age without a scheduled write.
        if ("BOOKED".equals(status) && !now.isBefore(end)) status = Status.EXPIRED.name();
        if ("CHECKED_IN".equals(status) && date.isBefore(SlotQueryService.hospitalDate(now))) status = Status.VISIT_UNCONFIRMED.name();
        boolean booked = "BOOKED".equals(status);
        var reviewed = row.reviewedOutcome();
        var visitedAt = row.visitedAt();
        var progress = visitedAt == null && reviewed == null ? null : new VisitProgress(visitedAt,
                reviewed == null ? null : VisitCompletionService.Outcome.valueOf(reviewed), row.reviewedAt());
        return new AppointmentView(
                row.appointmentNo(),
                status,
                row.bookedVia(),
                row.bookedAt(),
                row.slotId(),
                date.toString(),
                row.clinicPeriod(),
                range(row.startAt(), row.endAt()),
                row.departmentId(),
                row.departmentName(),
                row.doctorId(),
                row.doctorName(),
                row.doctorTitle(),
                deadline, early, row.checkedInAt(), row.cancelledAt(), end,
                booked && now.isBefore(deadline),
                booked && !now.isBefore(early) && now.isBefore(end)
                        && "ACTIVE".equals(row.departmentStatus()) && "ACTIVE".equals(row.doctorStatus())
                        && "OPEN".equals(row.scheduleStatus()) && "RESERVED".equals(row.slotStatus()),
                row.originalNo(), row.replacementNo(),
                row.publicReason() == null ? null : new Interruption(row.publicReason(),
                        row.stoppedAt(), row.outcome(), row.arrangement()),
                progress);
    }

    private final AppointmentQueryMapper mapper;
    private final AppointmentLockMapper locks;
    private final Clock clock;
    private final HospitalRules rules;

    public AppointmentQueryService(AppointmentQueryMapper mapper, AppointmentLockMapper locks, Clock clock,
                                   HospitalRules rules) {
        this.mapper = mapper;
        this.locks = locks;
        this.clock = clock;
        this.rules = rules;
    }

    /** Most recent booking first. The limit is clamped rather than rejected: it is paging, not input. */
    public List<AppointmentView> listForPatient(long patientId, int limit) {
        var now = clock.instant();
        var policy = rules.active();
        return mapper.listForPatient(patientId, Math.min(Math.max(limit, 1), MAX_LIMIT)).stream()
                .map(row -> mapAppointment(row, now, policy)).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PatientOverview patientOverview(long patientId, int upcomingPage, int historyPage) {
        var now = clock.instant();
        var policy = rules.active();
        var today = SlotQueryService.hospitalDate(now);
        var next = mapper.listPatientPage(patientId, now, today, true, 1, 0).stream().findFirst().orElse(null);
        Function<AppointmentRow, AppointmentView> view = row -> mapAppointment(row, now, policy);
        var upcoming = patientPage(patientId, now, today, true, upcomingPage, next == null ? 0 : 1, view);
        var history = patientPage(patientId, now, today, false, historyPage, 0, view);
        var interrupted = mapper.listInterrupted(patientId, today).stream().map(view).toList();
        return new PatientOverview(next == null ? null : view.apply(next), upcoming, history, interrupted);
    }

    private Page<AppointmentView> patientPage(long patientId, Instant now, LocalDate today, boolean upcoming,
                                              int requestedPage, int skip,
                                              Function<AppointmentRow, AppointmentView> view) {
        requirePage(requestedPage);
        int count = mapper.countPatientPage(patientId, now, today, upcoming) - skip;
        return page(requestedPage, count, skip,
                offset -> mapper.listPatientPage(patientId, now, today, upcoming, DEFAULT_LIMIT, offset), view);
    }

    public LocalDate operationalDate() { return SlotQueryService.hospitalDate(clock.instant()); }

    public long countPendingArrangements(Scope scheduler) {
        return mapper.countPendingArrangements(scheduler);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AdminAppointment> adminAppointments(AdminFilters filters, int requestedPage, RegisterAccess access) {
        if (filters.from() != null && filters.to() != null && filters.from().isAfter(filters.to())) {
            throw new IllegalArgumentException("就诊开始日期不能晚于结束日期。");
        }
        if (filters.appointmentNo() != null && filters.appointmentNo().length() > 64) {
            throw new IllegalArgumentException("预约编号最多 64 个字符。");
        }
        var now = clock.instant();
        var policy = rules.active();
        var query = new RegisterFilters(filters.appointmentNo(), filters.from(), filters.to(),
                filters.departmentId(), filters.doctorId(), filters.scheduleId(),
                filters.channel() == null ? null : filters.channel().name(),
                filters.status() == null ? null : RegisterStatus.valueOf(filters.status().name()),
                filters.pendingArrangement(),
                filters.visitDesk() == null ? null : RegisterView.valueOf(filters.visitDesk().name()));
        requirePage(requestedPage);
        var scope = registerScope(access);
        int count = mapper.countAdminAppointments(query, scope, now);
        return page(requestedPage, count, 0,
                offset -> mapper.listAdminAppointments(query, scope, now, DEFAULT_LIMIT, offset), row -> {
            var a = mapAppointment(row, now, policy);
            var date = row.scheduleDate();
            boolean canTriage = access.canTriage(a.departmentId(), date);
            boolean canReview = access.reviewer().allows(a.departmentId())
                    && (Status.VISITED.name().equals(a.status()) || Status.VISIT_UNCONFIRMED.name().equals(a.status()));
            return new AdminAppointment(row.scheduleId(), a, canTriage || canReview ? row.patientDisplayName() : null,
                    canTriage && a.canCheckIn(), canTriage && date.equals(access.today()) && Status.CHECKED_IN.name().equals(a.status()),
                    canReview, canReview ? row.visitReviewId() : null);
        });
    }

    public boolean mayCheckIn(String appointmentNo, RegisterAccess access) {
        return mapper.checkInTargets(appointmentNo).stream()
                .anyMatch(row -> access.canTriage(row.departmentId(), row.scheduleDate()));
    }

    private static RegisterScope registerScope(RegisterAccess access) {
        return new RegisterScope(access.scheduler(), access.triage(), access.reviewer(), access.today());
    }

    private static void requirePage(int requestedPage) {
        if (requestedPage < 1) throw new IllegalArgumentException("页码必须大于 0。");
    }

    private static <R, T> Page<T> page(int requestedPage, int count, int skip,
                                      LongFunction<List<R>> load, Function<R, T> project) {
        int totalPages = (int) ((count + (long) DEFAULT_LIMIT - 1) / DEFAULT_LIMIT);
        int page = Math.min(requestedPage, Math.max(1, totalPages));
        if (count == 0) return new Page<>(List.of(), page, 0, 0);
        long offset = (long) (page - 1) * DEFAULT_LIMIT + skip;
        return new Page<>(load.apply(offset).stream().map(project).toList(), page, count, totalPages);
    }

    public Optional<AppointmentView> findForPatient(String appointmentNo, long patientId) {
        if (appointmentNo == null || appointmentNo.isBlank() || patientId <= 0) {
            return Optional.empty();
        }
        var now = clock.instant();
        var policy = rules.active();
        return mapper.findForPatient(appointmentNo.trim(), patientId).stream().findFirst().map(row -> mapAppointment(row, now, policy));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<AppointmentOperations> appointmentOperations(String appointmentNo, int requestedPage, RegisterAccess access) {
        requirePage(requestedPage);
        var appointment = mapper.operationTargets(appointmentNo, registerScope(access)).stream().findFirst();
        if (appointment.isEmpty()) return Optional.empty();
        var target = appointment.get();
        int count = mapper.countOperationHistory(target);
        var events = page(requestedPage, count, 0, offset -> mapper.listOperationHistory(target, DEFAULT_LIMIT, offset),
                row -> new AppointmentOperation(row.time(), row.action(), row.operatorType(),
                        row.operatorName(), row.result(), row.note() == null ? operationReason(row.action(), row.code()) : row.note()));
        return Optional.of(new AppointmentOperations(target.number(), target.originalNo(), target.replacementNo(), events));
    }

    private static String operationReason(String type, String code) {
        if (code == null) return null;
        return "APPOINTMENT_RESCHEDULE".equals(type) ? AppointmentWriteService.rescheduleRejection(code).message() : code;
    }

    /** Locks the patient before the appointment because creating the consultation also references the patient. */
    public boolean lockBookedForPatient(String appointmentNo, long patientId) {
        locks.lockPatient(patientId);
        return !locks.lockBookedForPatient(appointmentNo, patientId).isEmpty();
    }

    private static String range(Instant start, Instant end) {
        return SlotQueryService.formatTime(Timestamp.from(start)) + "-" + SlotQueryService.formatTime(Timestamp.from(end));
    }
}
