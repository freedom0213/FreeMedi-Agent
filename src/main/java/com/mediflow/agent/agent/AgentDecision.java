package com.mediflow.agent.agent;

import java.util.Set;

public sealed interface AgentDecision
        permits AgentDecision.AskUser, AgentDecision.CallReadTool,
        AgentDecision.ProposePlan, AgentDecision.Finish {

    record AskUser(String question, Set<String> missingFields, TriageRecommendation recommendation) implements AgentDecision {
        public AskUser(String question, Set<String> missingFields) {
            this(question, missingFields, null);
        }
        public AskUser {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question must not be blank");
            }
            missingFields = missingFields == null ? Set.of() : Set.copyOf(missingFields);
        }
    }

    record CallReadTool(ToolCall call) implements AgentDecision {
        public CallReadTool {
            if (call == null) {
                throw new IllegalArgumentException("tool call must be present");
            }
        }
    }

    record ProposePlan(PlanDraft draft, String message, TriageRecommendation recommendation) implements AgentDecision {
        public ProposePlan(PlanDraft draft, String message) {
            this(draft, message, null);
        }
        public ProposePlan {
            if (draft == null || message == null || message.isBlank()) {
                throw new IllegalArgumentException("plan and message must be present");
            }
        }
    }

    record Finish(String message) implements AgentDecision {
        public Finish {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("finish message must not be blank");
            }
        }
    }
}
