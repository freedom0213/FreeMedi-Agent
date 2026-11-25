ALTER TABLE appointment
    MODIFY COLUMN status VARCHAR(24) NOT NULL COMMENT 'BOOKED / CHECKED_IN / CANCELLED / RESCHEDULED',
    ADD COLUMN rescheduled_from_id BIGINT NULL,
    ADD UNIQUE KEY uk_appointment_rescheduled_from (rescheduled_from_id),
    ADD CONSTRAINT fk_appointment_rescheduled_from FOREIGN KEY (rescheduled_from_id) REFERENCES appointment (id);

ALTER TABLE operation_audit_log
    MODIFY COLUMN operation_type VARCHAR(64) NOT NULL
        COMMENT 'APPOINTMENT_CREATE / APPOINTMENT_CANCEL / APPOINTMENT_CHECK_IN / APPOINTMENT_RESCHEDULE',
    ADD KEY idx_operation_audit_intent (patient_id, operation_type, idempotency_key);
