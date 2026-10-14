-- Synthetic demo data only. No real patients, clinicians, or medical records are used.
INSERT INTO sys_user (id, username, password_hash, display_name, role, status)
VALUES
    (1001, 'demo-patient', 'synthetic-demo-password', '演示患者', 'PATIENT', 'ACTIVE'),
    (1002, 'demo-admin', 'synthetic-demo-password', '演示管理员', 'ADMIN', 'ACTIVE');

INSERT INTO department (id, code, name, description, status)
VALUES
    (2001, 'CARDIOLOGY', '心内科', '模拟心血管相关门诊', 'ACTIVE'),
    (2002, 'RESPIRATORY', '呼吸内科', '模拟呼吸系统相关门诊', 'ACTIVE'),
    (2003, 'DERMATOLOGY', '皮肤科', '模拟皮肤相关门诊', 'ACTIVE'),
    (2004, 'GENERAL', '全科医学科', '模拟常见就诊服务', 'ACTIVE');

INSERT INTO doctor (id, department_id, name, title, specialty, introduction, status)
VALUES
    (3001, 2001, '林医生', '主任医师', '心血管常见病', '演示用医生资料', 'ACTIVE'),
    (3002, 2001, '周医生', '副主任医师', '心律相关问题', '演示用医生资料', 'ACTIVE'),
    (3003, 2002, '陈医生', '主任医师', '呼吸系统常见病', '演示用医生资料', 'ACTIVE'),
    (3004, 2003, '王医生', '副主任医师', '常见皮肤问题', '演示用医生资料', 'ACTIVE'),
    (3005, 2004, '赵医生', '主治医师', '常见健康问题', '演示用医生资料', 'ACTIVE');

INSERT INTO doctor_schedule (id, doctor_id, schedule_date, clinic_period, status)
VALUES
    (4001, 3001, '2027-01-15', 'AFTERNOON', 'OPEN'),
    (4002, 3002, '2027-01-15', 'AFTERNOON', 'OPEN'),
    (4003, 3003, '2027-01-16', 'MORNING', 'OPEN'),
    (4004, 3003, '2027-01-16', 'AFTERNOON', 'OPEN'),
    (4005, 3004, '2027-01-17', 'MORNING', 'OPEN'),
    (4006, 3005, '2027-01-18', 'AFTERNOON', 'OPEN');

INSERT INTO appointment_slot (id, schedule_id, slot_no, start_at, end_at, status)
VALUES
    (5001, 4001, 1, '2027-01-15 06:00:00.000', '2027-01-15 06:30:00.000', 'AVAILABLE'),
    (5002, 4001, 2, '2027-01-15 06:30:00.000', '2027-01-15 07:00:00.000', 'AVAILABLE'),
    (5003, 4002, 1, '2027-01-15 06:00:00.000', '2027-01-15 06:30:00.000', 'AVAILABLE'),
    (5004, 4002, 2, '2027-01-15 06:30:00.000', '2027-01-15 07:00:00.000', 'AVAILABLE'),
    (5005, 4003, 1, '2027-01-16 01:00:00.000', '2027-01-16 01:30:00.000', 'AVAILABLE'),
    (5006, 4003, 2, '2027-01-16 01:30:00.000', '2027-01-16 02:00:00.000', 'AVAILABLE'),
    (5007, 4004, 1, '2027-01-16 06:00:00.000', '2027-01-16 06:30:00.000', 'AVAILABLE'),
    (5008, 4004, 2, '2027-01-16 06:30:00.000', '2027-01-16 07:00:00.000', 'AVAILABLE'),
    (5009, 4005, 1, '2027-01-17 01:00:00.000', '2027-01-17 01:30:00.000', 'AVAILABLE'),
    (5010, 4005, 2, '2027-01-17 01:30:00.000', '2027-01-17 02:00:00.000', 'AVAILABLE'),
    (5011, 4006, 1, '2027-01-18 06:00:00.000', '2027-01-18 06:30:00.000', 'AVAILABLE'),
    (5012, 4006, 2, '2027-01-18 06:30:00.000', '2027-01-18 07:00:00.000', 'AVAILABLE');
