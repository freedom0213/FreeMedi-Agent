package com.mediflow.agent.mapper.admin.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.mediflow.agent.security.account.AdminAccess.Scope;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AdminCatalogQueryMapper {
    long countDepartments(@Param("q") String q, @Param("status") String status);

    List<DepartmentRow> departments(@Param("q") String q, @Param("status") String status,
                                   @Param("limit") int limit, @Param("offset") long offset);

    long countDoctors(@Param("q") String q, @Param("departmentId") Long departmentId, @Param("status") String status);

    List<DoctorRow> doctors(@Param("q") String q, @Param("departmentId") Long departmentId, @Param("status") String status,
                            @Param("limit") int limit, @Param("offset") long offset);

    List<CatalogOptionRow> departmentOptions(@Param("scope") Scope scope);

    @Select("SELECT id, name, status FROM doctor WHERE department_id = #{departmentId} ORDER BY name, id")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<CatalogOptionRow> doctorOptions(@Param("departmentId") long departmentId);

    long countSchedules(@Param("filters") ScheduleFilters filters, @Param("scope") Scope scope);

    List<ScheduleRow> schedules(@Param("filters") ScheduleFilters filters, @Param("scope") Scope scope,
                                @Param("limit") int limit, @Param("offset") long offset);

    Optional<ScheduleRow> schedule(@Param("id") long id);

    @Select("""
            SELECT slot_no, start_at, end_at, status FROM appointment_slot WHERE schedule_id = #{id} ORDER BY slot_no
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<SlotRow> slots(@Param("id") long id);

    @Select("SELECT d.department_id FROM doctor_schedule s JOIN doctor d ON d.id=s.doctor_id WHERE s.id=#{id}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Optional<Long> departmentForSchedule(@Param("id") long id);

    record ScheduleFilters(Long departmentId, Long doctorId, LocalDate date, LocalDate from, LocalDate to, String status) {}
    record CatalogOptionRow(long id, String name, String status) {}
    record DepartmentRow(long id, String code, String name, String description, String status) {}
    record DoctorRow(long id, long departmentId, String departmentName, String name,
                         String title, String specialty, String introduction, String status, String importCode) {}
    record ScheduleRow(long id, long departmentId, String departmentName, long doctorId, String doctorName,
                           LocalDate scheduleDate, String clinicPeriod, String status, int slots, int remaining, int booked,
                           boolean interrupted) {}
    record SlotRow(int number, Instant startAt, Instant endAt, String status) {}
}
