package com.mediflow.agent.agent;

public record AppointmentExecutionResult(boolean success, String appointmentId, String code, String message) {
    public static AppointmentExecutionResult success(String appointmentId) {
        return new AppointmentExecutionResult(true, appointmentId, null, "预约已创建");
    }

    public static AppointmentExecutionResult existing(String appointmentId) {
        return new AppointmentExecutionResult(true, appointmentId, null, "预约已存在，已返回原预约");
    }

    public static AppointmentExecutionResult failure(String code, String message) {
        return new AppointmentExecutionResult(false, null, code, message);
    }
}
