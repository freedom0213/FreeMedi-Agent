package com.mediflow.agent.mapper.admin.observability;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AdminModelCallMapper {
    List<Model> modelOptions(@Param("filter") Filter filter);
    List<MetricRow> metrics(@Param("filter") Filter filter);
    List<Day> days(@Param("filter") Filter filter);
    List<Reason> reasons(@Param("filter") Filter filter);

    List<AttemptRow> attempts(@Param("filter") Filter filter, @Param("outcome") String outcome,
                              @Param("beforeTime") Instant beforeTime, @Param("beforeId") Long beforeId,
                              @Param("limit") int limit);

    record Metrics(long attempts, long succeeded, long failed, long retries, Double averageMs, Long p95Ms) {}
    record Model(String key, String mode, String version, String label, String modelName) {}
    record Day(LocalDate date, long succeeded, long failed) {}
    record Reason(String code, long attempts) {}
    record Attempt(Instant recordedAt, String taskId, long sequence, String modelKey, Integer attempt, String outcome,
                          String reason, Long durationMs) {}
    record Filter(Instant start, Instant end, String model, String versionPattern) {}
    record MetricRow(String modelKey, long attempts, long succeeded, long failed, long retries, Double averageMs, Long p95Ms) {}
    record AttemptRow(long id, Instant recordedAt, String taskId, long sequence, String modelKey, Integer attempt,
                      String outcome, String reason, Long durationMs) {}
}
