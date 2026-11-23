CREATE TABLE agent_model_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    profile_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INT NOT NULL,
    label VARCHAR(100) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(150) NOT NULL,
    encrypted_key VARBINARY(4096) NOT NULL,
    probe_code VARCHAR(40) NULL,
    probe_ms BIGINT NULL,
    tested_at DATETIME(3) NULL,
    UNIQUE KEY uk_agent_model_revision (profile_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_runtime_settings (
    id INT NOT NULL PRIMARY KEY,
    active_model_id BIGINT NULL,
    activated_at DATETIME(3) NULL,
    model_timeout_ms BIGINT NULL,
    tool_timeout_ms BIGINT NULL,
    request_timeout_ms BIGINT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (active_model_id) REFERENCES agent_model_revision(id)
) ENGINE=InnoDB;

INSERT INTO agent_runtime_settings (id) VALUES (1);

CREATE TABLE agent_tool_settings (
    tool_name VARCHAR(80) NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    timeout_ms BIGINT NULL,
    revision BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (updated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB;

CREATE TABLE agent_settings_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    admin_id BIGINT NOT NULL,
    action VARCHAR(30) NOT NULL,
    target VARCHAR(100) NOT NULL,
    details VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (admin_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
