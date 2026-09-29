-- =============================================================================
-- Phase 5, Module 2 — consent_suppressions: allow email opt-out lines.
--
-- V15 introduced email_address on consent_suppressions so an email STOP /
-- unsubscribe can be durable before a customer360 row exists, exactly like an
-- SMS or WhatsApp STOP. But ck_suppression_source (V14) still only lists
-- WHATSAPP_OPTOUT and SMS_OPTOUT, so an EMAIL_UNSUBSCRIBE suppression row can
-- never be inserted: every email opt-out — and every hard-bounce suppression,
-- which ConsentService routes down the same path — fails a check constraint
-- and turns the webhook into a 500 even though the consent decision was made.
--
-- The source column is widened here; the V15 identity rule
-- (ck_suppression_identity) already requires email_address for channel=EMAIL,
-- so the durable form of "this address said stop" is now actually durable.
-- =============================================================================

ALTER TABLE consent_suppressions DROP CONSTRAINT ck_suppression_source;

ALTER TABLE consent_suppressions ADD CONSTRAINT ck_suppression_source
    CHECK (source IN ('WHATSAPP_OPTOUT', 'SMS_OPTOUT', 'EMAIL_UNSUBSCRIBE'));