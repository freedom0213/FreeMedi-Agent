package com.mediflow.agent.mapper.appointment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AppointmentOperationsMapper {
    List<ScheduleRow> scheduleCounts(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                     @Param("departmentId") Long departmentId, @Param("now") Instant now,
                                     @Param("today") LocalDate today, @Param("patientEnd") LocalDate patientEnd);

    record ScheduleRow(LocalDate date, String status, long departmentId, String departmentName,
                              long doctorId, String doctorName, long booked, long portal, long agent,
                              long remaining, long occupied) {}
}
