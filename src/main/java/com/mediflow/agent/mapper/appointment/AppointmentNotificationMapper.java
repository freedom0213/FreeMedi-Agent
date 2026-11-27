package com.mediflow.agent.mapper.appointment;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AppointmentNotificationMapper {
    Counts count(@Param("patientId") long patientId);

    List<NoticeRow> list(@Param("patientId") long patientId, @Param("limit") int limit,
                         @Param("offset") long offset);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
        SELECT a.id FROM appointment a
        JOIN interrupted_appointment i ON i.appointment_id = a.id
        WHERE a.patient_id = #{patientId} AND a.appointment_no = #{appointmentNo} AND (#{kind} = 'STOPPED' OR i.arranged_at IS NOT NULL)
            """)
    List<Long> findAppointmentIds(@Param("patientId") long patientId, @Param("appointmentNo") String appointmentNo,
                                  @Param("kind") String kind);

    @Insert("""
        INSERT INTO appointment_notice_receipt (appointment_id, notice_kind, read_at)
        VALUES (#{appointmentId}, #{kind}, UTC_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE read_at = appointment_notice_receipt.read_at
            """)
    void markRead(@Param("appointmentId") long appointmentId, @Param("kind") String kind);

    record Counts(long total, long unread) {}
    record NoticeRow(String appointmentNo, String kind, Instant occurredAt, LocalDate appointmentDate,
                     String departmentName, String doctorName, boolean unread) {}
}
