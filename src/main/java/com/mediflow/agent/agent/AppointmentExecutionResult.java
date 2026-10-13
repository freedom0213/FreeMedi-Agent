package com.mediflow.agent.agent;

public record AppointmentExecutionResult(boolean success, String appointmentId, String code, String message) {
    public static AppointmentExecutionResult success(String appointmentId) {
        return new AppointmentExecutionResult(true, appointmentId, null, "预约已创建");
    }

    public static AppointmentExecutionResult failure(String code, String message) {
        return new AppointmentExecutionResult(false, null, code, message);
    }
}
