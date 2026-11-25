package com.mediflow.agent.agent;

public record AppointmentVerificationResult(boolean success, String message, String code) {
    public AppointmentVerificationResult(boolean success, String message) {
        this(success, message, null);
    }
}
