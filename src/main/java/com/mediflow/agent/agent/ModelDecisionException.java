package com.mediflow.agent.agent;

/** Safe diagnostic category; never stores raw model responses or provider credentials. */
public final class ModelDecisionException extends RuntimeException {
    public enum Reason { EMPTY_RESPONSE, INVALID_JSON, INVALID_DECISION, INVALID_TOOL_REQUEST, UNSOURCED_SLOT, OPTIONAL_PREFERENCE_QUESTION }

    private final Reason reason;

    public ModelDecisionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
