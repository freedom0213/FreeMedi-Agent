package com.mediflow.agent.config;

import com.mediflow.agent.agent.AgentCallPolicy;
import com.mediflow.agent.agent.AgentRequestSettings;
import com.mediflow.agent.agent.ReadToolExecutor;
import com.mediflow.agent.agent.ReadToolSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
@Profile("mysql")
@DependsOnDatabaseInitialization
public class AgentSettings {
    private static final String SELECT_MODELS = """
            SELECT id, profile_id, revision, label, base_url, model_name, probe_code, probe_ms, tested_at
            FROM agent_model_revision
            """;
    private static final String SELECT_TOOLS = """
            SELECT t.tool_name, t.enabled, t.timeout_ms, t.revision, t.updated_at, u.display_name
            FROM agent_tool_settings t JOIN sys_user u ON t.updated_by = u.id
            """;
    private static final RowMapper<ModelRevision> MODEL = (rs, row) -> new ModelRevision(
            rs.getLong("id"), rs.getString("profile_id"), rs.getInt("revision"), rs.getString("label"),
            rs.getString("base_url"), rs.getString("model_name"), rs.getString("probe_code"),
            rs.getObject("probe_ms", Long.class), instant(rs.getTimestamp("tested_at")));
    private static final RowMapper<ToolRow> TOOL = (rs, row) -> new ToolRow(
            rs.getString("tool_name"), rs.getBoolean("enabled"), rs.getObject("timeout_ms", Long.class),
            rs.getLong("revision"), instant(rs.getTimestamp("updated_at")), rs.getString("display_name"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AgentModelClients clients;
    private final AgentModelSecrets secrets;
    private final ThreadPoolTaskExecutor executor;
    private final AgentCallPolicy defaults;
    private final List<ReadToolSpec> catalog;
    private final Semaphore tests = new Semaphore(1);
    private volatile State state;

    AgentSettings(JdbcTemplate jdbc, PlatformTransactionManager transactions, AgentModelClients clients,
                  AgentModelSecrets secrets, @Qualifier("agentCallExecutor") ThreadPoolTaskExecutor executor, AgentCallPolicy defaults,
                  @Qualifier("readToolExecutor") ReadToolExecutor tools) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
        this.clients = clients;
        this.secrets = secrets;
        this.executor = executor;
        this.defaults = defaults;
        this.catalog = tools.tools();
        this.state = loadState(null);
    }

    public AgentRequestSettings snapshot() { return state.settings(); }

    public synchronized ModelView modelView() {
        State current = state;
        var model = current.settings().model();
        var active = current.active();
        return new ModelView(clients.demo() && active == null ? "DEMO" : active == null ? "DEEPSEEK" : "COMPATIBLE",
                current.settings().calls().settings(),
                model instanceof DeepSeekDecisionModel adapter ? adapter.providerSettings() : null,
                active, current.activatedAt(), active == null && !clients.demo() ? safeInitialUrl() : null, current.revision(),
                secrets.available(), jdbc.query(SELECT_MODELS + " ORDER BY id DESC", MODEL));
    }

    private String safeInitialUrl() {
        try {
            return clients.managedUrl(clients.initialUrl());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public List<ToolView> toolView() {
        State current = state;
        long defaultMs = current.settings().calls().settings().toolTimeoutMs();
        return catalog.stream().map(spec -> {
            var tool = current.tools().get(spec.name());
            return new ToolView(spec.name(), spec.description(), spec.parameters(), tool == null || tool.enabled(),
                    tool == null ? null : tool.timeoutMs(), defaultMs, tool == null ? 0 : tool.revision(),
                    tool == null ? null : tool.updatedAt(), tool == null ? null : tool.updatedBy());
        }).toList();
    }

    public synchronized void saveModel(String profileId, long expectedId, String label, String url,
                                       String modelName, String apiKey, long admin) {
        String cleanLabel = label.strip();
        String cleanModel = modelName.strip();
        if (cleanLabel.isEmpty() || cleanLabel.length() > 100 || cleanModel.isEmpty() || cleanModel.length() > 150
                || cleanLabel.chars().anyMatch(Character::isISOControl) || cleanModel.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("请填写有效的配置名称和模型名称。");
        }
        String approvedUrl = clients.managedUrl(url);
        if (approvedUrl.length() > 500) throw new IllegalArgumentException("接口地址过长。");
        String profile = profileId == null ? UUID.randomUUID().toString() : profileId;
        transactions.executeWithoutResult(transaction -> {
            ModelRevision latest = jdbc.query(SELECT_MODELS + " WHERE profile_id = ? ORDER BY revision DESC LIMIT 1",
                    MODEL, profile).stream().findFirst().orElse(null);
            if (profileId != null && latest == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if ((latest == null ? 0 : latest.id()) != expectedId) throw conflict();
            String key = apiKey == null ? "" : apiKey.strip();
            byte[] encrypted;
            if (key.isEmpty() && latest != null && approvedUrl.equals(latest.baseUrl())) {
                encrypted = storedKey(latest.id());
            } else {
                if (key.isEmpty() || key.length() > 512 || key.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("请填写有效的 API Key。新增配置或更换接口地址时需要重新填写。");
                }
                encrypted = secrets.encrypt(key);
            }
            int revision = latest == null ? 1 : latest.revision() + 1;
            jdbc.update("""
                    INSERT INTO agent_model_revision (profile_id, revision, label, base_url, model_name, encrypted_key)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, profile, revision, cleanLabel, approvedUrl, cleanModel, encrypted);
            audit(admin, "MODEL_SAVE", profile, "revision=" + revision);
        });
    }

    public void testModel(long id, long admin) {
        if (!tests.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "已有模型正在测试，请稍后重试。");
        }
        try {
            var model = model(id);
            var probe = clients.probe(clients.managedUrl(model.baseUrl()), model.modelName(), secrets.decrypt(storedKey(id)));
            recordProbe(id, admin, probe);
        } finally {
            tests.release();
        }
    }

    private synchronized void recordProbe(long id, long admin, AgentModelClients.Probe probe) {
        transactions.executeWithoutResult(transaction -> {
            jdbc.update("UPDATE agent_model_revision SET probe_code = ?, probe_ms = ?, tested_at = ? WHERE id = ?",
                    probe.code(), probe.durationMs(), Timestamp.from(probe.testedAt()), id);
            audit(admin, "MODEL_TEST", Long.toString(id), probe.code());
        });
    }

    public synchronized void activate(Long id, long expectedRevision, long admin) {
        State before = state;
        if (before.revision() != expectedRevision) throw conflict();
        Long previous = before.active() == null ? null : before.active().id();
        if (Objects.equals(previous, id)) return;
        state = transactions.execute(transaction -> {
            if (id != null && !"PASSED".equals(model(id).probeCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "此版本需先通过测试才能启用。");
            }
            jdbc.update("""
                    UPDATE agent_runtime_settings SET active_model_id = ?, activated_at = CURRENT_TIMESTAMP(3),
                    revision = revision + 1 WHERE id = 1
                    """, id);
            audit(admin, "MODEL_ACTIVATE", id == null ? "environment" : id.toString(), "previous=" + previous);
            return loadState(before);
        });
    }

    public synchronized void updatePolicy(long modelMs, long toolMs, long requestMs, long expectedRevision, long admin) {
        validateTimeout(modelMs);
        validateTimeout(toolMs);
        validateTimeout(requestMs);
        if (state.revision() != expectedRevision) throw conflict();
        if (modelMs > requestMs || toolMs > requestMs
                || state.tools().values().stream().anyMatch(tool -> tool.timeoutMs() != null && tool.timeoutMs() > requestMs)) {
            throw new IllegalArgumentException("整轮时限不能小于模型、默认工具或任何单个工具的超时。");
        }
        var current = state.settings().calls().settings();
        if (modelMs == current.modelTimeoutMs() && toolMs == current.toolTimeoutMs()
                && requestMs == current.requestTimeoutMs()) return;
        State before = state;
        state = transactions.execute(transaction -> {
            jdbc.update("""
                    UPDATE agent_runtime_settings SET model_timeout_ms = ?, tool_timeout_ms = ?,
                    request_timeout_ms = ?, revision = revision + 1 WHERE id = 1
                    """, modelMs, toolMs, requestMs);
            audit(admin, "POLICY_UPDATE", "global", "modelMs=%d->%d, toolMs=%d->%d, requestMs=%d->%d".formatted(
                    current.modelTimeoutMs(), modelMs, current.toolTimeoutMs(), toolMs,
                    current.requestTimeoutMs(), requestMs));
            return loadState(before);
        });
    }

    public synchronized void updateTool(String name, boolean enabled, Long timeoutMs, long version, long admin) {
        if (catalog.stream().noneMatch(tool -> tool.name().equals(name))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到内置工具。");
        }
        State before = state;
        if (timeoutMs != null) {
            validateTimeout(timeoutMs);
            if (timeoutMs > before.settings().calls().settings().requestTimeoutMs()) {
                throw new IllegalArgumentException("工具超时不能超过整轮时限。");
            }
        }
        var previous = before.tools().get(name);
        if ((previous == null ? 0 : previous.revision()) != version) throw conflict();
        boolean wasEnabled = previous == null || previous.enabled();
        Long wasTimeout = previous == null ? null : previous.timeoutMs();
        if (wasEnabled == enabled && Objects.equals(wasTimeout, timeoutMs)) return;
        state = transactions.execute(transaction -> {
            if (previous == null) {
                jdbc.update("INSERT INTO agent_tool_settings (tool_name, enabled, timeout_ms, revision, updated_by) VALUES (?, ?, ?, 1, ?)",
                        name, enabled, timeoutMs, admin);
            } else {
                jdbc.update("""
                        UPDATE agent_tool_settings SET enabled = ?, timeout_ms = ?, revision = revision + 1,
                        updated_by = ?, updated_at = CURRENT_TIMESTAMP(3) WHERE tool_name = ?
                        """, enabled, timeoutMs, admin, name);
            }
            audit(admin, "TOOL_UPDATE", name, "enabled=%s->%s, timeoutMs=%s->%s".formatted(
                    wasEnabled, enabled, wasTimeout, timeoutMs));
            return loadState(before);
        });
    }

    private State loadState(State before) {
        var configured = jdbc.queryForObject("SELECT * FROM agent_runtime_settings WHERE id = 1",
                (rs, row) -> new ConfigRow(rs.getObject("active_model_id", Long.class), rs.getLong("revision"),
                        rs.getObject("model_timeout_ms", Long.class), rs.getObject("tool_timeout_ms", Long.class),
                        rs.getObject("request_timeout_ms", Long.class), instant(rs.getTimestamp("activated_at"))));
        var fallback = defaults.settings();
        var calls = new AgentCallPolicy(executor, orDefault(configured.modelMs(), fallback.modelTimeoutMs()),
                orDefault(configured.toolMs(), fallback.toolTimeoutMs()),
                orDefault(configured.requestMs(), fallback.requestTimeoutMs()));
        var active = configured.activeId() == null ? null : model(configured.activeId());
        String modelVersion = active == null ? "environment" : active.profileId() + "/v" + active.revision();
        long timeout = calls.settings().modelTimeoutMs();
        var model = before != null && modelVersion.equals(before.settings().modelVersion())
                && before.settings().calls().settings().modelTimeoutMs() == timeout ? before.settings().model()
                : active == null ? clients.initial(timeout)
                : clients.managed(clients.managedUrl(active.baseUrl()), active.modelName(),
                        secrets.decrypt(storedKey(active.id())), timeout);
        var tools = new HashMap<String, ToolRow>();
        var policies = new HashMap<String, AgentRequestSettings.ToolPolicy>();
        for (var tool : jdbc.query(SELECT_TOOLS, TOOL)) {
            tools.put(tool.name(), tool);
            policies.put(tool.name(), new AgentRequestSettings.ToolPolicy(tool.enabled(), tool.timeoutMs()));
        }
        return new State(active, configured.activatedAt(), configured.revision(), Map.copyOf(tools),
                new AgentRequestSettings(model, modelVersion, calls, policies));
    }

    private ModelRevision model(long id) {
        return jdbc.query(SELECT_MODELS + " WHERE id = ?", MODEL, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到模型配置版本。"));
    }

    private byte[] storedKey(long id) {
        return jdbc.queryForObject("SELECT encrypted_key FROM agent_model_revision WHERE id = ?", byte[].class, id);
    }

    private void audit(long admin, String action, String target, String details) {
        jdbc.update("INSERT INTO agent_settings_change (admin_id, action, target, details) VALUES (?, ?, ?, ?)",
                admin, action, target, details);
    }

    private static void validateTimeout(long ms) {
        if (ms < 100 || ms > 120_000) throw new IllegalArgumentException("时限须在 100 至 120000 毫秒之间。");
    }

    private static long orDefault(Long value, long fallback) { return value == null ? fallback : value; }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "配置已更新，请重新加载核对后再操作。");
    }

    private record ConfigRow(Long activeId, long revision, Long modelMs, Long toolMs, Long requestMs, Instant activatedAt) {}
    private record ToolRow(String name, boolean enabled, Long timeoutMs, long revision, Instant updatedAt, String updatedBy) {}
    private record State(ModelRevision active, Instant activatedAt, long revision, Map<String, ToolRow> tools,
                         AgentRequestSettings settings) {}

    public record ModelRevision(long id, String profileId, int revision, String label, String baseUrl, String modelName,
                                String probeCode, Long probeMs, Instant testedAt) {}
    public record ModelView(String mode, AgentCallPolicy.Settings callPolicy, DeepSeekDecisionModel.ProviderSettings provider,
                            ModelRevision active, Instant activatedAt, String environmentBaseUrl, long revision, boolean secretStorageReady,
                            List<ModelRevision> models) {}
    public record ToolView(String name, String description, List<ReadToolSpec.Parameter> parameters, boolean enabled,
                           Long timeoutMs, long defaultTimeoutMs, long revision, Instant updatedAt, String updatedBy) {}
}
