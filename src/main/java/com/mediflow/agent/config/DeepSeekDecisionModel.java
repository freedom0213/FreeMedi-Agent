package com.mediflow.agent.config;

import java.time.Instant;
import java.time.LocalDate;
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
            你是 MediFlow 预约编排 Agent。只能根据用户消息、任务状态和工具结果提出下一步决策。
            只可调用提供的只读工具；不得声称已经预约成功，不得执行或要求执行写操作。
            日期必须使用 yyyy-MM-dd，时段只能是 MORNING、AFTERNOON、EVENING。
            如果缺少查询号源所需信息，返回 ASK_USER；信息充足时选择 findAvailableSlots。
            工具结果为空时可追问日期、科室或时段，不得捏造医生、号源或结果。
            如果工具结果中有号源，可提出其中一个候选号源形成方案，并等待用户在界面明确确认。
            除工具调用外，严格返回单个 JSON 对象，不要 Markdown：
            {"type":"ASK_USER","question":"...","missingFields":["appointmentDate"]}
            {"type":"PROPOSE_PLAN","slotId":1,"departmentId":1,"doctorId":1,
             "appointmentDate":"2027-01-15","timeRange":"14:00-14:30","message":"..."}
            {"type":"FINISH","message":"..."}
            missingFields 只允许 departmentId、doctorId、appointmentDate、clinicPeriod。
            """;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final ChatLanguageModel model;
    private final ObjectMapper json;

    public DeepSeekDecisionModel(ChatLanguageModel model, ObjectMapper json) {
        this.model = model;
        this.json = json;
    }

    @Override
    public AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
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

        var response = model.generate(messages, specifications(tools));
        var aiMessage = response.content();
        if (aiMessage == null) {
            throw new IllegalStateException("DeepSeek returned an empty response");
        }
        if (aiMessage.hasToolExecutionRequests()) {
            var request = aiMessage.toolExecutionRequests().get(0);
            if (aiMessage.toolExecutionRequests().size() != 1) {
                throw new IllegalArgumentException("parallel tool calls are not supported");
            }
            Map<String, Object> arguments = readObject(request.arguments());
            return new AgentDecision.CallReadTool(new ToolCall(request.name(), arguments));
        }
        return parseDecision(aiMessage.text(), context.task().patientId());
    }

    private List<ToolSpecification> specifications(List<ReadToolSpec> tools) {
        List<ToolSpecification> result = new ArrayList<>();
        for (ReadToolSpec tool : tools) {
            if ("findAvailableSlots".equals(tool.name())) {
                result.add(ToolSpecification.builder().name(tool.name()).description(tool.description())
                        .addOptionalParameter("departmentId", JsonSchemaProperty.INTEGER,
                                JsonSchemaProperty.description("科室 ID"))
                        .addOptionalParameter("doctorId", JsonSchemaProperty.INTEGER,
                                JsonSchemaProperty.description("医生 ID"))
                        .addOptionalParameter("appointmentDate", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("预约日期，格式 yyyy-MM-dd"))
                        .addOptionalParameter("clinicPeriod", JsonSchemaProperty.STRING,
                                JsonSchemaProperty.description("MORNING、AFTERNOON 或 EVENING"),
                                JsonSchemaProperty.enums("MORNING", "AFTERNOON", "EVENING"))
                        .addOptionalParameter("limit", JsonSchemaProperty.INTEGER,
                                JsonSchemaProperty.description("最多返回数量，不超过 50"))
                        .build());
            } else if ("getAppointmentRules".equals(tool.name())) {
                result.add(ToolSpecification.builder().name(tool.name()).description(tool.description()).build());
            }
        }
        return List.copyOf(result);
    }

    private AgentDecision parseDecision(String content, long patientId) {
        Map<String, Object> decision = readObject(content);
        String type = requiredText(decision, "type");
        return switch (type) {
            case "ASK_USER" -> new AgentDecision.AskUser(requiredText(decision, "question"),
                    missingFields(decision.get("missingFields")));
            case "PROPOSE_PLAN" -> {
                long departmentId = positiveLong(decision, "departmentId");
                long doctorId = positiveLong(decision, "doctorId");
                long slotId = positiveLong(decision, "slotId");
                LocalDate date = LocalDate.parse(requiredText(decision, "appointmentDate"));
                String timeRange = requiredText(decision, "timeRange");
                String message = requiredText(decision, "message");
                yield new AgentDecision.ProposePlan(new PlanDraft(patientId, departmentId, doctorId, slotId,
                        date, timeRange, Instant.now().plusSeconds(600)), message);
            }
            case "FINISH" -> new AgentDecision.Finish(requiredText(decision, "message"));
            default -> throw new IllegalArgumentException("unsupported decision type");
        };
    }

    private Set<String> missingFields(Object value) {
        if (!(value instanceof List<?> fields)) {
            return Set.of();
        }
        Set<String> allowed = Set.of("departmentId", "doctorId", "appointmentDate", "clinicPeriod");
        return fields.stream().map(String::valueOf).filter(allowed::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Map<String, Object> readObject(String value) {
        try {
            return json.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("DeepSeek returned invalid JSON");
        }
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
