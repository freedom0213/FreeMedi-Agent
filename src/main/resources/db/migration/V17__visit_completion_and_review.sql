ALTER TABLE appointment ADD COLUMN visited_at DATETIME(3) NULL;

ALTER TABLE admin_grant DROP CHECK ck_admin_grant_scope,
    ADD CONSTRAINT ck_admin_grant_scope CHECK (
        (role_code IN ('SCHEDULER', 'TRIAGE', 'VISIT_SUPERVISOR') AND department_id IS NOT NULL)
        OR (role_code IN ('SUPER_ADMIN', 'SYSTEM_ADMIN', 'KNOWLEDGE') AND department_id IS NULL)
    );

CREATE TABLE appointment_visit_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id BIGINT NOT NULL,
    audit_operation_no VARCHAR(64) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    reason VARCHAR(400) NOT NULL,
    reviewed_at DATETIME(3) NOT NULL,
    KEY idx_visit_review_appointment (appointment_id, id),
    UNIQUE KEY uq_visit_review_operation (audit_operation_no),
    CONSTRAINT fk_visit_review_appointment FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    CONSTRAINT fk_visit_review_operation FOREIGN KEY (audit_operation_no) REFERENCES operation_audit_log(operation_no),
    CONSTRAINT ck_visit_review_outcome CHECK (outcome IN ('CONFIRMED', 'UNCONFIRMED'))
);
