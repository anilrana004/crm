-- =====================================================================
-- SecureTravels CRM — Phase 2, Module 4 (WhatsApp / Interakt)
--
-- Module 4 introduces customer-facing communication: outbound WhatsApp
-- templates through Interakt, an inbound webhook for replies and delivery
-- status, and a real Lead/Customer/Booking timeline.
--
-- Two tables, not one. They answer different questions and have different
-- lifecycles:
--
--   whatsapp_messages  — the OUTBOUND INTENT. One row per message we decide
--                        to send, written BEFORE the send attempt. This row
--                        is the idempotency guard, because Interakt exposes
--                        no Idempotency-Key and a retry after a timeout can
--                        double-send to a real customer. A listener redelivery
--                        finds status != QUEUED and skips. See ADR 0005.
--
--   timeline_events    — the READ MODEL. Polymorphic over LEAD / CUSTOMER /
--                        BOOKING, direction INBOUND / OUTBOUND, so one
--                        timeline query serves "what did we tell this person
--                        and what did they say back".
--
-- audit_log is deliberately NOT reused here. It is a mutation log whose actor
-- is derived from the SecurityContext, and its action enum has no notion of a
-- message direction, a provider id, or a delivery state. Reusing it would
-- have meant encoding "customer replied on WhatsApp" into a field name.
--
-- Provider templates are metadata WE own: Interakt has no template list/create
-- API, so the dashboard (or Meta sync) is the source of the Interakt code name
-- and this table is the mirror. Keeping the code name out of Java lets ops fix
-- a name mismatch without a redeploy; expected_params lets us reject a bad
-- render before we spend a provider quota on it.
--
-- Max 4 body parameters per template is a Meta approval constraint, not an
-- arbitrary cap — more than 4 risks template rejection at review time.
-- =====================================================================

-- 1) Outbound message intent + delivery state.
CREATE TABLE whatsapp_messages (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type        varchar(20) NOT NULL CHECK (subject_type IN ('LEAD', 'CUSTOMER', 'BOOKING')),
    subject_id          uuid NOT NULL,          -- polymorphic; no FK by design
    template_code       varchar(60) NOT NULL,   -- FK -> whatsapp_templates.code
    recipient_mobile    varchar(20) NOT NULL,   -- 10-digit, normalized (PhoneUtils)
    country_code        varchar(8)  NOT NULL DEFAULT '+91',
    body_values         text[],                 -- positional {{1}}..{{4}}
    callback_data       varchar(512),           -- our id; echoed back by Interakt
    provider            varchar(30) NOT NULL DEFAULT 'INTERAKT',
    provider_message_id varchar(80),
    status              varchar(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'READ',
                          'FAILED', 'DEAD_LETTERED')),
    attempts            int NOT NULL DEFAULT 0,
    last_error          varchar(500),
    channel_error_code  varchar(20),            -- Meta code, e.g. '1013'
    failure_reason      varchar(300),
    queued_at           timestamptz NOT NULL DEFAULT now(),
    sent_at             timestamptz,
    delivered_at        timestamptz,
    read_at             timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,

    CONSTRAINT chk_whatsapp_attempts_non_negative CHECK (attempts >= 0),
    -- A message cannot be DELIVERED or READ before it was sent. This is the
    -- invariant that keeps a webhook racing a slow first send from inventing
    -- a delivery that never happened.
    CONSTRAINT chk_whatsapp_delivery_after_send CHECK (
        sent_at IS NULL
        OR (delivered_at IS NULL OR delivered_at >= sent_at)
    ),
    CONSTRAINT chk_whatsapp_read_after_delivery CHECK (
        delivered_at IS NULL OR read_at IS NULL OR read_at >= delivered_at
    )
);

CREATE INDEX idx_whatsapp_messages_subject ON whatsapp_messages(subject_type, subject_id, queued_at DESC);
CREATE INDEX idx_whatsapp_messages_status  ON whatsapp_messages(status, queued_at);
CREATE INDEX idx_whatsapp_messages_sent_at ON whatsapp_messages(sent_at DESC);

-- The dedup guard. callback_data carries our message id, so a provider retry
-- of the same logical message can never create a second intent row.
CREATE UNIQUE INDEX uq_whatsapp_messages_callback ON whatsapp_messages(callback_data)
    WHERE callback_data IS NOT NULL;

