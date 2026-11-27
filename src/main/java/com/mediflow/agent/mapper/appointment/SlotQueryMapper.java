package com.mediflow.agent.mapper.appointment;

import com.mediflow.agent.service.appointment.query.SlotCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SlotQueryMapper {
    int count(@Param("criteria") SlotCriteria criteria, @Param("from") LocalDate from,
              @Param("until") LocalDate until, @Param("slotId") Long slotId, @Param("now") Instant now);

    List<SlotRow> find(@Param("criteria") SlotCriteria criteria, @Param("from") LocalDate from,
                       @Param("until") LocalDate until, @Param("slotId") Long slotId, @Param("now") Instant now,
                       @Param("limit") int limit, @Param("offset") long offset);

    record SlotRow(long slotId, long departmentId, String departmentCode, String departmentName,
                          long doctorId, String doctorName, String doctorTitle, LocalDate date,
                          String period, Timestamp startAt, Timestamp endAt) {}
}
