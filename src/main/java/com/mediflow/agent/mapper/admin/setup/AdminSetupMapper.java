package com.mediflow.agent.mapper.admin.setup;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Mapper
public interface AdminSetupMapper {
    @Select("SELECT hospital_name, contact, activated_at FROM hospital_settings WHERE id=1")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    StoredProfile readProfile();

    @Update("""
            UPDATE hospital_settings SET hospital_name=#{name}, contact=#{contact}, schedule_days=#{scheduleDays}, patient_days=#{patientDays},
                cancellation_cutoff=#{cancellationCutoff}, check_in_lead=#{checkInLead}, activated_at=#{activatedAt}, activated_by=#{operatorId} WHERE id=1
            """)
    void activate(@Param("name") String name, @Param("contact") String contact, @Param("scheduleDays") int scheduleDays,
                  @Param("patientDays") int patientDays, @Param("cancellationCutoff") LocalTime cancellationCutoff,
                  @Param("checkInLead") int checkInLead, @Param("activatedAt") Instant activatedAt,
                  @Param("operatorId") long operatorId);

    Readiness readiness(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("now") Instant now);

    record StoredProfile(String name, String contact, Instant activatedAt) {}
    record Readiness(long departments, long doctors, long schedules) {}
}