-- Webhook correlation. Not unique: Interakt may report the same id across
-- sent -> delivered -> read, and we want all of those lookups to hit.
CREATE INDEX idx_whatsapp_messages_provider_id ON whatsapp_messages(provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

-- 2) Interakt-side template metadata (mirror of the dashboard).
CREATE TABLE whatsapp_templates (
    code            varchar(60) PRIMARY KEY,
    interakt_name   varchar(120) NOT NULL,      -- the code name in app.interakt.ai
    label           varchar(200) NOT NULL,      -- human/ops label
    language_code   varchar(10)  NOT NULL DEFAULT 'en',
    expected_params int NOT NULL CHECK (expected_params BETWEEN 0 AND 4),
    enabled         boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    -- Ops must be able to turn a template off without a redeploy (a Meta
    -- rejection, a wording fix). Disabling must not delete history.
    CONSTRAINT chk_whatsapp_templates_params CHECK (expected_params <= 4)
);

-- 3) Seed the nine Module 4 templates. The interakt_name values are the
--    placeholders ops must create in the Interakt dashboard; they are
--    lowercase snake_case to match the legacy prototype's template_key
--    vocabulary (server/src/services/whatsapp.js) so sales muscle memory and
--    the old wa.me links still make sense.
INSERT INTO whatsapp_templates (code, interakt_name, label, language_code, expected_params) VALUES
  ('PACKAGE_DETAILS',   'securetravels_package_details',   'Package details',          'en', 4),
  ('ITINERARY',         'securetravels_itinerary',         'Day-by-day itinerary',     'en', 4),
  ('PRICE_DETAILS',     'securetravels_price_details',     'Price breakdown',          'en', 4),
  ('PAYMENT_LINK',      'securetravels_payment_link',      'Accept & Pay link',        'en', 4),
  ('TRIP_LOGISTICS',    'securetravels_trip_logistics',    'Hotel / driver / pickup',  'en', 4),
  ('BOOKING_CONFIRMED', 'securetravels_booking_confirmed', 'Booking confirmed',        'en', 4),
  ('BALANCE_DUE',       'securetravels_balance_due',       'Balance due reminder',     'en', 4),
  ('DOCUMENT_LINK',     'securetravels_document_link',     'Document upload link',     'en', 4),
  ('POST_TRIP_REVIEW',  'securetravels_post_trip_review',  'Post-trip review request', 'en', 3);

-- 4) Unified timeline. seq is bigserial for a stable order when created_at
--    ties (batch inserts within one transaction share a timestamp).
CREATE TABLE timeline_events (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type        varchar(20) NOT NULL CHECK (subject_type IN ('LEAD', 'CUSTOMER', 'BOOKING')),
    subject_id          uuid NOT NULL,          -- polymorphic; no FK by design
    direction           varchar(10) NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    channel             varchar(20) NOT NULL DEFAULT 'WHATSAPP'
        CHECK (channel IN ('WHATSAPP', 'EMAIL', 'SYSTEM')),
    kind                varchar(30) NOT NULL CHECK (kind IN
        ('TEMPLATE_QUEUED', 'TEMPLATE_SENT', 'TEMPLATE_DELIVERED', 'TEMPLATE_READ',
         'TEMPLATE_FAILED', 'REPLY_RECEIVED', 'MEDIA_RECEIVED', 'BUTTON_CLICKED',
         'SYSTEM_NOTE')),
    template_code       varchar(60),
    summary             varchar(300) NOT NULL,
    body                text,
    provider            varchar(30),
    provider_message_id varchar(80),
    customer_mobile     varchar(20),           -- denormalized for display
    actor_id            uuid REFERENCES users(id) ON DELETE SET NULL,
    body_values         text[],                -- what was actually rendered
    created_at          timestamptz NOT NULL DEFAULT now(),
    seq                 bigserial NOT NULL,

    CONSTRAINT uq_timeline_seq UNIQUE (seq)
);

CREATE INDEX idx_timeline_subject ON timeline_events(subject_type, subject_id, created_at DESC);
CREATE INDEX idx_timeline_created ON timeline_events(created_at DESC);

-- Webhook replay protection: Interakt retries non-200 deliveries, and a
-- redelivered status event must not append a second identical timeline row.
CREATE UNIQUE INDEX uq_timeline_provider_event ON timeline_events(provider, provider_message_id, kind)
    WHERE provider_message_id IS NOT NULL;
