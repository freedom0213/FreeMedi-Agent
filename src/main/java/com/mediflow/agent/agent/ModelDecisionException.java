package com.mediflow.agent.agent;

/** Safe diagnostic category; never stores raw model responses or provider credentials. */
public final class ModelDecisionException extends RuntimeException {
    public enum Reason {
        EMPTY_RESPONSE, INVALID_JSON, INVALID_DECISION, INVALID_TOOL_REQUEST, UNSOURCED_SLOT,
        OPTIONAL_PREFERENCE_QUESTION, MODEL_TRANSIENT, MODEL_AUTH_REJECTED, MODEL_QUOTA_REJECTED,
        MODEL_PROVIDER_REJECTED
    }

    private final Reason reason;

    public ModelDecisionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
