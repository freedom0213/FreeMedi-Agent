package com.mediflow.agent.api;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;

@Service
@Profile("mysql")
public class AdminModelCallObservations {
    private static final ZoneOffset BEIJING_TIME = ZoneOffset.ofHours(8);
    private static final int MAX_DAYS = 31;
    private static final int PAGE_SIZE = 50;
    private static final String VERSION = "[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}/v[1-9][0-9]{0,9}";
    private static final String MODEL_KEY = "(demo|external|unspecified):(environment|built-in|unrecorded|" + VERSION + ")";
    private static final Metrics ZERO = new Metrics(0, 0, 0, 0, null, null);

    private static final String EVENTS = """
            WITH decoded AS (
                SELECT e.id, e.task_id, e.event_no, e.created_at, e.reason_code,
                       e.metadata ->> '$.outcome' AS outcome,
                       e.metadata ->> '$.summary.adapter' AS adapter,
                       e.metadata ->> '$.summary.modelVersion' AS logged_version,
                       CASE WHEN JSON_TYPE(e.metadata -> '$.durationMs') = 'INTEGER'
                            THEN CAST(e.metadata ->> '$.durationMs' AS SIGNED) END AS logged_duration,
                       CASE WHEN JSON_TYPE(e.metadata -> '$.attempt') = 'INTEGER'
                            THEN CAST(e.metadata ->> '$.attempt' AS SIGNED) END AS logged_attempt
                FROM agent_state_event e
                WHERE e.event_type = 'MODEL_DECISION' AND e.created_at >= :start AND e.created_at < :end
            ), identified AS (
                SELECT id, task_id, event_no, created_at, outcome,
                       CASE WHEN REGEXP_LIKE(reason_code, '^[A-Z][A-Z0-9_]{0,63}$', 'c') THEN reason_code END AS reason,
                       CASE WHEN logged_attempt BETWEEN 1 AND 2147483647 THEN logged_attempt END AS attempt,
                       CASE WHEN logged_duration >= 0 THEN logged_duration END AS duration_ms,
                       CASE WHEN logged_version IN ('environment', 'built-in')
                                 OR REGEXP_LIKE(logged_version, :versionPattern, 'c') THEN logged_version
                            ELSE 'unrecorded' END AS version,
                       CASE WHEN adapter = 'DemoDecisionModel' THEN 'demo'
                            WHEN adapter = 'DeepSeekDecisionModel' OR REGEXP_LIKE(logged_version, :versionPattern, 'c')
                                 THEN 'external'
                            ELSE 'unspecified' END AS mode
                FROM decoded WHERE outcome IN ('SUCCEEDED', 'FAILED')
            ), model_events AS (
                SELECT identified.*, CONCAT(mode, ':', version) AS model_key FROM identified
            ), filtered AS (
                SELECT * FROM model_events WHERE :model IS NULL OR model_key = :model
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminModelCallObservations(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Overview overview(LocalDate from, LocalDate to, String model) {
        var params = filters(from, to, model);
        var options = jdbc.query(EVENTS + """
                SELECT options.model_key, options.mode, options.version, r.label, r.model_name
                FROM (SELECT DISTINCT model_key, mode, version FROM model_events) options
                LEFT JOIN agent_model_revision r ON options.version = CONCAT(r.profile_id, '/v', r.revision)
                ORDER BY options.mode, r.label, r.revision, options.version
                """, params, (rs, row) -> new Model(rs.getString("model_key"), rs.getString("mode"),
                rs.getString("version"), rs.getString("label"), rs.getString("model_name")));

        var metrics = jdbc.query(EVENTS + """
                , scopes AS (
                    SELECT model_key, outcome, attempt, duration_ms FROM filtered
                    UNION ALL
                    SELECT NULL, outcome, attempt, duration_ms FROM filtered
                ), ranked AS (
                    SELECT scopes.*,
                           ROW_NUMBER() OVER (PARTITION BY model_key ORDER BY duration_ms IS NULL, duration_ms) AS latency_rank,
                           COUNT(duration_ms) OVER (PARTITION BY model_key) AS latency_samples
                    FROM scopes
                )
                SELECT model_key, COUNT(*) AS attempts, SUM(outcome = 'SUCCEEDED') AS succeeded,
                       SUM(outcome = 'FAILED') AS failed, SUM(CASE WHEN attempt > 1 THEN 1 ELSE 0 END) AS retries,
                       AVG(duration_ms) AS average_ms,
                       MIN(CASE WHEN latency_rank >= CEIL(latency_samples * 0.95) THEN duration_ms END) AS p95_ms
                FROM ranked GROUP BY model_key
                """, params, rs -> {
            var result = new HashMap<String, Metrics>();
            while (rs.next()) result.put(rs.getString("model_key"), readMetrics(rs));
            return result;
        });

        var days = jdbc.query(EVENTS + """
                SELECT DATE(DATE_ADD(created_at, INTERVAL 8 HOUR)) AS observed_date,
                       SUM(outcome = 'SUCCEEDED') AS succeeded, SUM(outcome = 'FAILED') AS failed
                FROM filtered GROUP BY observed_date
                """, params, rs -> {
            var result = new HashMap<LocalDate, Day>();
            while (rs.next()) {
                var date = rs.getObject("observed_date", LocalDate.class);
                result.put(date, new Day(date, rs.getLong("succeeded"), rs.getLong("failed")));
            }
            return result;
        });
        var reasons = jdbc.query(EVENTS + """
                SELECT reason, COUNT(*) AS attempts FROM filtered WHERE outcome = 'FAILED'
                GROUP BY reason ORDER BY attempts DESC, reason
                """, params, (rs, row) -> new Reason(rs.getString("reason"), rs.getLong("attempts")));
        var breakdown = options.stream().filter(option -> metrics.containsKey(option.key()))
                .map(option -> new ModelMetrics(option, metrics.get(option.key()))).toList();
        var trend = from.datesUntil(to.plusDays(1)).map(day -> days.getOrDefault(day, new Day(day, 0, 0))).toList();
        return new Overview(metrics.getOrDefault(null, ZERO), options, breakdown, trend, reasons);
    }

    public AttemptPage attempts(LocalDate from, LocalDate to, String model, String outcome, String before) {
        var params = filters(from, to, model);
        if (outcome != null && !List.of("SUCCEEDED", "FAILED").contains(outcome)) {
            throw new IllegalArgumentException("无效的调用结果筛选。");
        }
        var cursor = Cursor.parse(before);
        if (cursor != null && (cursor.time().isBefore(dayStart(from)) || !cursor.time().isBefore(dayStart(to.plusDays(1))))) {
            throw new IllegalArgumentException("分页位置不在当前记录日期范围内。");
        }
        params.addValue("outcome", outcome)
                .addValue("beforeTime", cursor == null ? null : Timestamp.from(cursor.time()))
                .addValue("beforeId", cursor == null ? null : cursor.id())
                .addValue("limit", PAGE_SIZE + 1);
        var rows = jdbc.query(EVENTS + """
                SELECT f.id, f.event_no, f.created_at, f.model_key, f.attempt, f.outcome, f.reason, f.duration_ms, t.task_no
                FROM filtered f JOIN agent_task t ON t.id = f.task_id
                WHERE (:outcome IS NULL OR f.outcome = :outcome)
                  AND (:beforeTime IS NULL OR (f.created_at, f.id) < (:beforeTime, :beforeId))
                ORDER BY f.created_at DESC, f.id DESC LIMIT :limit
                """, params, (rs, row) -> new AttemptRow(rs.getLong("id"),
                new Attempt(rs.getTimestamp("created_at").toInstant(), rs.getString("task_no"), rs.getLong("event_no"), rs.getString("model_key"),
                        rs.getObject("attempt", Integer.class), rs.getString("outcome"), rs.getString("reason"),
                        rs.getObject("duration_ms", Long.class))));
        var items = rows.stream().limit(PAGE_SIZE).map(AttemptRow::attempt).toList();
        String nextBefore = null;
        if (rows.size() > PAGE_SIZE) {
            var last = rows.get(PAGE_SIZE - 1);
            nextBefore = last.attempt().recordedAt().toEpochMilli() + ":" + last.id();
        }
        return new AttemptPage(items, nextBefore);
    }

    private static MapSqlParameterSource filters(LocalDate from, LocalDate to, String model) {
        if (from.getYear() < 1000 || to.getYear() > 9998 || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new IllegalArgumentException("请选择最多 31 天的有效记录日期范围。");
        }
        if (model != null && !model.matches(MODEL_KEY)) throw new IllegalArgumentException("无效的模型版本筛选。");
        return new MapSqlParameterSource()
                .addValue("start", Timestamp.from(dayStart(from)))
                .addValue("end", Timestamp.from(dayStart(to.plusDays(1))))
                .addValue("versionPattern", "^" + VERSION + "$")
                .addValue("model", model);
    }

    private static Instant dayStart(LocalDate day) {
        return day.atStartOfDay().toInstant(BEIJING_TIME);
    }

    private static Metrics readMetrics(ResultSet rs) throws SQLException {
        return new Metrics(rs.getLong("attempts"), rs.getLong("succeeded"), rs.getLong("failed"), rs.getLong("retries"),
                rs.getObject("average_ms", Double.class), rs.getObject("p95_ms", Long.class));
    }

    public record Metrics(long attempts, long succeeded, long failed, long retries, Double averageMs, Long p95Ms) {}
    public record Model(String key, String mode, String version, String label, String modelName) {}
    public record ModelMetrics(Model model, Metrics metrics) {}
    public record Day(LocalDate date, long succeeded, long failed) {}
    public record Reason(String code, long attempts) {}
    public record Overview(Metrics totals, List<Model> options, List<ModelMetrics> models, List<Day> trend, List<Reason> reasons) {}
    public record Attempt(Instant recordedAt, String taskId, long sequence, String modelKey, Integer attempt, String outcome,
                          String reason, Long durationMs) {}
    public record AttemptPage(List<Attempt> items, String nextBefore) {}
    private record AttemptRow(long id, Attempt attempt) {}

    private record Cursor(Instant time, long id) {
        private static Cursor parse(String value) {
            if (value == null) return null;
            if (value.matches("-?[0-9]{1,15}:[1-9][0-9]{0,18}")) {
                var parts = value.split(":", 2);
                try {
                    return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
                } catch (NumberFormatException ignored) {
                    // The cursor is an untrusted query parameter.
                }
            }
            throw new IllegalArgumentException("无效的调用记录分页位置。");
        }
    }
}
