-- Synthetic near-term slots keep the local demo useful without touching existing bookings.
INSERT INTO doctor_schedule (id, doctor_id, schedule_date, clinic_period, status)
SELECT 10000 + dates.day_offset * 100 + (doctor.id - 3001) * 2 + periods.period_offset,
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
WHERE doctor.id BETWEEN 3001 AND 3005
  AND doctor.status = 'ACTIVE';

INSERT INTO appointment_slot (id, schedule_id, slot_no, start_at, end_at, status)
SELECT 20000 + dates.day_offset * 1000 + (doctor.id - 3001) * 20
           + periods.period_offset * 2 + slots.slot_no,
       10000 + dates.day_offset * 100 + (doctor.id - 3001) * 2 + periods.period_offset,
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
WHERE doctor.id BETWEEN 3001 AND 3005
  AND doctor.status = 'ACTIVE';
