ALTER TABLE doctor ADD COLUMN code VARCHAR(48) NULL, ADD UNIQUE KEY uq_doctor_code (code);

CREATE TABLE hospital_settings (
    id TINYINT PRIMARY KEY,
    hospital_name VARCHAR(100) NOT NULL DEFAULT '',
    contact VARCHAR(100) NOT NULL DEFAULT '',
    schedule_days INT NOT NULL DEFAULT 90,
    patient_days INT NOT NULL DEFAULT 28,
    cancellation_cutoff TIME NOT NULL DEFAULT '23:00:00',
    check_in_lead INT NOT NULL DEFAULT 30,
    initial_setup_required BOOLEAN NOT NULL DEFAULT FALSE,
    activated_at TIMESTAMP(3) NULL,
    activated_by BIGINT NULL,
    CONSTRAINT fk_hospital_settings_admin FOREIGN KEY (activated_by) REFERENCES sys_user(id)
);

INSERT INTO hospital_settings (id) VALUES (1);
