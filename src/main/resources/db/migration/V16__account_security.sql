ALTER TABLE sys_user
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN temporary_password_expires_at DATETIME(3) NULL;

CREATE TABLE account_security_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_account_security_events (account_id, id),
    CONSTRAINT fk_account_security_event_account FOREIGN KEY (account_id) REFERENCES sys_user(id),
    CONSTRAINT fk_account_security_event_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id)
);
