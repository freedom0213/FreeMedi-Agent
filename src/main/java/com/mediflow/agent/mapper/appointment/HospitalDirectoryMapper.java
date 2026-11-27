package com.mediflow.agent.mapper.appointment;

import com.mediflow.agent.agent.booking.DoctorDirectory.Clinician;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HospitalDirectoryMapper {
    // Read the current catalog even when a JDBC write preceded a lookup in the same transaction.
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
                SELECT id FROM department
                WHERE status = 'ACTIVE' AND (name = #{nameOrCode} OR code = #{nameOrCode})
                ORDER BY id
                LIMIT 1
                """)
    List<Long> activeDepartmentIds(@Param("nameOrCode") String nameOrCode);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
                SELECT id, code, name, description FROM department
                WHERE status = 'ACTIVE'
                ORDER BY id
                """)
    List<DepartmentRow> activeDepartments();

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
                SELECT id FROM department
                WHERE status = 'ACTIVE' AND INSTR(#{question}, name) > 0
                ORDER BY id
                LIMIT 2
                """)
    List<Long> mentionedDepartmentIds(@Param("question") String question);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
                SELECT id, department_id, name, title, specialty, introduction FROM doctor
                WHERE department_id = #{departmentId} AND status = 'ACTIVE'
                ORDER BY id
                """)
    List<DoctorRow> activeDoctors(@Param("departmentId") long departmentId);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
                SELECT dr.id, dr.department_id
                FROM doctor dr JOIN department d ON d.id = dr.department_id
                WHERE dr.status = 'ACTIVE' AND d.status = 'ACTIVE' AND INSTR(#{question}, dr.name) > 0
                ORDER BY dr.id
                LIMIT 2
                """)
    List<Clinician> mentionedClinicians(@Param("question") String question);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
                SELECT COUNT(*) FROM doctor dr JOIN department d ON d.id = dr.department_id
                WHERE dr.id = #{doctorId} AND d.id = #{departmentId} AND dr.status = 'ACTIVE' AND d.status = 'ACTIVE'
                """)
    int countActiveDoctorInDepartment(@Param("doctorId") long doctorId, @Param("departmentId") long departmentId);

    record DepartmentRow(long id, String code, String name, String description) {}
    record DoctorRow(long id, long departmentId, String name, String title, String specialty, String introduction) {}
}
