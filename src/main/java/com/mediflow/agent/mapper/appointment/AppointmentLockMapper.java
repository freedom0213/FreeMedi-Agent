package com.mediflow.agent.mapper.appointment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Shared row locks. Services take patient locks before inventory and appointment locks. */
@Mapper
public interface AppointmentLockMapper {
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM sys_user WHERE id = #{patientId} FOR UPDATE")
    List<Long> lockPatient(@Param("patientId") long patientId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT status FROM department WHERE id = #{departmentId} FOR UPDATE")
    String lockDepartment(@Param("departmentId") long departmentId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT status FROM doctor WHERE id = #{doctorId} AND department_id = #{departmentId} FOR UPDATE")
    String lockDoctor(@Param("departmentId") long departmentId, @Param("doctorId") long doctorId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT status FROM doctor_schedule WHERE id = #{scheduleId} AND doctor_id = #{doctorId} FOR UPDATE")
    String lockSchedule(@Param("doctorId") long doctorId, @Param("scheduleId") long scheduleId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
        SELECT id FROM appointment
        WHERE appointment_no = #{appointmentNo} AND patient_id = #{patientId} AND status IN ('BOOKED', 'CHECKED_IN')
        FOR UPDATE
            """)
    List<Long> lockBookedForPatient(@Param("appointmentNo") String appointmentNo, @Param("patientId") long patientId);
}
