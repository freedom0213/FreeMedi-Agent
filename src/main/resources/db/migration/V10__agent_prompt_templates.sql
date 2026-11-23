CREATE TABLE agent_prompt_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    triage_guidance VARCHAR(400) NOT NULL,
    selection_guidance VARCHAR(400) NOT NULL,
    note VARCHAR(160) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    trial_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    trial_passed BOOLEAN NULL,
    trial_report JSON NULL,
    tested_at DATETIME(3) NULL,
    FOREIGN KEY (created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_prompt_settings (
    id INT NOT NULL PRIMARY KEY,
    active_revision_id BIGINT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    activated_at DATETIME(3) NULL,
    FOREIGN KEY (active_revision_id) REFERENCES agent_prompt_revision(id)
) ENGINE=InnoDB;

INSERT INTO agent_prompt_settings (id) VALUES (1);
