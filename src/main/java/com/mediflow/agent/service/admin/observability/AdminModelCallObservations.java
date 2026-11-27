package com.mediflow.agent.service.admin.observability;

import com.mediflow.agent.mapper.admin.observability.AdminModelCallMapper;
import com.mediflow.agent.mapper.admin.observability.AdminModelCallMapper.Attempt;
import com.mediflow.agent.mapper.admin.observability.AdminModelCallMapper.AttemptRow;
import com.mediflow.agent.mapper.admin.observability.AdminModelCallMapper.Day;
import com.mediflow.agent.mapper.admin.observability.AdminModelCallMapper.Filter;
import com.mediflow.agent.mapper.admin.observability.AdminModelCallMapper.Metrics;
import com.mediflow.agent.mapper.admin.observability.AdminModelCallMapper.Model;
import com.mediflow.agent.mapper.admin.observability.AdminModelCallMapper.Reason;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final AdminModelCallMapper mapper;

    public AdminModelCallObservations(AdminModelCallMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Overview overview(LocalDate from, LocalDate to, String model) {
        var filter = filters(from, to, model);
        var options = mapper.modelOptions(filter);
        var metrics = new HashMap<String, Metrics>();
        for (var row : mapper.metrics(filter)) {
            metrics.put(row.modelKey(), new Metrics(row.attempts(), row.succeeded(), row.failed(), row.retries(),
                    row.averageMs(), row.p95Ms()));
        }
        var days = new HashMap<LocalDate, Day>();
        for (var row : mapper.days(filter)) {
            days.put(row.date(), row);
        }
        var breakdown = options.stream().filter(option -> metrics.containsKey(option.key()))
                .map(option -> new ModelMetrics(option, metrics.get(option.key()))).toList();
        var trend = from.datesUntil(to.plusDays(1)).map(day -> days.getOrDefault(day, new Day(day, 0, 0))).toList();
        return new Overview(metrics.getOrDefault(null, ZERO), options, breakdown, trend, mapper.reasons(filter));
    }

    public AttemptPage attempts(LocalDate from, LocalDate to, String model, String outcome, String before) {
        var filter = filters(from, to, model);
        if (outcome != null && !List.of("SUCCEEDED", "FAILED").contains(outcome)) {
            throw new IllegalArgumentException("无效的调用结果筛选。");
        }
        var cursor = Cursor.parse(before);
        if (cursor != null && (cursor.time().isBefore(dayStart(from)) || !cursor.time().isBefore(dayStart(to.plusDays(1))))) {
            throw new IllegalArgumentException("分页位置不在当前记录日期范围内。");
        }
        var rows = mapper.attempts(filter, outcome, cursor == null ? null : cursor.time(),
                cursor == null ? null : cursor.id(), PAGE_SIZE + 1);
        var items = rows.stream().limit(PAGE_SIZE).map(AdminModelCallObservations::attempt).toList();
        String nextBefore = null;
        if (rows.size() > PAGE_SIZE) {
            var last = rows.get(PAGE_SIZE - 1);
            nextBefore = last.recordedAt().toEpochMilli() + ":" + last.id();
        }
        return new AttemptPage(items, nextBefore);
    }

    private static Attempt attempt(AttemptRow row) {
        return new Attempt(row.recordedAt(), row.taskId(), row.sequence(), row.modelKey(), row.attempt(),
                row.outcome(), row.reason(), row.durationMs());
    }

    private static Filter filters(LocalDate from, LocalDate to, String model) {
        if (from.getYear() < 1000 || to.getYear() > 9998 || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new IllegalArgumentException("请选择最多 31 天的有效记录日期范围。");
        }
        if (model != null && !model.matches(MODEL_KEY)) throw new IllegalArgumentException("无效的模型版本筛选。");
        return new Filter(dayStart(from), dayStart(to.plusDays(1)), model, "^" + VERSION + "$");
    }

    private static Instant dayStart(LocalDate day) {
        return day.atStartOfDay().toInstant(BEIJING_TIME);
    }

    public record ModelMetrics(Model model, Metrics metrics) {}
    public record Overview(Metrics totals, List<Model> options, List<ModelMetrics> models, List<Day> trend, List<Reason> reasons) {}
    public record AttemptPage(List<Attempt> items, String nextBefore) {}

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
