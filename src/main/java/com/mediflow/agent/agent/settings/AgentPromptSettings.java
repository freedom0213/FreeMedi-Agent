package com.mediflow.agent.agent.settings;

import com.mediflow.agent.mapper.admin.agent.AgentPromptMapper;
import com.mediflow.agent.agent.model.DeepSeekDecisionModel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.agent.model.AgentPrompt;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
@Profile("mysql")
public class AgentPromptSettings {
    private final AgentSettings settings;
    private final AgentPromptMapper mapper;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AgentPromptTrials trials;
    private Long runningTrial;

    AgentPromptSettings(AgentSettings settings, AgentPromptMapper mapper, PlatformTransactionManager transactions,
                        ObjectMapper json, Clock clock) {
        this.settings = settings;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactions);
        this.json = json;
        this.trials = new AgentPromptTrials(clock);
    }

    public View view() {
        synchronized (settings) {
            var selected = mapper.selection();
            var fingerprint = settings.promptTrialFingerprint();
            var versions = mapper.versions().stream().map(row -> view(row, fingerprint)).toList();
            return new View(selected.revision(), selected.activeId(), selected.activatedAt(), settings.snapshot().prompt().version(),
                    fingerprint != null, runningTrial, AgentPrompt.BUILT_IN, DeepSeekDecisionModel.SYSTEM_PROMPT, versions);
        }
    }

    public void save(String triage, String selection, String note, long actor) {
        String triageText = clean(triage);
        String selectionText = clean(selection);
        String changeNote = clean(note);
        synchronized (settings) {
            transactions.executeWithoutResult(tx -> {
                mapper.save(triageText, selectionText, changeNote, actor);
                settings.audit(actor, "PROMPT_SAVE", "triage-conversation", changeNote);
            });
        }
    }

    public void trial(long id, long actor) {
        AgentPromptMapper.Version candidate;
        AgentSettings.PromptTrialContext context;
        synchronized (settings) {
            if (runningTrial != null) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "已有模板正在试跑。");
            candidate = requireVersion(id);
            context = settings.promptTrialContext();
            runningTrial = id;
        }
        try {
            var report = trials.run(context, prompt(candidate));
            String payload = write(report);
            synchronized (settings) {
                transactions.executeWithoutResult(tx -> {
                    mapper.recordTrial(id, context.fingerprint(), report.candidatePassed(), payload);
                    settings.audit(actor, "PROMPT_TRIAL", prompt(candidate).version(), report.candidatePassed() ? "PASSED" : "FAILED");
                });
            }
        } finally {
            synchronized (settings) {
                runningTrial = null;
            }
        }
    }

    public void activate(Long id, long expectedRevision, long actor) {
        synchronized (settings) {
            var before = mapper.selection();
            if (before.revision() != expectedRevision) throw conflict();
            if (Objects.equals(id, before.activeId())) return;
            if (id != null && Objects.equals(id, runningTrial)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请等待当前试跑结束。");
            }
            var target = id == null ? null : requireVersion(id);
            if (target != null && !passed(target, settings.promptTrialFingerprint())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "此版本需先在当前模型和工具配置下通过试跑。");
            }
            var prompt = target == null ? AgentPrompt.BUILT_IN : prompt(target);
            transactions.executeWithoutResult(tx -> {
                if (mapper.activate(id, expectedRevision) != 1) throw conflict();
                settings.audit(actor, "PROMPT_ACTIVATE", prompt.version(), "previous=" + selectedPrompt(before).version());
            });
            settings.usePrompt(prompt);
        }
    }

    static AgentPrompt selectedPrompt(AgentPromptMapper.Selection selected) {
        return selected.activeId() == null ? AgentPrompt.BUILT_IN
                : new AgentPrompt("v" + selected.activeId(), selected.triage(), selected.selection());
    }

    private static AgentPrompt prompt(AgentPromptMapper.Version row) {
        return new AgentPrompt("v" + row.id(), row.triage(), row.selection());
    }

    private AgentPromptMapper.Version requireVersion(long id) {
        return mapper.version(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到模板版本。"));
    }

    private static boolean passed(AgentPromptMapper.Version version, String fingerprint) {
        return fingerprint != null && fingerprint.equals(version.fingerprint()) && Boolean.TRUE.equals(version.passed());
    }

    private Version view(AgentPromptMapper.Version row, String fingerprint) {
        return new Version(row.id(), prompt(row), row.note(), row.createdBy(), row.createdAt(), row.testedAt(),
                row.passed(), passed(row, fingerprint), read(row.report()));
    }

    private AgentPromptTrials.Report read(String stored) {
        if (stored == null) return null;
        try {
            return json.readValue(stored, AgentPromptTrials.Report.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid saved prompt trial report", invalid);
        }
    }

    private String write(AgentPromptTrials.Report report) {
        try {
            return json.writeValueAsString(report);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot serialize prompt trial report", invalid);
        }
    }

    private static String clean(String text) {
        if (text.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')) {
            throw new IllegalArgumentException("请输入有效的模板文本。");
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "当前生效版本已更新，请刷新后重试。");
    }

    public record View(long revision, Long activeId, Instant activatedAt, String effectiveVersion, boolean trialAvailable,
                       Long runningTrial, AgentPrompt builtIn, String basePrompt, List<Version> versions) {}
    public record Version(long id, AgentPrompt prompt, String note, String createdBy, Instant createdAt, Instant testedAt,
                          Boolean trialPassed, boolean canActivate, AgentPromptTrials.Report report) {}
}
