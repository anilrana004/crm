-- =====================================================================
-- SecureTravels CRM — Module 1 (Lead Management full build)
-- 1. Leads now carry an optional link to the canonical customer360 record
--    (a "returning customer / new trip interest"). The link is written at
--    lead creation when the normalized phone matches an existing Customer360.
-- 2. Lost-reason taxonomy updated to the six business-defined reasons.
--    Old values are remapped in place so existing rows stay valid.
-- =====================================================================

ALTER TABLE leads
    ADD COLUMN customer360_id uuid REFERENCES customer360(id);

CREATE INDEX idx_leads_customer360 ON leads(customer360_id);

-- Drop the V1 inline check (auto-named leads_lost_reason_check) and replace
-- it with the Module-1 taxonomy.
ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_lost_reason_check;

UPDATE leads SET lost_reason = CASE lost_reason
    WHEN 'BUDGET'             THEN 'PRICE_TOO_HIGH'
    WHEN 'TIMING_DATES'       THEN 'DATES_UNAVAILABLE'
    WHEN 'ALREADY_BOOKED'     THEN 'CHOSE_COMPETITOR'
    WHEN 'NO_RESPONSE'        THEN 'WENT_SILENT'
    WHEN 'SERVICE_COMPARISON' THEN 'CHOSE_COMPETITOR'
    WHEN 'OTHER'              THEN 'POSTPONED'
    ELSE lost_reason
END
WHERE lost_reason IS NOT NULL;

ALTER TABLE leads
    ADD CONSTRAINT chk_leads_lost_reason CHECK (lost_reason IN
        ('PRICE_TOO_HIGH', 'DATES_UNAVAILABLE', 'CHOSE_COMPETITOR',
         'WENT_SILENT', 'NOT_GENUINE', 'POSTPONED'));

-- Monotonic insertion key for audit activity timelines (created_at alone is
-- not unique within a request because timestamps are transaction-scoped).
ALTER TABLE audit_log ADD COLUMN seq bigserial;
CREATE UNIQUE INDEX idx_audit_seq ON audit_log(seq);