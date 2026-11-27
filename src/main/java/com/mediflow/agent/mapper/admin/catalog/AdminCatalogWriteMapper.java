package com.mediflow.agent.mapper.admin.catalog;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AdminCatalogWriteMapper {
    @Insert("INSERT INTO department (code, name, description, status) VALUES (#{code}, #{name}, #{description,jdbcType=VARCHAR}, 'ACTIVE')")
    void insertDepartment(@Param("code") String code, @Param("name") String name,
                          @Param("description") String description);

    @Update("UPDATE department SET name = #{name}, description = #{description,jdbcType=VARCHAR} WHERE id = #{id}")
    void updateDepartment(@Param("id") long id, @Param("name") String name,
                          @Param("description") String description);

    @Update("UPDATE department SET status = #{status} WHERE id = #{id}")
    void setDepartmentStatus(@Param("id") long id, @Param("status") String status);

    @Insert("""
            INSERT INTO doctor (department_id, code, name, title, specialty, introduction, status)
            VALUES (#{departmentId}, #{code,jdbcType=VARCHAR}, #{name}, #{title,jdbcType=VARCHAR}, #{specialty,jdbcType=VARCHAR}, #{introduction,jdbcType=VARCHAR}, 'ACTIVE')
            """)
    void insertDoctor(@Param("departmentId") long departmentId, @Param("code") String code,
                      @Param("name") String name, @Param("title") String title, @Param("specialty") String specialty,
                      @Param("introduction") String introduction);

    @Update("""
            UPDATE doctor SET name = #{name}, title = #{title,jdbcType=VARCHAR},
                specialty = #{specialty,jdbcType=VARCHAR}, introduction = #{introduction,jdbcType=VARCHAR} WHERE id = #{id}
            """)
    void updateDoctor(@Param("id") long id, @Param("name") String name, @Param("title") String title,
                      @Param("specialty") String specialty, @Param("introduction") String introduction);

    @Update("UPDATE doctor SET status = #{status} WHERE id = #{id}")
    void setDoctorStatus(@Param("id") long id, @Param("status") String status);

    @Select("SELECT department_id FROM doctor WHERE id = #{doctorId}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Optional<Long> doctorDepartment(@Param("doctorId") long doctorId);

    @Select("""
            SELECT id FROM doctor_schedule WHERE doctor_id = #{doctorId} AND schedule_date = #{date} AND clinic_period = #{period} FOR UPDATE
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockSchedulesForPeriod(@Param("doctorId") long doctorId, @Param("date") LocalDate date,
                                      @Param("period") String period);

    List<Long> lockOverlappingSlots(@Param("doctorId") long doctorId, @Param("date") LocalDate date,
                                    @Param("start") Instant start, @Param("end") Instant end);

    @Insert("INSERT INTO doctor_schedule (doctor_id, schedule_date, clinic_period, status) VALUES (#{doctorId}, #{date}, #{period}, 'OPEN')")
    void insertSchedule(@Param("doctorId") long doctorId, @Param("date") LocalDate date,
                        @Param("period") String period);

    @Select("""
            SELECT id FROM doctor_schedule WHERE doctor_id = #{doctorId} AND schedule_date = #{date} AND clinic_period = #{period}
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long scheduleId(@Param("doctorId") long doctorId, @Param("date") LocalDate date,
                    @Param("period") String period);

    @Insert("""
            INSERT INTO appointment_slot (schedule_id, slot_no, start_at, end_at, status)
            VALUES (#{scheduleId}, #{number}, #{start}, #{end}, 'AVAILABLE')
            """)
    void insertSlot(@Param("scheduleId") long scheduleId, @Param("number") int number,
                    @Param("start") Instant start, @Param("end") Instant end);

    @Select("""
            SELECT d.department_id, sc.doctor_id FROM doctor_schedule sc
            JOIN doctor d ON d.id = sc.doctor_id WHERE sc.id = #{id}
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Optional<SchedulePath> schedulePath(@Param("id") long id);

    List<Long> lockActiveScheduleBookings(@Param("id") long id);

    @Select("""
            SELECT id FROM appointment_slot WHERE schedule_id = #{id} AND status = 'RESERVED' FOR UPDATE
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockReservedSlots(@Param("id") long id);

    @Update("UPDATE doctor_schedule SET status = 'CLOSED' WHERE id = #{id}")
    void closeSchedule(@Param("id") long id);

    @Update("""
            UPDATE appointment_slot SET status = 'CLOSED', version = version + 1
            WHERE schedule_id = #{id} AND status = 'AVAILABLE'
            """)
    void closeAvailableSlots(@Param("id") long id);

    List<Long> lockFutureDepartmentBookings(@Param("departmentId") long departmentId);

    List<Long> lockFutureDoctorBookings(@Param("doctorId") long doctorId);

    record SchedulePath(long departmentId, long doctorId) {}
}
