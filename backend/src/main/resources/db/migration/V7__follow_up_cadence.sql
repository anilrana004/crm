-- =====================================================================
-- SecureTravels CRM — Module 2 (Follow-up automation cadence)
-- Product decision (spec §19.2, 2026-09-11): cumulative day-offsets
-- Day+1, Day+3, Day+8, Day+15. Rename prior types and re-apply the
-- CHECK constraint that lists TYPE values.
-- =====================================================================

ALTER TABLE tasks DROP CONSTRAINT tasks_type_check;

UPDATE tasks SET type = CASE
    WHEN type = 'FOLLOW_UP_2D' THEN 'FOLLOW_UP_3D'
    WHEN type = 'FOLLOW_UP_5D' THEN 'FOLLOW_UP_8D'
    WHEN type = 'FOLLOW_UP_7D' THEN 'FOLLOW_UP_15D'
    ELSE type
END
WHERE type IN ('FOLLOW_UP_2D', 'FOLLOW_UP_5D', 'FOLLOW_UP_7D');

ALTER TABLE tasks ADD CONSTRAINT tasks_type_check CHECK (type IN
    ('INITIAL_CALL', 'FOLLOW_UP_1D', 'FOLLOW_UP_3D', 'FOLLOW_UP_8D', 'FOLLOW_UP_15D',
     'QUOTATION', 'PAYMENT_REMINDER', 'OPS', 'REVIEW', 'CUSTOM'));