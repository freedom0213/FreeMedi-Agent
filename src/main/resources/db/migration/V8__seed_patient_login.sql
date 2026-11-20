-- Local synthetic accounts. Keep the original patient ID and any existing appointment/task ownership.
UPDATE sys_user
SET password_hash = '$2b$12$NQiEh7Kh9DHTP0Jaez8hCOietz0dTz3PjIEBFWf.QFOUIXb8HYVue'
WHERE id = 1001 AND username = 'demo-patient' AND role = 'PATIENT'
  AND password_hash = 'synthetic-demo-password';

INSERT INTO sys_user (username, password_hash, display_name, role, status)
VALUES ('demo-patient-b', '$2b$12$mJU51eLmGoYhBOFr5pQ.yODg0iSff8xzLv4yMMNgfGab41FUMUqTq',
        '演示患者乙', 'PATIENT', 'ACTIVE');
