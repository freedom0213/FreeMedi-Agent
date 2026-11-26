CREATE TABLE appointment_notice_receipt (
    appointment_id BIGINT NOT NULL,
    notice_kind VARCHAR(20) NOT NULL,
    read_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (appointment_id, notice_kind),
    CONSTRAINT fk_notice_receipt_interruption FOREIGN KEY (appointment_id)
        REFERENCES interrupted_appointment (appointment_id),
    CONSTRAINT ck_appointment_notice_kind CHECK (notice_kind IN ('STOPPED', 'ARRANGEMENT'))
);
