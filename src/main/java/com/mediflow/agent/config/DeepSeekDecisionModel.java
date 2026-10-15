package com.mediflow.agent.config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.agent.AgentDecision;
import com.mediflow.agent.agent.DecisionContext;
import com.mediflow.agent.agent.DecisionModel;
import com.mediflow.agent.agent.PlanDraft;
import com.mediflow.agent.agent.ReadToolSpec;
import com.mediflow.agent.agent.ToolCall;
import com.mediflow.agent.agent.TriageRecommendation;
import com.mediflow.agent.agent.ModelDecisionException;
import static com.mediflow.agent.agent.ModelDecisionException.Reason.*;

import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;

/** Maps DeepSeek tool calls and constrained JSON decisions into the AgentRuntime protocol. */
public final class DeepSeekDecisionModel implements DecisionModel {
    private static final String SYSTEM_PROMPT = """
            你是 FreeMedi 医院的导诊与预约助手。只能根据用户消息、任务状态和工具结果提出下一步决策。
            只可调用提供的只读工具；不得声称已经预约成功，不得执行或要求执行写操作。
            面向普通患者交流，始终使用自然、易懂的中文。不得要求患者使用 yyyy-MM-dd，不得在回复中出现 MORNING、AFTERNOON、EVENING、数据库 ID 或工具名。
            用户可以用“10月15号”“明天”“下周三”“最近几天”“最近有号的时间”等说法表达日期；根据当前日期理解，不向患者追问标准格式。没有指定年份的月日按当前日期之后最近一次理解。
            用户只描述不适时，先回应其感受。根据常见症状给出谨慎的就诊科室方向建议，并明确这不是诊断；询问患者是否按该科室查号。只有症状、还未同意科室方向时，不要查询或要求患者自己懂科室。若尚未描述不适，先问哪里不舒服、持续多久。
            如果用户点击“发起智能分诊”或明确表示着急、希望现在直接获得分诊建议，不要要求完成一整套问诊。综合当前对话已有信息，给出暂定科室方向和简短理由；只补问影响安全或分诊方向的关键问题。说明建议可以在补充信息后调整，且不代表诊断。若现有信息完全不足，再问一个最关键的问题。
            咳嗽、喘息、咽痛等呼吸道症状可建议呼吸内科；头痛、头晕等可建议神经内科；腹痛、胃痛等可建议消化内科；胸闷、心悸可建议心内科；皮疹、瘙痒可建议皮肤科；不确定时建议全科医学科。若用户描述呼吸困难、持续胸痛、意识不清等急症信号，提醒立即联系急救/前往急诊，不要继续预约流程。
            若用户接受建议或明确科室，医生和时段均为可选条件。日期和时段也可以缺省：用户希望尽快、最近或未指定日期时，查询该科室最早的可用号源；未指定时段时不要追问时段。
            不得为查询而主动追问医生偏好；没有指定医生就直接查询该科室。患者要求最近/最早/尽快时，不得将医生、日期、时段作为缺失信息阻塞查询。
            用户指定日期/时段后使用这些条件查询。若没有匹配号源，主动扩大查询范围：先保留同科室和已指定时段、移除指定日期；仍无结果时再移除时段，找最近可用号源。向用户说明这是替代时间并让其选择/确认，禁止静默创建预约。
            用户提供科室名或医生名时，使用 departmentName 或 doctorName 查询；绝不能向用户索取数据库 ID。不知道科室 ID 时省略 ID 参数，使用 departmentName。
            号源结果只能选择真实返回的记录。工具结果为空时可以说明暂时没有可用号源并询问是否扩大日期范围，不得捏造医生、号源或结果。
            工具自动搜索备选号源时会标记 exactMatch=false，notice 会说明变化；必须向患者解释原日期/时段没有号，并说清推荐的替代日期和时段，不能声称完全符合原条件。搜索范围是未来90天，不能把范围外的日期描述为已查询。
            每次只调用一个工具。需要规则时先查规则再查号源；用户提供的信息及已接受的科室方向要沿用，不能反复问已经回答过的问题。
            如果工具结果中有合适号源，可提出其中一个候选号源形成方案。回复明确提醒患者“请核对下方方案，点击确认预约后才会提交”。患者可继续提出调整需求；普通聊天中的“确认”不能替代界面按钮，不能据此声称已预约。
            除工具调用外，严格返回单个 JSON 对象，不要 Markdown：
            {"type":"ASK_USER","question":"...","missingFields":["appointmentDate"],"recommendation":{"departmentName":"神经内科","reason":"头痛症状通常可先由该科室评估"}}
            {"type":"PROPOSE_PLAN","slotId":1,"departmentId":1,"doctorId":1,
             "appointmentDate":"2027-01-15","timeRange":"14:00-14:30","message":"..."}
            {"type":"FINISH","message":"..."}
            missingFields 只允许 departmentName、doctorName、appointmentDate、clinicPeriod。
            """;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final ChatLanguageModel model;
    private final ObjectMapper json;
    private final Clock clock;

