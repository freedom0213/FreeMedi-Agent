package com.mediflow.agent.agent.runtime;

import com.mediflow.agent.agent.task.TaskSnapshot;
import com.mediflow.agent.agent.tool.ToolResult;
import java.util.List;
import java.util.Map;

/** Patient-ready read result. Sources come only from the knowledge executor. */
public record KnowledgeConsultation(String status, String message, List<?> sources) {
    public static final String TOOL_NAME = "searchHospitalKnowledge";
    public static final String FACT_KEY = "knowledgeReplies";

    public static KnowledgeConsultation from(ToolResult result) {
        if (!result.success()) {
            if ("INVALID_TOOL_ARGUMENT".equals(result.errorCode())) {
                return new KnowledgeConsultation("INVALID_QUESTION", "请用简短的话补充想查询的就诊资料。", List.of());
            }
            return new KnowledgeConsultation("UNAVAILABLE",
                    "医院资料库暂时无法查询，请稍后再试。你也可以向门诊服务台核实。", List.of());
        }
        if (result.data().get("chunks") instanceof List<?> evidence && !evidence.isEmpty()
                && result.data().get("answer") instanceof String answer && !answer.isBlank()) {
            return new KnowledgeConsultation("ANSWERED", answer, evidence);
        }
        return new KnowledgeConsultation("NO_EVIDENCE",
                "已查阅现有医院资料，里面没有找到能够回答这个问题的内容。请向门诊服务台核实。", List.of());
    }

    Map<String, Object> toFact() {
        return Map.of("status", status, "sources", sources);
    }

    public static boolean isReply(AgentTurnResult result) {
        return isConsultationTurn(result.task(), result.task().turnCount());
    }

    public static boolean isConsultationTurn(TaskSnapshot task, int turnNo) {
        return task.facts().get(FACT_KEY) instanceof Map<?, ?> replies
                && replies.containsKey(Integer.toString(turnNo));
    }
}
