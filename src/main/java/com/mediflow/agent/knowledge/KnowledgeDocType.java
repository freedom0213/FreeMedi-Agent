package com.mediflow.agent.knowledge;

/**
 * What kind of hospital material a document is.
 *
 * <p>Stable codes are stored in the database; the Chinese wording a patient sees is a presentation
 * concern and lives in the front end, so the codes never have to change when the wording does.
 */
public enum KnowledgeDocType {
    /** 预约规则：放号时间、取消与改约、爽约处理。 */
    APPOINTMENT_RULE,
    /** 就诊须知：证件、到院时间、取号流程。 */
    VISIT_NOTICE,
    /** 科室介绍。 */
    DEPARTMENT_INTRO,
    /** 医生专长介绍，必须带 doctorId。 */
    DOCTOR_PROFILE,
    /** 检查前准备。 */
    PREPARATION;

    public static KnowledgeDocType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("资料类型不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("无法识别的资料类型：" + value
                    + "，可选 " + java.util.Arrays.toString(values()));
        }
    }
}
