package com.mediflow.agent.mapper.appointment;

import com.mediflow.agent.security.account.AdminAccess.Scope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AppointmentQueryMapper {
    List<AppointmentRow> listForPatient(@Param("patientId") long patientId, @Param("limit") int limit);

    int countPatientPage(@Param("patientId") long patientId, @Param("now") Instant now,
                          @Param("today") LocalDate today, @Param("upcoming") boolean upcoming);

    List<AppointmentRow> listPatientPage(@Param("patientId") long patientId, @Param("now") Instant now,
                                         @Param("today") LocalDate today, @Param("upcoming") boolean upcoming,
                                         @Param("limit") int limit, @Param("offset") long offset);

    List<AppointmentRow> listInterrupted(@Param("patientId") long patientId, @Param("today") LocalDate today);

    long countPendingArrangements(@Param("scheduler") Scope scheduler);

    int countAdminAppointments(@Param("filters") RegisterFilters filters, @Param("access") RegisterScope access,
                               @Param("now") Instant now);

    List<AppointmentRow> listAdminAppointments(@Param("filters") RegisterFilters filters,
                                                @Param("access") RegisterScope access, @Param("now") Instant now,
                                                @Param("limit") int limit, @Param("offset") long offset);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
        SELECT a.department_id,ds.schedule_date FROM appointment a JOIN appointment_slot s ON s.id=a.slot_id
        JOIN doctor_schedule ds ON ds.id=s.schedule_id WHERE a.appointment_no=#{appointmentNo}
            """)
    List<CheckInTarget> checkInTargets(@Param("appointmentNo") String appointmentNo);

    List<AppointmentRow> findForPatient(@Param("appointmentNo") String appointmentNo, @Param("patientId") long patientId);

    List<OperationTarget> operationTargets(@Param("appointmentNo") String appointmentNo,
                                           @Param("access") RegisterScope access);

    int countOperationHistory(@Param("target") OperationTarget target);

    List<OperationRow> listOperationHistory(@Param("target") OperationTarget target, @Param("limit") int limit,
                                             @Param("offset") long offset);

    record RegisterScope(Scope scheduler, Scope triage, Scope reviewer, LocalDate today) {}

    enum RegisterView { TODAY, REVIEW }
    enum RegisterStatus { BOOKED, CHECKED_IN, VISITED, VISIT_UNCONFIRMED, CANCELLED, EXPIRED, RESCHEDULED, INTERRUPTED }

    record RegisterFilters(String appointmentNo, LocalDate from, LocalDate to, Long departmentId, Long doctorId,
                                  Long scheduleId, String channel, RegisterStatus status, boolean pendingArrangement,
                                  RegisterView visitDesk) {}
    record AppointmentRow(String appointmentNo, String status, String bookedVia, Instant bookedAt, long slotId,
                                  long scheduleId, LocalDate scheduleDate, String clinicPeriod, Instant startAt,
                                  Instant endAt, Instant checkedInAt, Instant cancelledAt, String scheduleStatus,
                                  String slotStatus, String departmentStatus, String doctorStatus, long departmentId,
                                  String departmentName, long doctorId, String doctorName, String doctorTitle,
                                  String originalNo, String replacementNo, String publicReason, Instant stoppedAt,
                                  String outcome, String arrangement, String patientDisplayName, Instant visitedAt,
                                  Long visitReviewId, String reviewedOutcome, Instant reviewedAt) {}
    record CheckInTarget(long departmentId, LocalDate scheduleDate) {}
    record OperationTarget(long id, Long originalId, String number, String originalNo, String replacementNo) {}
    record OperationRow(Instant time, String action, String operatorType, String operatorName,
                        String result, String code, String note) {}
}
