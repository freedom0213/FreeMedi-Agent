package com.mediflow.agent.config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.mediflow.agent.agent.AgentDecision;
import com.mediflow.agent.agent.DecisionContext;
import com.mediflow.agent.agent.DecisionModel;
import com.mediflow.agent.agent.PlanDraft;
import com.mediflow.agent.agent.ReadToolSpec;
import com.mediflow.agent.agent.ToolCall;
import com.mediflow.agent.agent.TriageRecommendation;
import com.mediflow.agent.appointment.AppointmentDateParser;

/** Small deterministic offline demo; real conversations use DeepSeek, not these keyword rules. */
public final class DemoDecisionModel implements DecisionModel {
    private static final Map<String, String> DEPARTMENTS = Map.of(
            "心内科", "心内科", "呼吸内科", "呼吸内科", "呼吸科", "呼吸内科",
            "皮肤科", "皮肤科", "全科医学科", "全科医学科", "全科", "全科医学科",
            "神经内科", "神经内科", "消化内科", "消化内科");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[日号]?|明天|后天|今天");

    @Override
    public AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools) {
        List<String> messages = new ArrayList<>(context.task().turns().stream()
                .filter(turn -> turn.assistantMessage() != null).map(turn -> turn.userMessage()).toList());
        messages.add(context.userMessage());
        String department = null;
        String symptomDepartment = null;
        LocalDate date = null;
        String period = null;
        for (String message : messages) {
            for (var entry : DEPARTMENTS.entrySet()) {
                if (message.contains(entry.getKey())) department = entry.getValue();
            }
            if (message.contains("咳嗽") || message.contains("咽痛")) symptomDepartment = "呼吸内科";
            if (message.contains("心悸")) symptomDepartment = "心内科";
            if (message.contains("皮疹") || message.contains("瘙痒")) symptomDepartment = "皮肤科";
            if (message.contains("头疼") || message.contains("头痛")) symptomDepartment = "神经内科";
            if (message.contains("肚子疼") || message.contains("腹痛") || message.contains("胃痛")) symptomDepartment = "消化内科";
            if (department == null && symptomDepartment != null
                    && message.matches("^(可以|好的|确认|就这个|按你建议).*$")) department = symptomDepartment;
            var match = DATE.matcher(message);
            if (match.find()) date = AppointmentDateParser.parse(match.group(), LocalDate.now(ZoneId.of("Asia/Shanghai")));
            if (message.contains("最近") || message.contains("尽快") || message.contains("最早")) date = null;
            if (message.contains("上午") || message.contains("早上")) period = "MORNING";
            if (message.contains("下午")) period = "AFTERNOON";
            if (message.contains("晚上")) period = "EVENING";
            if (message.contains("时间都可以") || message.contains("时段不限")) period = null;
        }
        if (department == null) {
            if (symptomDepartment != null) {
                boolean directTriage = messages.stream().anyMatch(message -> message.contains("智能分诊")
                        || message.contains("直接分诊") || message.contains("现在就分诊")
                        || message.contains("比较着急") || message.contains("就诊方向建议"));
                return new AgentDecision.AskUser(
                        "根据目前的信息，建议先考虑" + symptomDepartment + "。这只是初步就诊方向，不是诊断；"
                                + (directTriage ? "如有突发剧烈不适或其他急症表现，请及时前往急诊。需要我按这个方向查找近期号源吗？"
                                        : "您还可以补充持续时间或伴随症状，也可以直接让我按这个方向查号。"),
                        Set.of("departmentName"),
                        new TriageRecommendation(0, symptomDepartment,
                                "根据你描述的症状，先由" + symptomDepartment + "了解情况更合适"));
            }
            return new AgentDecision.AskUser("您哪里不舒服，持续多久了？如果已经知道要看哪个科室，也可以直接告诉我。",
                    Set.of("departmentName"));
        }
        if (context.toolResults().isEmpty()) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("departmentName", department);
            arguments.put("limit", 20);
            if (date != null) arguments.put("appointmentDate", date.toString());
            if (period != null) arguments.put("clinicPeriod", period);
            return new AgentDecision.CallReadTool(new ToolCall("findAvailableSlots", arguments));
        }

        Map<String, Object> data = context.toolResults().get(context.toolResults().size() - 1).data();
        List<Map<?, ?>> candidates = new ArrayList<>();
        if (data.get("slots") instanceof List<?> slots) {
            for (Object value : slots) {
                if (value instanceof Map<?, ?> slot && department.equals(slot.get("departmentName"))) candidates.add(slot);
            }
        }
        if (candidates.isEmpty()) return new AgentDecision.AskUser(
                "暂时没有找到这个科室的可用号源，您希望换位医生或稍后再查询吗？", Set.of());
        String requestedPeriod = period;
        LocalDate requestedDate = date;
        Map<?, ?> selected = candidates.stream()
                .filter(slot -> requestedPeriod == null || requestedPeriod.equals(slot.get("clinicPeriod")))
                .filter(slot -> requestedDate == null || requestedDate.toString().equals(slot.get("appointmentDate")))
                .findFirst().orElse(candidates.get(0));
        boolean alternate = Boolean.FALSE.equals(data.get("exactMatch"))
                || requestedPeriod != null && !requestedPeriod.equals(selected.get("clinicPeriod"))
                || requestedDate != null && !requestedDate.toString().equals(selected.get("appointmentDate"));
        PlanDraft draft = new PlanDraft(context.task().patientId(), number(selected, "departmentId"),
                number(selected, "doctorId"), number(selected, "slotId"),
                LocalDate.parse(String.valueOf(selected.get("appointmentDate"))),
                String.valueOf(selected.get("timeRange")), Instant.now().plusSeconds(600));
        return new AgentDecision.ProposePlan(draft,
                (alternate ? "原日期或时段没有号，为您找到同科室的替代时间。" : "已找到可用号源。")
                        + "请核对下方医生、日期和时间，点击确认预约后才会提交。");
    }

    private static long number(Map<?, ?> values, String name) {
        return Long.parseLong(String.valueOf(values.get(name)));
    }
}
