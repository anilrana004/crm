-- =============================================================================
-- Phase 5, Module 1 — Consent & Preference Management (and the send-gate that
-- enforces it). Update numbering continues from V13.
--
-- Three building blocks land here:
--   1) consent_records           append-only history of (customer, channel,
--                                purpose, status) changes. Effective status is
--                                the latest row; UNKNOWN is the default until a
--                                GRANTED/REVOKED row exists.
--   2) consent_suppressions      opt-out lines for numbers with no customer360
--                                yet ("STOP" before they ever talk to us). Once
--                                a customer exists, the latest consent_records
--                                row wins over a suppression (explicit re-grant
--                                beats a stale STOP).
--   3) template policy columns   whatsapp_templates.category (TRANSACTIONAL /
--                                MARKETING / OTP) + approval_status. The send
--                                gate derives a send's purpose from the template
--                                category so a caller cannot relabel a promotion
--                                as "transactional".
-- =============================================================================

CREATE TABLE consent_records (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   uuid NOT NULL REFERENCES customer360(id),
    channel       varchar(20) NOT NULL,
    purpose       varchar(20) NOT NULL,
    status        varchar(20) NOT NULL,
    source        varchar(30) NOT NULL,
    occurred_at   timestamptz NOT NULL DEFAULT now(),
    evidence_ref  varchar(255),
    created_by    uuid REFERENCES users(id) ON DELETE SET NULL,
    version       bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_consent_channel CHECK (channel IN ('WHATSAPP', 'EMAIL', 'SMS')),
    CONSTRAINT ck_consent_purpose CHECK (purpose  IN ('TRANSACTIONAL', 'MARKETING')),
    CONSTRAINT ck_consent_status  CHECK (status   IN ('GRANTED', 'REVOKED', 'UNKNOWN')),
    CONSTRAINT ck_consent_source  CHECK (source   IN ('WEB_FORM', 'WHATSAPP_OPTIN', 'SMS_OPTIN',
        'EMAIL_OPTIN', 'STAFF_RECORDED', 'WHATSAPP_OPTOUT', 'SMS_OPTOUT', 'EMAIL_UNSUBSCRIBE',
        'SYSTEM_DEFAULT'))
);

-- "Latest status" is the hot lookup for every send the gate guards. Keep rows
-- append-only; do NOT UPDATE in place — that is the difference between a
-- preference history (auditable) and a mutating flag (not).
CREATE INDEX idx_consent_latest ON consent_records (customer_id, channel, purpose, occurred_at DESC);
CREATE INDEX idx_consent_by_time  ON consent_records (occurred_at DESC);

CREATE TABLE consent_suppressions (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    mobile_digits varchar(20) NOT NULL,
    channel       varchar(20) NOT NULL,
    source        varchar(30) NOT NULL,
    occurred_at   timestamptz NOT NULL DEFAULT now(),
    evidence_ref  varchar(255),
    CONSTRAINT ck_suppression_channel CHECK (channel IN ('WHATSAPP', 'EMAIL', 'SMS')),
    CONSTRAINT ck_suppression_source  CHECK (source  IN ('WHATSAPP_OPTOUT', 'SMS_OPTOUT'))
);

-- One suppression per (number, channel); re-giving the same instruction is a
-- no-op, not five rows.
CREATE UNIQUE INDEX uq_suppression_number_channel ON consent_suppressions (mobile_digits, channel);
CREATE INDEX idx_suppression_lookup ON consent_suppressions (mobile_digits, channel, occurred_at DESC);

-- -----------------------------------------------------------------------------
-- Template policy columns. Existing rows default to the least-privilege pair
-- (TRANSACTIONAL / APPROVED) and are corrected below.
-- -----------------------------------------------------------------------------
ALTER TABLE whatsapp_templates ADD COLUMN category varchar(20) NOT NULL DEFAULT 'TRANSACTIONAL';
ALTER TABLE whatsapp_templates ADD COLUMN approval_status varchar(20) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE whatsapp_templates ADD CONSTRAINT ck_template_category
    CHECK (category IN ('TRANSACTIONAL', 'MARKETING', 'OTP'));
ALTER TABLE whatsapp_templates ADD CONSTRAINT ck_template_approval
    CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'));

-- The nine Module 4 templates are operational messaging; POST_TRIP_REVIEW is a
-- promotional re-engagement and is therefore MARKETING (its sends now need
-- explicit consent like every other marketing outreach).
UPDATE whatsapp_templates SET category = 'TRANSACTIONAL'
 WHERE code IN ('PACKAGE_DETAILS', 'ITINERARY', 'PRICE_DETAILS', 'PAYMENT_LINK',
                'TRIP_LOGISTICS', 'BOOKING_CONFIRMED', 'BALANCE_DUE', 'DOCUMENT_LINK');
UPDATE whatsapp_templates SET category = 'MARKETING' WHERE code = 'POST_TRIP_REVIEW';

-- Acknowledgment for an inbound opt-out ("Your STOP request is recorded").
-- Straightforward enough to send without consent: it is the confirmation of the
-- preference the customer just expressed.
INSERT INTO whatsapp_templates (code, interakt_name, label, language_code, expected_params, category, approval_status)
VALUES ('OPTOUT_CONFIRMED', 'securetravels_optout_confirmed', 'Opt-out confirmed', 'en', 0, 'TRANSACTIONAL', 'APPROVED');

-- -----------------------------------------------------------------------------
-- The unified timeline gains SMS alongside WHATSAPP / EMAIL / SYSTEM.
-- -----------------------------------------------------------------------------
ALTER TABLE timeline_events DROP CONSTRAINT timeline_events_channel_check;
ALTER TABLE timeline_events ADD CONSTRAINT timeline_events_channel_check
    CHECK (channel IN ('WHATSAPP', 'EMAIL', 'SMS', 'SYSTEM'));

-- -----------------------------------------------------------------------------
-- Default for every existing customer: MARKETING is UNKNOWN on all three
-- channels. The legacy marketing_opt_in boolean is a snapshot, not a consent
-- history, and is deliberately NOT imported as a grant — Principle 3 of Phase 5
-- says a consent we cannot demonstrate was explicit must not be assumed.
-- -----------------------------------------------------------------------------
INSERT INTO consent_records (customer_id, channel, purpose, status, source, occurred_at, evidence_ref, version)
SELECT c.id, ch.channel, 'MARKETING', 'UNKNOWN', 'SYSTEM_DEFAULT', now(),
       'V14 backfill; legacy marketing_opt_in treated as unknown until reaffirmed', 0
  FROM customer360 c
 CROSS JOIN (VALUES ('WHATSAPP'), ('EMAIL'), ('SMS')) AS ch(channel);