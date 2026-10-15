-- Synthetic departments and clinicians for common headache and abdominal-pain demos.
INSERT INTO department (id, code, name, description, status)
VALUES
    (2005, 'NEUROLOGY', '神经内科', '模拟头痛及常见神经系统相关门诊', 'ACTIVE'),
    (2006, 'GASTROENTEROLOGY', '消化内科', '模拟腹痛及常见消化系统相关门诊', 'ACTIVE');

INSERT INTO doctor (id, department_id, name, title, specialty, introduction, status)
VALUES
    (3006, 2005, '刘医生', '主治医师', '头痛及常见神经系统问题', '合成演示医生资料', 'ACTIVE'),
    (3007, 2006, '孙医生', '主治医师', '腹痛及常见消化系统问题', '合成演示医生资料', 'ACTIVE');

INSERT INTO doctor_schedule (id, doctor_id, schedule_date, clinic_period, status)
SELECT 12000 + dates.day_offset * 100 + (doctor.id - 3006) * 10 + periods.period_offset,
       doctor.id,
       DATE_ADD(CURRENT_DATE(), INTERVAL dates.day_offset DAY),
       periods.clinic_period,
       'OPEN'
FROM doctor
CROSS JOIN (
    SELECT 1 AS day_offset UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
    UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
    UNION ALL SELECT 13 UNION ALL SELECT 14
) dates
CROSS JOIN (
    SELECT 0 AS period_offset, 'MORNING' AS clinic_period
    UNION ALL SELECT 1, 'AFTERNOON'
) periods
WHERE doctor.id BETWEEN 3006 AND 3007
  AND doctor.status = 'ACTIVE';

INSERT INTO appointment_slot (id, schedule_id, slot_no, start_at, end_at, status)
SELECT 40000 + dates.day_offset * 1000 + (doctor.id - 3006) * 20
           + periods.period_offset * 2 + slots.slot_no,
       12000 + dates.day_offset * 100 + (doctor.id - 3006) * 10 + periods.period_offset,
       slots.slot_no,
       TIMESTAMP(DATE_ADD(CURRENT_DATE(), INTERVAL dates.day_offset DAY),
                 CASE periods.clinic_period
                     WHEN 'MORNING' THEN IF(slots.slot_no = 1, '01:00:00', '01:30:00')
                     ELSE IF(slots.slot_no = 1, '06:00:00', '06:30:00')
                 END),
       TIMESTAMP(DATE_ADD(CURRENT_DATE(), INTERVAL dates.day_offset DAY),
                 CASE periods.clinic_period
                     WHEN 'MORNING' THEN IF(slots.slot_no = 1, '01:30:00', '02:00:00')
                     ELSE IF(slots.slot_no = 1, '06:30:00', '07:00:00')
                 END),
       'AVAILABLE'
FROM doctor
CROSS JOIN (
    SELECT 1 AS day_offset UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
    UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
    UNION ALL SELECT 13 UNION ALL SELECT 14
) dates
CROSS JOIN (
    SELECT 0 AS period_offset, 'MORNING' AS clinic_period
    UNION ALL SELECT 1, 'AFTERNOON'
) periods
CROSS JOIN (SELECT 1 AS slot_no UNION ALL SELECT 2) slots
WHERE doctor.id BETWEEN 3006 AND 3007
  AND doctor.status = 'ACTIVE';