    public DeepSeekDecisionModel(ChatLanguageModel model, ObjectMapper json) {
        this(model, json, Clock.systemUTC());
    }

    public DeepSeekDecisionModel(ChatLanguageModel model, ObjectMapper json, Clock clock) {
        this.model = model;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT + "\n当前日期（北京时间）："
                + LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))));
        context.task().turns().stream()
                .filter(turn -> turn.assistantMessage() != null)
                .forEach(turn -> {
                    messages.add(UserMessage.from(turn.userMessage()));
                    messages.add(AiMessage.from(turn.assistantMessage()));
                });
        messages.add(UserMessage.from("当前任务状态：" + context.task().status()
                + "\n已收集条件：" + write(context.task().facts())
                + "\n当前用户消息：" + context.userMessage()));
        if (!context.toolResults().isEmpty()) {
            List<Map<String, Object>> results = context.toolResults().stream().map(result -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("success", result.success());
                value.put("data", result.data());
                value.put("errorCode", result.errorCode());
                value.put("errorMessage", result.errorMessage());
                return value;
            }).toList();
            messages.add(UserMessage.from("只读工具执行结果（JSON）：" + write(results)));
        }

        var toolSpecifications = specifications(tools);
        ToolSpecification requiredTool = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var response = requiredTool == null ? model.generate(messages, toolSpecifications)
                    : model.generate(messages, requiredTool);
            var aiMessage = response.content();
            try {
                if (aiMessage == null) throw new ModelDecisionException(EMPTY_RESPONSE);
                if (aiMessage.hasToolExecutionRequests()) {
                    var requests = aiMessage.toolExecutionRequests();
                    if (requests.stream().anyMatch(request -> tools.stream()
                            .noneMatch(tool -> tool.name().equals(request.name())))) {
                        throw new ModelDecisionException(INVALID_TOOL_REQUEST);
                    }
                    // The runtime is sequential: execute one read and let the next step use its result.
                    var request = requests.get(0);
                    return new AgentDecision.CallReadTool(new ToolCall(request.name(), readObject(request.arguments())));
                }
                AgentDecision decision = parseDecision(aiMessage.text(), context);
                if (blocksExplicitNearestRequest(decision, context)) {
                    throw new ModelDecisionException(OPTIONAL_PREFERENCE_QUESTION);
                }
                return decision;
            } catch (RuntimeException exception) {
                ModelDecisionException failure = exception instanceof ModelDecisionException decisionException
                        ? decisionException : new ModelDecisionException(INVALID_DECISION);
                if (attempt == 1) throw failure;
                if (failure.reason() == OPTIONAL_PREFERENCE_QUESTION) {
                    requiredTool = toolSpecifications.stream().filter(tool -> tool.name().equals("findAvailableSlots"))
                            .findFirst().orElse(null);
                    messages.add(UserMessage.from("用户已明确科室并要求最近可用时间。医生、日期和时段均为可选偏好，"
                            + "请直接调用 findAvailableSlots，不要再询问可选条件。"));
                }
                // One bounded correction attempt for protocol errors, never for network/provider failures.
                messages.add(UserMessage.from("上一步输出未通过协议校验（" + failure.reason()
                        + "）。请严格按 JSON 决策格式重试；只可使用本轮工具返回的号源。若信息不足，返回 ASK_USER。"));
            }
        }
        throw new ModelDecisionException(INVALID_DECISION);
    }

    private static boolean blocksExplicitNearestRequest(AgentDecision decision, DecisionContext context) {
        if (!(decision instanceof AgentDecision.AskUser question) || !context.toolResults().isEmpty()) return false;
        String message = context.userMessage();
        // An explicit department plus an earliest-slot request is enough to query. The model
        // may incorrectly ask about symptoms as well as optional booking preferences.
        return message.contains("科") && message.matches("(?s).*(最近|最早|尽快|就近).*");
    }

    private List<ToolSpecification> specifications(List<ReadToolSpec> tools) {
        List<ToolSpecification> result = new ArrayList<>();
        for (ReadToolSpec tool : tools) {
            if ("findAvailableSlots".equals(tool.name())) {
                result.add(ToolSpecification.builder().name(tool.name()).description(tool.description())
                        .addOptionalParameter("departmentId", JsonSchemaProperty.INTEGER,
                                JsonSchemaProperty.description("科室 ID"))
                        .addOptionalParameter("departmentName", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("用户提供的科室名称或科室代码"))
                        .addOptionalParameter("doctorId", JsonSchemaProperty.INTEGER,
                                JsonSchemaProperty.description("医生 ID"))
                        .addOptionalParameter("doctorName", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("用户提供的医生姓名"))
                        .addOptionalParameter("appointmentDate", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("预约日期，格式 yyyy-MM-dd"))
                        .addOptionalParameter("clinicPeriod", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("MORNING、AFTERNOON 或 EVENING"),
                                JsonSchemaProperty.from("enum", List.of("MORNING", "AFTERNOON", "EVENING")))
                        .addOptionalParameter("limit", JsonSchemaProperty.INTEGER,
                                JsonSchemaProperty.description("最多返回数量，不超过 50"))
                        .build());
            } else if ("getAppointmentRules".equals(tool.name())) {
                result.add(ToolSpecification.builder().name(tool.name()).description(tool.description()).build());
            }
        }
        return List.copyOf(result);
    }

    private AgentDecision parseDecision(String content, DecisionContext context) {
        Map<String, Object> decision = readObject(content);
        String type = requiredText(decision, "type");
        return switch (type) {
            case "ASK_USER" -> new AgentDecision.AskUser(patientText(requiredText(decision, "question")),
                    missingFields(decision.get("missingFields")), recommendation(decision.get("recommendation")));
            case "PROPOSE_PLAN" -> {
                long slotId = positiveLong(decision, "slotId");
                Map<String, Object> slot = context.toolResults().stream().filter(result -> result.success())
                        .flatMap(result -> result.data().get("slots") instanceof List<?> slots
                                ? slots.stream() : java.util.stream.Stream.empty())
                        .filter(value -> value instanceof Map<?, ?>)
                        .map(value -> json.convertValue(value, MAP_TYPE))
                        .filter(value -> positiveLong(value, "slotId") == slotId)
                        .findFirst().orElseThrow(() -> new ModelDecisionException(UNSOURCED_SLOT));
                String message = patientText(requiredText(decision, "message"));
                yield new AgentDecision.ProposePlan(new PlanDraft(context.task().patientId(),
                        positiveLong(slot, "departmentId"), positiveLong(slot, "doctorId"), slotId,
                        LocalDate.parse(requiredText(slot, "appointmentDate")), requiredText(slot, "timeRange"),
                        Instant.now(clock).plusSeconds(600)), message, recommendation(decision.get("recommendation")));
            }
            case "FINISH" -> new AgentDecision.Finish(patientText(requiredText(decision, "message")));
            default -> throw new IllegalArgumentException("unsupported decision type");
        };
    }

    private Set<String> missingFields(Object value) {
        if (!(value instanceof List<?> fields)) {
            return Set.of();
        }
        Set<String> allowed = Set.of("departmentName", "doctorName", "appointmentDate", "clinicPeriod");
        return fields.stream().map(String::valueOf).filter(allowed::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private TriageRecommendation recommendation(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Object> candidate = json.convertValue(raw, MAP_TYPE);
        String departmentName = candidate.get("departmentName") == null ? "" : String.valueOf(candidate.get("departmentName"));
        String reason = candidate.get("reason") == null ? "" : patientText(String.valueOf(candidate.get("reason")));
        if (departmentName.isBlank() || reason.isBlank()) return null;
        Object id = candidate.get("departmentId");
        long departmentId = id == null ? 0 : positiveLong(candidate, "departmentId");
        return new TriageRecommendation(departmentId, departmentName, reason);
    }

    private Map<String, Object> readObject(String value) {
        if (value == null || value.isBlank()) throw new ModelDecisionException(INVALID_JSON);
        String normalized = value.trim();
        if (normalized.startsWith("```") && normalized.endsWith("```") && normalized.contains("\n")) {
            normalized = normalized.substring(normalized.indexOf('\n') + 1, normalized.length() - 3).trim();
        }
        try {
            Map<String, Object> result = json.readValue(normalized, MAP_TYPE);
            if (result == null) throw new ModelDecisionException(INVALID_JSON);
            return result;
        } catch (JsonProcessingException exception) {
            throw new ModelDecisionException(INVALID_JSON);
        }
    }

    private static String patientText(String value) {
        return value.replace("yyyy-MM-dd", "例如10月15号")
                .replace("yyyy-mm-dd", "例如10月15号")
                .replace("MORNING", "上午").replace("AFTERNOON", "下午").replace("EVENING", "晚上");
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize agent context", exception);
        }
    }

    private static String requiredText(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("DeepSeek decision is missing " + name);
        }
        return value.toString();
    }

    private static long positiveLong(Map<String, Object> values, String name) {
        Object value = values.get(name);
        long parsed = value instanceof Number number
                ? number.longValue() : Long.parseLong(String.valueOf(value));
        if (parsed <= 0) {
            throw new IllegalArgumentException("DeepSeek decision contains invalid " + name);
        }
        return parsed;
    }
}
