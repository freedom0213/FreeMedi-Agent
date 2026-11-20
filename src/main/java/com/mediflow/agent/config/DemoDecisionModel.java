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
import com.mediflow.agent.agent.KnowledgeConsultation;
import com.mediflow.agent.agent.PlanDraft;
import com.mediflow.agent.agent.ReadToolSpec;
import com.mediflow.agent.agent.ToolCall;
import com.mediflow.agent.agent.TriageRecommendation;
import com.mediflow.agent.appointment.AppointmentDateParser;
import com.mediflow.agent.knowledge.HospitalKnowledgeQuestion;

/** Small deterministic offline demo; real conversations use DeepSeek, not these keyword rules. */
public final class DemoDecisionModel implements DecisionModel {
    private static final Map<String, String> DEPARTMENTS = Map.of(
            "心内科", "心内科", "呼吸内科", "呼吸内科", "呼吸科", "呼吸内科",
            "皮肤科", "皮肤科", "全科医学科", "全科医学科", "全科", "全科医学科",
            "神经内科", "神经内科", "消化内科", "消化内科");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[日号]?|明天|后天|今天");
    private static final String DATE_WORD = DATE.pattern() + "|(?:[本这下])?(?:周|星期)[一二三四五六日天]";
    private static final Pattern PATIENT_DATE = Pattern.compile(DATE_WORD);
    private static final Pattern DOCTOR = Pattern.compile("[\\p{IsHan}]医生");
    private static final Pattern DEPARTMENT = Pattern.compile("(?:预约|挂|看|换成|改成)(?!哪|什么)([\\p{IsHan}]{1,7}?科)");

    @Override
    public AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools) {
        if (context.selectionRequired()) return decideWithSelection(context, tools);
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

    private AgentDecision decideWithSelection(DecisionContext context, List<ReadToolSpec> tools) {
        String message = context.userMessage().strip();
        if (message.matches(".*(呼吸困难|持续胸痛|意识不清).*")) {
            return new AgentDecision.Reply("你描述的情况可能需要紧急处理，请立即联系急救或前往急诊。");
        }
        if (HospitalKnowledgeQuestion.isConsultation(message)
                && tools.stream().anyMatch(tool -> KnowledgeConsultation.TOOL_NAME.equals(tool.name()))) {
            return new AgentDecision.CallReadTool(new ToolCall(KnowledgeConsultation.TOOL_NAME, Map.of("query", message)));
        }
        if (message.matches(".*(准备|材料|为什么|理由|规则|怎么去|在哪里|怎么取消).*")
                && !message.matches(".*(改成|换成|改到|换到|重新查).*")) {
            return new AgentDecision.Reply("我可以继续帮你核对预约条件。预约方案保持原样；就诊材料和具体要求请以医院通知为准。");
        }
        if (message.matches("^(确认|就这个|好的|同意|可以)[。！!，, ]*$")
                && context.task().status() == com.mediflow.agent.agent.TaskStatus.AWAITING_CONFIRMATION) {
            return new AgentDecision.Reply("请在确认单上核对医生和时间，再点击「确认预约」。");
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        DEPARTMENTS.keySet().stream().filter(message::contains).max(java.util.Comparator.comparingInt(String::length))
                .ifPresent(name -> patch.put("departmentName", DEPARTMENTS.get(name)));
        var department = DEPARTMENT.matcher(message);
        if (!patch.containsKey("departmentName") && department.find()
                && !department.group(1).matches(".*(哪|什么|合适).*")) {
            patch.put("departmentName", department.group(1));
        }
        var doctor = DOCTOR.matcher(message);
        if (doctor.find()) patch.put("doctorName", doctor.group());
        if (message.matches(".*(医生不限|不限医生|哪个医生都行).*")) patch.put("doctorName", "");

        var date = PATIENT_DATE.matcher(message);
        if (date.find()) {
            patch.put("appointmentDate", date.group());
            patch.put("dateFlexible", prefers(message, date.group(), "(?:别的|其他|其它)(?:日子|日期|天)也"));
        } else if (message.matches(".*(日期不限|哪天都行).*")
                || message.matches(".*(最近|尽快|最早).*") && message.matches(".*(预约|挂号|有号|查号).*")) {
            patch.put("appointmentDate", "");
        }
        var period = Pattern.compile("上午|早上|下午|晚上|晚间").matcher(message);
        if (period.find()) {
            patch.put("clinicPeriod", period.group());
            patch.put("periodFlexible", prefers(message, period.group(), "(?:别的|其他|其它)(?:时间|时段)也"));
        }
        if (message.matches(".*(时间都可以|时段不限|不限时段).*")) patch.put("clinicPeriod", "");
        if (message.matches(".*(如果没有号|没号|无号).*替代.*")) {
            if (patch.containsKey("appointmentDate")) patch.put("dateFlexible", !message.contains("必须"));
            if (patch.containsKey("clinicPeriod")) patch.put("periodFlexible", !message.contains("必须"));
        }
        if (!patch.containsKey("departmentName") && message.matches("^(可以|好的|按你建议|按这个科室).*")
                && context.task().facts().get("triageRecommendation") instanceof Map<?, ?> recommendation
                && recommendation.get("departmentName") instanceof String direction) {
            patch.put("departmentName", direction);
        }
        if (!patch.isEmpty() || context.task().facts().containsKey("requirements")
                && message.matches(".*(查号|查找|找一下|再查|有号吗|看看号源).*")) {
            return new AgentDecision.CallReadTool(new ToolCall("findAvailableSlots", patch));
        }
        String symptom = symptomDirection(message);
        if (symptom == null && message.matches(".*(分诊|就诊方向).*")) {
            symptom = context.task().turns().stream().filter(turn -> turn.assistantMessage() != null)
                    .map(turn -> symptomDirection(turn.userMessage())).filter(java.util.Objects::nonNull)
                    .reduce((before, last) -> last).orElse(null);
        }
        if (symptom != null) {
            return new AgentDecision.AskUser("可以先考虑" + symptom + "，这是初步就诊方向。需要我按这个方向查号吗？",
                    Set.of("departmentName"), new TriageRecommendation(0, symptom, "根据你刚才描述的情况给出的就诊方向"));
        }
        return new AgentDecision.AskUser("你可以告诉我想看的科室、医生或方便的时间，也可以先描述哪里不舒服。",
                Set.of("departmentName"));
    }

    private static String symptomDirection(String message) {
        if (message.matches("(?s).*(咳嗽|咽痛).*")) return "呼吸内科";
        if (message.contains("心悸")) return "心内科";
        if (message.matches("(?s).*(皮疹|瘙痒).*")) return "皮肤科";
        if (message.matches("(?s).*(头疼|头痛).*")) return "神经内科";
        if (message.matches("(?s).*(肚子疼|腹痛|胃痛).*")) return "消化内科";
        return null;
    }

    private static boolean prefers(String message, String value, String fallback) {
        String quoted = Pattern.quote(value);
        return !message.matches(".*(?:必须|只能|只要)(?:在|是|选)?" + quoted + ".*")
                && message.matches(".*(?:(?:优先|最好)(?:选|是|在)?" + quoted + "|"
                + quoted + "(?:优先|最好)|" + fallback + ").*");
    }

    private static long number(Map<?, ?> values, String name) {
        return Long.parseLong(String.valueOf(values.get(name)));
    }
}
