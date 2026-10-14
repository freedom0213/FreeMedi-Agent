-- MediFlow Agent schema v1
-- MySQL 8.0+. Review before applying. This file is not executed automatically.
-- Store all DATETIME values in UTC; convert to Asia/Shanghai at API boundaries.

CREATE TABLE sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    role VARCHAR(24) NOT NULL COMMENT 'PATIENT / ADMIN',
    status VARCHAR(24) NOT NULL COMMENT 'ACTIVE / DISABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE department (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(48) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT NULL,
    status VARCHAR(24) NOT NULL COMMENT 'ACTIVE / DISABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_department_code (code),
    KEY idx_department_status_name (status, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE doctor (
    id BIGINT NOT NULL AUTO_INCREMENT,
    department_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    title VARCHAR(80) NULL,
    specialty VARCHAR(500) NULL,
    introduction TEXT NULL,
    status VARCHAR(24) NOT NULL COMMENT 'ACTIVE / DISABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_doctor_department_status (department_id, status),
    CONSTRAINT fk_doctor_department
        FOREIGN KEY (department_id) REFERENCES department (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE doctor_schedule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    doctor_id BIGINT NOT NULL,
    schedule_date DATE NOT NULL,
    clinic_period VARCHAR(24) NOT NULL COMMENT 'MORNING / AFTERNOON / EVENING',
    status VARCHAR(24) NOT NULL COMMENT 'OPEN / CLOSED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_doctor_date_period (doctor_id, schedule_date, clinic_period),
    KEY idx_schedule_date_status (schedule_date, status),
    CONSTRAINT fk_schedule_doctor
        FOREIGN KEY (doctor_id) REFERENCES doctor (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE appointment_slot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT NOT NULL,
    slot_no INT NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    status VARCHAR(24) NOT NULL COMMENT 'AVAILABLE / RESERVED / CLOSED',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_slot_schedule_no (schedule_id, slot_no),
    KEY idx_slot_schedule_status_time (schedule_id, status, start_at),
    CONSTRAINT fk_slot_schedule
        FOREIGN KEY (schedule_id) REFERENCES doctor_schedule (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    patient_id BIGINT NOT NULL,
    title VARCHAR(200) NULL,
    status VARCHAR(24) NOT NULL COMMENT 'ACTIVE / CLOSED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id_patient (id, patient_id),
    KEY idx_session_patient_updated (patient_id, updated_at),
    CONSTRAINT fk_session_patient
        FOREIGN KEY (patient_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_no VARCHAR(64) NOT NULL,
    session_id BIGINT NOT NULL,
    patient_id BIGINT NOT NULL,
    task_type VARCHAR(40) NOT NULL COMMENT 'APPOINTMENT_BOOKING',
    status VARCHAR(32) NOT NULL,
    current_turn_no INT NOT NULL DEFAULT 0,
    step_count INT NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    collected_facts JSON NOT NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_summary VARCHAR(500) NULL,
    started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_task_no (task_no),
    UNIQUE KEY uk_agent_task_id_patient (id, patient_id),
    KEY idx_agent_task_patient_updated (patient_id, updated_at),
    KEY idx_agent_task_session_created (session_id, created_at),
    KEY idx_agent_task_status_updated (status, updated_at),
    CONSTRAINT fk_agent_task_session_patient
        FOREIGN KEY (session_id, patient_id) REFERENCES agent_session (id, patient_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_agent_task_patient
        FOREIGN KEY (patient_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_turn (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    turn_no INT NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    user_message TEXT NOT NULL,
    assistant_message TEXT NULL,
    status VARCHAR(24) NOT NULL COMMENT 'PROCESSING / WAITING_USER / COMPLETED / FAILED',
    model_name VARCHAR(100) NULL,
    prompt_version VARCHAR(64) NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    duration_ms BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_turn_task_no (task_id, turn_no),
    UNIQUE KEY uk_agent_turn_request (task_id, client_request_id),
    UNIQUE KEY uk_agent_turn_id_task (id, task_id),
    CONSTRAINT fk_agent_turn_task
        FOREIGN KEY (task_id) REFERENCES agent_task (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_tool_call (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    call_no INT NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    arguments JSON NOT NULL,
    result_summary JSON NULL,
    status VARCHAR(24) NOT NULL COMMENT 'STARTED / SUCCEEDED / FAILED / REJECTED',
    error_code VARCHAR(64) NULL,
    duration_ms BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_call_turn_no (turn_id, call_no),
    KEY idx_tool_call_task_created (task_id, created_at),
    CONSTRAINT fk_tool_call_task
        FOREIGN KEY (task_id) REFERENCES agent_task (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_tool_call_turn_task
        FOREIGN KEY (turn_id, task_id) REFERENCES agent_turn (id, task_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_no VARCHAR(64) NOT NULL,
    task_id BIGINT NOT NULL,
    patient_id BIGINT NOT NULL,
    version INT NOT NULL,
    department_id BIGINT NOT NULL,
    doctor_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    plan_snapshot JSON NOT NULL,
    plan_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) NOT NULL COMMENT 'PROPOSED / CONFIRMED / EXPIRED / REJECTED / INVALIDATED / EXECUTED',
    expires_at DATETIME(3) NOT NULL,
    confirmed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_plan_no (plan_no),
    UNIQUE KEY uk_agent_plan_task_version (task_id, version),
    UNIQUE KEY uk_agent_plan_id_patient (id, patient_id),
    KEY idx_agent_plan_active (task_id, status, expires_at),
    CONSTRAINT fk_agent_plan_task_patient
        FOREIGN KEY (task_id, patient_id) REFERENCES agent_task (id, patient_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_agent_plan_department
        FOREIGN KEY (department_id) REFERENCES department (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_agent_plan_doctor
        FOREIGN KEY (doctor_id) REFERENCES doctor (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_agent_plan_slot
        FOREIGN KEY (slot_id) REFERENCES appointment_slot (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_state_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    event_no BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NULL,
    actor_type VARCHAR(24) NOT NULL COMMENT 'SYSTEM / USER / MODEL_DECISION',
    actor_user_id BIGINT NULL,
    correlation_id VARCHAR(64) NULL,
    metadata JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_state_event_task_no (task_id, event_no),
    KEY idx_state_event_task_created (task_id, created_at),
    CONSTRAINT fk_state_event_task
        FOREIGN KEY (task_id) REFERENCES agent_task (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_state_event_actor
        FOREIGN KEY (actor_user_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE appointment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    appointment_no VARCHAR(64) NOT NULL,
    patient_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    doctor_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL COMMENT 'BOOKED / CANCELLED',
    idempotency_key VARCHAR(64) NOT NULL,
    confirmed_version INT NOT NULL,
    booked_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_appointment_no (appointment_no),
    UNIQUE KEY uk_appointment_patient_idempotency (patient_id, idempotency_key),
    KEY idx_appointment_patient_created (patient_id, created_at),
    KEY idx_appointment_doctor_created (doctor_id, created_at),
    CONSTRAINT fk_appointment_patient
        FOREIGN KEY (patient_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_department
        FOREIGN KEY (department_id) REFERENCES department (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_doctor
        FOREIGN KEY (doctor_id) REFERENCES doctor (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_slot
        FOREIGN KEY (slot_id) REFERENCES appointment_slot (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_plan_patient
        FOREIGN KEY (plan_id, patient_id) REFERENCES agent_plan (id, patient_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE operation_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operation_no VARCHAR(64) NOT NULL,
    patient_id BIGINT NOT NULL,
    operator_type VARCHAR(24) NOT NULL COMMENT 'PATIENT / ADMIN / SYSTEM',
    operator_id BIGINT NULL,
    operation_type VARCHAR(64) NOT NULL COMMENT 'APPOINTMENT_CREATE',
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT NULL,
    plan_id BIGINT NULL,
    idempotency_key VARCHAR(64) NULL,
    result_status VARCHAR(24) NOT NULL COMMENT 'SUCCEEDED / REJECTED / FAILED',
    result_code VARCHAR(64) NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_operation_audit_no (operation_no),
    KEY idx_operation_audit_patient_created (patient_id, created_at),
    KEY idx_operation_audit_target (target_type, target_id),
    CONSTRAINT fk_operation_audit_patient
        FOREIGN KEY (patient_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_operation_audit_operator
        FOREIGN KEY (operator_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_operation_audit_plan
        FOREIGN KEY (plan_id) REFERENCES agent_plan (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
