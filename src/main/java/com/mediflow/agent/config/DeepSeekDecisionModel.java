package com.mediflow.agent.config;

import com.mediflow.agent.agent.AgentPrompt;
import com.mediflow.agent.agent.TaskStatus;

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
import com.mediflow.agent.agent.KnowledgeConsultation;
import com.mediflow.agent.agent.PlanDraft;
import com.mediflow.agent.agent.ReadToolSpec;
import com.mediflow.agent.agent.ToolCall;
import com.mediflow.agent.agent.TriageRecommendation;
import com.mediflow.agent.agent.ModelDecisionException;
import com.mediflow.agent.knowledge.HospitalKnowledgeQuestion;
import static com.mediflow.agent.agent.ModelDecisionException.Reason.*;

import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.ai4j.openai4j.OpenAiHttpException;

/** Maps DeepSeek tool calls and constrained JSON decisions into the AgentRuntime protocol. */
public final class DeepSeekDecisionModel implements DecisionModel {
    static final int SDK_MAX_RETRIES = 0;
    private static final int MAX_DECISION_ATTEMPTS = 2;
    static final String SYSTEM_PROMPT = """
            你是 FreeMedi 医院的导诊与预约助手。只能根据用户消息、任务状态和工具结果提出下一步决策。
            只可调用提供的只读工具；不得声称已经预约成功，不得执行或要求执行写操作。
            面向普通患者交流，始终使用自然、易懂的中文。不得要求患者使用 yyyy-MM-dd，不得在回复中出现 MORNING、AFTERNOON、EVENING、数据库 ID 或工具名。
            用户可以用“10月15号”“明天”“下周三”“最近几天”“最近有号的时间”等说法表达日期；根据当前日期理解，不向患者追问标准格式。没有指定年份的月日按当前日期之后最近一次理解。
            用户只描述不适时，先回应其感受。根据常见症状给出谨慎的就诊科室方向建议，并明确这不是诊断；询问患者是否按该科室查号。只有症状、还未同意科室方向时，不要查询或要求患者自己懂科室。若尚未描述不适，先问哪里不舒服、持续多久。
            如果用户点击“发起智能分诊”或明确表示着急、希望现在直接获得分诊建议，不要要求完成一整套问诊。综合当前对话已有信息，给出暂定科室方向和简短理由；只补问影响安全或分诊方向的关键问题。说明建议可以在补充信息后调整，且不代表诊断。若现有信息完全不足，再问一个最关键的问题。
            咳嗽、喘息、咽痛等呼吸道症状可建议呼吸内科；头痛、头晕等可建议神经内科；腹痛、胃痛等可建议消化内科；胸闷、心悸可建议心内科；皮疹、瘙痒可建议皮肤科；不确定时建议全科医学科。若用户描述呼吸困难、持续胸痛、意识不清等急症信号，提醒立即联系急救/前往急诊，不要继续预约流程。
            若用户接受建议或明确科室，医生和时段均为可选条件。日期和时段也可以缺省：用户希望尽快、最近或未指定日期时，查询该科室最早的可用号源；未指定时段时不要追问时段。
            不得为查询而主动追问医生偏好；没有指定医生就直接查询该科室。患者要求最近/最早/尽快时，不得将医生、日期、时段作为缺失信息阻塞查询。
            用户指定日期或时段时，默认查询严格条件。只有患者明确说优先、最好、没号可以替代等，才允许相应日期或时段放宽。科室和医生不得自动更换。
            用户提供科室名或医生名时，使用 departmentName 或 doctorName 查询；绝不能向用户索取数据库 ID。不知道科室 ID 时省略 ID 参数，使用 departmentName。
            号源结果只能选择真实返回的记录。工具结果为空时可以说明暂时没有可用号源并询问是否扩大日期范围，不得捏造医生、号源或结果。
            搜索范围是未来90天，不能把范围外的日期描述为已查询。查询后由后端呈现候选及条件差异。
            每次只调用一个工具。需要规则时先查规则再查号源；用户提供的信息及已接受的科室方向要沿用，不能反复问已经回答过的问题。
            当前已保存的 requirements 是权威需求，以它为准，不要从历史消息复原已修改的条件。调用 findAvailableSlots 只传本轮新提供或明确改动的字段；未传字段继续沿用。明确取消某个条件时给相应名称、日期或时段传空字符串。指定日期为优先时传 dateFlexible=true，指定时段为优先时传 periodFlexible=true；明确要求必须时给对应标记传 false。
            预约意图或条件变动时调用 findAvailableSlots。明确就诊医生但无科室时，可只提供医生名。如果只补充日期或时段，也调用此工具保存条件。
            让患者在页面比较并选择候选。询问就诊准备、就诊须知、证件、取号、预约规定或取消入口时，调用 searchHospitalKnowledge。它是只读咨询，系统会自行返回可核对的来源或说明查不到；不要引用常识补内容，不要调用查号工具改动已保存的条件。单纯询问候选理由时返回 REPLY。对话中的“确认”应提示在页面选中候选并点击确认。
            患者询问某位医生的专长、专业方向、接诊范围，或院内某科室的服务介绍时，调用 searchHospitalKnowledge，保留问题中的科室和医生名字。资料由系统核对，只能用于了解接诊范围，不能用来排名、诊断或推断实时排班。
            除工具调用外，严格返回单个 JSON 对象，不要 Markdown：
            {"type":"ASK_USER","question":"...","missingFields":["appointmentDate"],"recommendation":{"departmentName":"神经内科","reason":"头痛症状通常可先由该科室评估"}}
            {"type":"REPLY","message":"..."}
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

    public ProviderSettings providerSettings() {
        String name = model instanceof OpenAiChatModel client ? client.modelName() : null;
        return new ProviderSettings(name, SDK_MAX_RETRIES, MAX_DECISION_ATTEMPTS - 1);
    }

    public record ProviderSettings(String modelName, int sdkMaxRetries, int maxOutputCorrections) { }

    @Override
    public AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools) {
        return decide(context, tools, AgentPrompt.BUILT_IN);
    }

    @Override
    public AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools, AgentPrompt prompt) {
        boolean triageOnly = needsTriageDirection(context.userMessage());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT + additionalGuidance(context, prompt, triageOnly) + "\n当前日期（北京时间）："
                + LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))) + unavailableToolsNotice(tools)));
        context.task().turns().stream()
                .filter(turn -> turn.assistantMessage() != null)
                .forEach(turn -> {
                    messages.add(UserMessage.from(turn.userMessage()));
                    // Historical source excerpts are view data, not conversation instructions.
                    messages.add(AiMessage.from(KnowledgeConsultation.isConsultationTurn(context.task(), turn.turnNo())
                            ? "上一轮医院资料咨询已处理。需要核实资料时可以重新查阅。"
                            : turn.assistantMessage()));
                });
        var decisionFacts = new LinkedHashMap<>(context.task().facts());
        decisionFacts.remove(KnowledgeConsultation.FACT_KEY);
        messages.add(UserMessage.from("当前任务状态：" + context.task().status()
                + "\n已收集条件：" + write(decisionFacts)
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

        if (triageOnly) {
            messages.add(UserMessage.from("本轮先讨论就诊方向。请用 ASK_USER 给出初步科室建议或补问关键信息，"
                    + "询问是否按该方向查号。紧急情况请按急症提示用 REPLY。"));
        }
        var toolSpecifications = triageOnly ? List.<ToolSpecification>of() : specifications(tools);
        ToolSpecification profileTool = HospitalKnowledgeQuestion.isProfileQuestion(context.userMessage())
                ? toolSpecifications.stream().filter(tool -> tool.name().equals(KnowledgeConsultation.TOOL_NAME))
                        .findFirst().orElse(null) : null;
        ToolSpecification requiredTool = profileTool;
        for (int attempt = 1; attempt <= MAX_DECISION_ATTEMPTS; attempt++) {
            dev.langchain4j.model.output.Response<AiMessage> response;
            try {
                response = requiredTool == null ? model.generate(messages, toolSpecifications)
                        : model.generate(messages, requiredTool);
            } catch (RuntimeException failure) {
                Throwable cause = failure;
                // LangChain4j can wrap the HTTP error even with its own retries disabled.
                while (cause != null && !(cause instanceof OpenAiHttpException) && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                if (!(cause instanceof OpenAiHttpException error)) throw failure;
                throw new ModelDecisionException(switch (error.code()) {
                    case 401, 403 -> MODEL_AUTH_REJECTED;
                    case 402 -> MODEL_QUOTA_REJECTED;
                    case 408, 429, 500, 502, 503, 504 -> MODEL_TRANSIENT;
                    default -> MODEL_PROVIDER_REJECTED;
                });
            }
            var aiMessage = response.content();
            try {
                if (aiMessage == null) throw new ModelDecisionException(EMPTY_RESPONSE);
                if (aiMessage.hasToolExecutionRequests()) {
                    var requests = aiMessage.toolExecutionRequests();
                    if (requests.stream().anyMatch(request -> toolSpecifications.stream()
                            .noneMatch(tool -> tool.name().equals(request.name())))) {
                        throw new ModelDecisionException(INVALID_TOOL_REQUEST);
                    }
                    // The runtime is sequential: execute one read and let the next step use its result.
                    var request = requests.get(0);
                    if (profileTool != null && !profileTool.name().equals(request.name())) {
                        throw new ModelDecisionException(INVALID_TOOL_REQUEST);
                    }
                    return new AgentDecision.CallReadTool(new ToolCall(request.name(), readObject(request.arguments())));
                }
                if (profileTool != null) throw new ModelDecisionException(INVALID_TOOL_REQUEST);
                AgentDecision decision = parseDecision(aiMessage.text(), context);
                if (toolSpecifications.stream().anyMatch(tool -> tool.name().equals("findAvailableSlots"))
                        && blocksExplicitNearestRequest(decision, context)) {
                    throw new ModelDecisionException(OPTIONAL_PREFERENCE_QUESTION);
                }
                return decision;
            } catch (RuntimeException exception) {
                ModelDecisionException failure = exception instanceof ModelDecisionException decisionException
                        ? decisionException : new ModelDecisionException(INVALID_DECISION);
                if (attempt == MAX_DECISION_ATTEMPTS) throw failure;
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

    private static String additionalGuidance(DecisionContext context, AgentPrompt prompt, boolean triageOnly) {
        String message = context.userMessage();
        if (HospitalKnowledgeQuestion.isConsultation(message) || message.matches("(?s).*(呼吸困难|持续胸痛|意识不清).*")) {
            return "";
        }
        var status = context.task().status();
        boolean selection = (status == TaskStatus.PROPOSAL_READY || status == TaskStatus.AWAITING_CONFIRMATION)
                && message.matches("(?s).*(选号|选|候选|确认|提交|哪个好|区别).*");
        String guidance = triageOnly ? prompt.triage() : selection ? prompt.selection() : "";
        if (guidance.isBlank()) return "";
        return "\n本场景的补充表达指引，仅用于措辞和信息组织。遇到冲突时遵循上面的分诊、工具、预约确认及输出协议。\n"
                + guidance + "\n补充表达指引结束。\n";
    }

    private static boolean needsTriageDirection(String message) {
        if (HospitalKnowledgeQuestion.isConsultation(message)) return false;
        if (message.matches("(?s).*(先看哪个科|先看哪科|挂哪个科|挂哪科|看什么科|挂什么科|就诊方向|分诊).*")) {
            return true;
        }
        return message.matches("(?s).*(咳嗽|咽痛|头痛|头疼|头晕|腹痛|胃痛|胸闷|心悸|皮疹|瘙痒).*")
                && !message.matches("(?s).*(科|门诊|医生|预约|挂号|查号|号源).*");
    }

    private static boolean blocksExplicitNearestRequest(AgentDecision decision, DecisionContext context) {
        if (!(decision instanceof AgentDecision.AskUser question) || !context.toolResults().isEmpty()) return false;
        String message = context.userMessage();
        // An explicit department plus an earliest-slot request is enough to query. The model
        // may incorrectly ask about symptoms as well as optional booking preferences.
        return message.contains("科") && message.matches("(?s).*(最近|最早|尽快|就近).*");
    }

    private List<ToolSpecification> specifications(List<ReadToolSpec> tools) {
        return tools.stream().map(DeepSeekDecisionModel::specification).toList();
    }

    private static String unavailableToolsNotice(List<ReadToolSpec> tools) {
        var available = tools.stream().map(ReadToolSpec::name).collect(java.util.stream.Collectors.toSet());
        var paused = List.of("findAvailableSlots", "getAppointmentRules", KnowledgeConsultation.TOOL_NAME).stream()
                .filter(name -> !available.contains(name)).toList();
        return paused.isEmpty() ? "" : "\n本轮暂不可用的工具：" + String.join("、", paused)
                + "。如果患者需要该工具才能完成的查询，用 REPLY 告知对应查询暂不可用。"
                + "仍须优先处理急症提示，继续遵循原有分诊和预约确认约束。";
    }

    static ToolSpecification specification(ReadToolSpec tool) {
        var builder = ToolSpecification.builder().name(tool.name()).description(tool.description());
        for (var parameter : tool.parameters()) {
            var type = switch (parameter.type()) {
                case STRING -> JsonSchemaProperty.STRING;
                case INTEGER -> JsonSchemaProperty.INTEGER;
                case BOOLEAN -> JsonSchemaProperty.BOOLEAN;
            };
            var description = JsonSchemaProperty.description(parameter.description());
            if (parameter.required()) builder.addParameter(parameter.name(), type, description);
            else builder.addOptionalParameter(parameter.name(), type, description);
        }
        return builder.build();
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
            case "REPLY" -> new AgentDecision.Reply(patientText(requiredText(decision, "message")));
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
