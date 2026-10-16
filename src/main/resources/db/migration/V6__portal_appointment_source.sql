-- Portal bookings have no agent plan. Rather than inventing an agent task, session and plan for
-- every ordinary registration, allow appointment.plan_id to be null and record where the booking
-- came from. operation_audit_log.plan_id is already nullable, so this aligns appointment with a
-- capability the original design had already reserved.
--
-- The (plan_id, patient_id) foreign key is kept: MySQL skips a foreign key check when any column
-- of the key is null, so portal rows remain valid without a plan.
ALTER TABLE appointment MODIFY COLUMN plan_id BIGINT NULL;

ALTER TABLE appointment
    ADD COLUMN booked_via VARCHAR(24) NOT NULL DEFAULT 'AGENT' COMMENT 'AGENT / PORTAL' AFTER plan_id;
