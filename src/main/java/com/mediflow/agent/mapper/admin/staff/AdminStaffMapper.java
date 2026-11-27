package com.mediflow.agent.mapper.admin.staff;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AdminStaffMapper {
    @Select("""
            SELECT id, username, display_name, status, temporary_password_expires_at
            FROM sys_user WHERE role='ADMIN' ORDER BY id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<StaffRow> list();

    @Insert("INSERT INTO sys_user(username,password_hash,display_name,role,status) VALUES (#{username}, #{passwordHash}, #{displayName}, 'ADMIN', 'ACTIVE')")
    void insert(@Param("username") String username, @Param("displayName") String displayName,
                @Param("passwordHash") String passwordHash);

    @Select("SELECT id FROM sys_user WHERE username = #{username}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long accountId(@Param("username") String username);

    @Select("SELECT status FROM sys_user WHERE id = #{id} AND role='ADMIN'")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Optional<String> findStatus(@Param("id") long id);

    @Select("SELECT status FROM sys_user WHERE id = #{id} AND role='ADMIN' FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Optional<String> lockStatus(@Param("id") long id);

    @Update("UPDATE sys_user SET status=#{target}, credential_version=credential_version+1 WHERE id=#{id}")
    void updateStatus(@Param("id") long id, @Param("target") String target);

    List<SecurityEventRow> securityEvents(@Param("id") long id, @Param("before") Long before);

    @Select("SELECT EXISTS(SELECT 1 FROM department WHERE id = #{departmentId})")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    boolean departmentExists(@Param("departmentId") long departmentId);

    @Delete("DELETE FROM admin_grant WHERE admin_id=#{id}")
    void deleteGrants(@Param("id") long id);

    @Insert("INSERT INTO admin_grant(admin_id,role_code,department_id) VALUES (#{id}, #{role}, #{departmentId,jdbcType=BIGINT})")
    void insertGrant(@Param("id") long id, @Param("role") String role, @Param("departmentId") Long departmentId);

    @Insert("INSERT INTO admin_grant_event(operator_id,staff_id,grants_before,grants_after) VALUES (#{operatorId}, #{id}, #{before}, #{after})")
    void recordGrantChange(@Param("operatorId") long operatorId, @Param("id") long id,
                           @Param("before") String before, @Param("after") String after);

    record StaffRow(long id, String username, String displayName, String status,
                           Instant temporaryPasswordExpiresAt) {}
    record SecurityEventRow(long id, String operator, String action, Instant occurredAt) {}
}
