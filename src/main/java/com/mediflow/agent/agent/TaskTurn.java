package com.mediflow.agent.agent;

/** A user-visible conversation turn; it does not contain model reasoning or credentials. */
public record TaskTurn(int turnNo, String clientRequestId, String userMessage,
                       String assistantMessage, String status) {
}
