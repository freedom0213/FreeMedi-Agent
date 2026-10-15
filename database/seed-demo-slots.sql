-- Reusable demo slot seeder for the synthetic hospital.
--
-- Why this file exists: V4 and V5 generate "today + 1..14 days" using CURRENT_DATE(), but a
-- Flyway migration runs exactly once. Once that window has passed the demo has no bookable
-- slots left, and re-running the migration is impossible (checksum and version history).
-- This script can be executed at any time instead.
--
-- It is idempotent. Repeated runs rely on the existing unique keys:
--   doctor_schedule (doctor_id, schedule_date, clinic_period)
--   appointment_slot (schedule_id, slot_no)
-- so ON DUPLICATE KEY UPDATE id = id is a deliberate no-op: existing rows, including ones an
-- administrator closed or a patient already booked, are never overwritten.
--
-- Slot times are stored in UTC, matching V4/V5 and the application's SET time_zone = '+00:00'.
-- Morning 01:00 UTC is 09:00 Asia/Shanghai and afternoon 06:00 UTC is 14:00 Asia/Shanghai.
--
-- Run it through scripts/seed-demo-slots.ps1. @days is set to 14 when the caller does not set it.

SET @days = IFNULL(@days, 14);
SET @days = LEAST(GREATEST(@days, 1), 30);
SET @last_offset = @days - 1;

-- Open MORNING and AFTERNOON clinics for every active doctor across the window.
INSERT INTO doctor_schedule (doctor_id, schedule_date, clinic_period, status)
SELECT d.id, DATE_ADD(CURRENT_DATE(), INTERVAL dates.day_offset DAY), periods.clinic_period, 'OPEN'
FROM doctor d
CROSS JOIN (
    SELECT 0 AS day_offset UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
    UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14
    UNION ALL SELECT 15 UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
    UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23 UNION ALL SELECT 24
    UNION ALL SELECT 25 UNION ALL SELECT 26 UNION ALL SELECT 27 UNION ALL SELECT 28 UNION ALL SELECT 29
) dates
CROSS JOIN (
    SELECT 'MORNING' AS clinic_period UNION ALL SELECT 'AFTERNOON'
) periods
WHERE d.status = 'ACTIVE'
  AND dates.day_offset < @days
ON DUPLICATE KEY UPDATE doctor_schedule.id = doctor_schedule.id;

-- Two bookable slots per open clinic, mirroring the V4/V5 slot layout.
INSERT INTO appointment_slot (schedule_id, slot_no, start_at, end_at, status)
SELECT s.id, slots.slot_no,
       TIMESTAMP(s.schedule_date, IF(slots.slot_no = 1, times.first_start, times.first_end)),
       TIMESTAMP(s.schedule_date, IF(slots.slot_no = 1, times.first_end, times.second_end)),
       'AVAILABLE'
FROM doctor_schedule s
JOIN doctor d ON d.id = s.doctor_id AND d.status = 'ACTIVE'
JOIN (
    SELECT 'MORNING' AS clinic_period, '01:00:00' AS first_start,
           '01:30:00' AS first_end, '02:00:00' AS second_end
    UNION ALL SELECT 'AFTERNOON', '06:00:00', '06:30:00', '07:00:00'
    UNION ALL SELECT 'EVENING', '11:00:00', '11:30:00', '12:00:00'
) times ON times.clinic_period = s.clinic_period
CROSS JOIN (SELECT 1 AS slot_no UNION ALL SELECT 2) slots
WHERE s.status = 'OPEN'
  AND s.schedule_date BETWEEN CURRENT_DATE() AND DATE_ADD(CURRENT_DATE(), INTERVAL @last_offset DAY)
ON DUPLICATE KEY UPDATE appointment_slot.id = appointment_slot.id;

-- Coverage report. Counts are ASCII only so the output stays readable in any console encoding.
SELECT COUNT(DISTINCT schedule_date) AS covered_days,
       COUNT(*) AS open_clinics,
       MIN(schedule_date) AS first_date,
       MAX(schedule_date) AS last_date
FROM doctor_schedule
WHERE status = 'OPEN' AND schedule_date >= CURRENT_DATE();

SELECT COUNT(*) AS bookable_slots
FROM appointment_slot slot
JOIN doctor_schedule s ON s.id = slot.schedule_id
WHERE slot.status = 'AVAILABLE' AND slot.start_at > UTC_TIMESTAMP();
