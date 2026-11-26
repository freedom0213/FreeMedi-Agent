CREATE TABLE admin_grant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admin_id BIGINT NOT NULL,
    role_code VARCHAR(24) NOT NULL,
    department_id BIGINT NULL,
    scope_key BIGINT GENERATED ALWAYS AS (COALESCE(department_id, 0)) STORED,
    UNIQUE KEY uk_admin_grant (admin_id, role_code, scope_key),
    CONSTRAINT fk_admin_grant_user FOREIGN KEY (admin_id) REFERENCES sys_user (id),
    CONSTRAINT fk_admin_grant_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT ck_admin_grant_scope CHECK (
        (role_code IN ('SCHEDULER', 'TRIAGE') AND department_id IS NOT NULL)
        OR (role_code IN ('SUPER_ADMIN', 'SYSTEM_ADMIN', 'KNOWLEDGE') AND department_id IS NULL)
    )
);

INSERT INTO admin_grant (admin_id, role_code)
SELECT id, 'SUPER_ADMIN' FROM sys_user WHERE role = 'ADMIN';

CREATE TABLE admin_grant_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NOT NULL,
    staff_id BIGINT NOT NULL,
    grants_before JSON NOT NULL,
    grants_after JSON NOT NULL,
    occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_admin_event_actor FOREIGN KEY (operator_id) REFERENCES sys_user (id),
    CONSTRAINT fk_admin_event_staff FOREIGN KEY (staff_id) REFERENCES sys_user (id)
);

CREATE TABLE schedule_interruption (
    schedule_id BIGINT PRIMARY KEY,
    request_id CHAR(36) NOT NULL,
    preview_token CHAR(64) NOT NULL,
    public_reason VARCHAR(240) NOT NULL,
    affected_count INT NOT NULL,
    operator_id BIGINT NOT NULL,
    occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_interruption_schedule FOREIGN KEY (schedule_id) REFERENCES doctor_schedule (id),
    CONSTRAINT fk_interruption_operator FOREIGN KEY (operator_id) REFERENCES sys_user (id)
);

CREATE TABLE interrupted_appointment (
    appointment_id BIGINT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    outcome VARCHAR(24) NULL,
    arrangement VARCHAR(400) NULL,
    arranged_by BIGINT NULL,
    arranged_at DATETIME(3) NULL,
    KEY idx_interrupted_schedule (schedule_id),
    CONSTRAINT fk_interrupted_appointment FOREIGN KEY (appointment_id) REFERENCES appointment (id),
    CONSTRAINT fk_interrupted_schedule FOREIGN KEY (schedule_id) REFERENCES schedule_interruption (schedule_id),
    CONSTRAINT fk_interrupted_arranger FOREIGN KEY (arranged_by) REFERENCES sys_user (id)
);
