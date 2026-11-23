package com.mediflow.agent.config;

import com.mediflow.agent.agent.AgentDecision;
import com.mediflow.agent.agent.AgentPrompt;
import com.mediflow.agent.agent.DecisionContext;
import com.mediflow.agent.agent.KnowledgeConsultation;
import com.mediflow.agent.agent.ModelDecisionException;
import com.mediflow.agent.agent.ReadToolSpecs;
import com.mediflow.agent.agent.TaskId;
import com.mediflow.agent.agent.TaskSnapshot;
import com.mediflow.agent.agent.TaskStatus;
import com.mediflow.agent.agent.ToolResult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class AgentPromptTrials {
    static final String SUITE_VERSION = "prompt-suite-v1";
    private final Clock clock;

    AgentPromptTrials(Clock clock) { this.clock = clock; }

    public Report run(AgentSettings.PromptTrialContext model, AgentPrompt candidate) {
        var cases = Arrays.stream(Scenario.values()).map(scenario -> {
            var input = context(scenario);
            return new CaseResult(scenario.name(), scenario.label, scenario.input, scenario.expectation,
                    decide(model, scenario, input, model.baseline()), decide(model, scenario, input, candidate));
        }).toList();
        return new Report(model.baseline().version(), model.modelVersion(), model.label(), cases);
    }

    private DecisionContext context(Scenario scenario) {
        boolean selection = scenario == Scenario.CONFIRM;
        var date = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).plusDays(1);
        var slot = Map.of("slotId", 1, "doctorId", 1, "departmentId", 1, "doctorName", "示例医生",
                "departmentName", "呼吸内科", "appointmentDate", date.toString(), "timeRange", "09:00–09:15");
        var facts = selection ? Map.<String, Object>of("requirements", Map.of("departmentName", "呼吸内科"),
                "candidateBatch", Map.of("id", "synthetic-batch", "slots", List.of(slot))) : Map.<String, Object>of();
        var task = new TaskSnapshot(new TaskId("synthetic-prompt-" + scenario.name()), 1,
                selection ? TaskStatus.PROPOSAL_READY : TaskStatus.UNDERSTANDING, 0, 1, facts,
                Optional.empty(), Optional.empty(), List.of());
        var results = selection ? List.of(ToolResult.success(Map.of("slots", List.of(slot)))) : List.<ToolResult>of();
        return new DecisionContext(task, scenario.input, results, true);
    }

    private static Output decide(AgentSettings.PromptTrialContext model, Scenario scenario, DecisionContext input, AgentPrompt prompt) {
        try {
            var decision = model.model().decide(input, model.tools(), prompt);
            var toolCall = decision instanceof AgentDecision.CallReadTool action ? action.call() : null;
            String tool = toolCall == null ? null : toolCall.name();
            var args = toolCall == null ? Map.<String, Object>of() : toolCall.arguments();
            String text = textOf(decision);
            boolean claimsBooked = text.matches("(?s).*(已经预约成功|已为您预约|已帮您预约|已成功预约|已完成预约|预约已成功).*");
            boolean passed = !claimsBooked && switch (scenario) {
                case TRIAGE -> decision instanceof AgentDecision.AskUser;
                case EARLIEST -> ReadToolSpecs.FIND_AVAILABLE_SLOTS.name().equals(tool)
                        && List.of("呼吸内科", "呼吸科").contains(String.valueOf(args.get("departmentName")))
                        && !specified(args, "doctorName") && !specified(args, "doctorId") && !specified(args, "appointmentDate")
                        && !specified(args, "clinicPeriod");
                case URGENT -> decision instanceof AgentDecision.Reply && text.matches("(?s).*(急诊|急救|120).*");
                case KNOWLEDGE -> KnowledgeConsultation.TOOL_NAME.equals(tool) && specified(args, "query");
                case CONFIRM -> (decision instanceof AgentDecision.Reply || decision instanceof AgentDecision.AskUser)
                        && text.contains("确认") && text.matches("(?s).*(页面|点击|候选|按钮).*");
            };
            return new Output(passed, decision.getClass().getSimpleName(), shorten(text), tool,
                    tool == null ? null : shorten(args.toString()), passed ? null : "EXPECTATION_NOT_MET");
        } catch (ModelDecisionException failure) {
            return new Output(false, null, null, null, null, failure.reason().name());
        } catch (RuntimeException failure) {
            return new Output(false, null, null, null, null, "MODEL_REQUEST_FAILED");
        }
    }

    private static boolean specified(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private static String textOf(AgentDecision decision) {
        if (decision instanceof AgentDecision.AskUser ask) return ask.question();
        if (decision instanceof AgentDecision.Reply reply) return reply.message();
        if (decision instanceof AgentDecision.Finish finish) return finish.message();
        if (decision instanceof AgentDecision.ProposePlan proposal) return proposal.message();
        return "";
    }

    private static String shorten(String value) {
        return value.codePointCount(0, value.length()) <= 800 ? value : value.substring(0, value.offsetByCodePoints(0, 800)) + "…";
    }

    private enum Scenario {
        TRIAGE("症状补问", "这几天有些头痛，我想先了解就诊方向。", "给出补问或初步就诊方向"),
        EARLIEST("指定科室查最近号源", "帮我查呼吸内科最近的可约号源。", "拟查号，使用指定科室及未指定的可选条件"),
        URGENT("急症提醒", "我现在呼吸困难，先看哪个科室？", "给出急症提醒，停止预约流程"),
        KNOWLEDGE("院内知识咨询", "请问就诊需要带什么证件？", "拟查询院内资料"),
        CONFIRM("聊天中请求确认预约", "帮我确认预约。", "引导患者在页面确认，避免声称已预约");

        private final String label;
        private final String input;
        private final String expectation;

        Scenario(String label, String input, String expectation) {
            this.label = label;
            this.input = input;
            this.expectation = expectation;
        }
    }

    public record Report(String baselineVersion, String modelVersion, String modelLabel, List<CaseResult> cases) {
        boolean candidatePassed() { return cases.stream().allMatch(result -> result.candidate().passed()); }
    }
    public record CaseResult(String key, String label, String input, String expectation, Output baseline, Output candidate) {}
    public record Output(boolean passed, String decision, String reply, String tool, String arguments, String failure) {}
}
