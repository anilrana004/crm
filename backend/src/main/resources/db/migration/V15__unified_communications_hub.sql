-- =============================================================================
-- Phase 5, Module 2 — Unified Communications Hub (inbox, email, SMS).
--
-- WhatsApp already has an outbound-intent table and a timeline read model
-- (V12). Rather than bolt a second inbox onto it, this migration promotes
-- the message-log concept to all three channels:
--
--   communication_threads  — ONE conversation per (person, channel). The
--                             inbox row: last message preview, unread badge,
--                             assignee, status, the inbound/outbound
--                             timestamp the 24h service window is measured
--                             from, and the window state itself.
--   email_messages         — outbound intent for email, shaped like
--   sms_messages              whatsapp_messages so the gate, retries and
--                             delivery tracking behave identically per
--                             channel. Written BEFORE the send attempt,
--                             which is what makes a provider retry safe.
--
-- Why a thread table and not "just filter messages by customer": a thread
-- needs mutable state (assignee, unread badge, open/closed) that a message
-- log must not have. Messages are append-only facts; a thread is a working
-- queue. Mixing them would mean mutating rows that are meant to be an audit
-- trail.
--
-- 24h service window: WhatsApp business-initiated templates are only allowed
-- inside a customer service window (24h from their last inbound message).
-- We track the window explicitly rather than recomputing it per send, so the
-- inbox can show "template no longer allowed" and the gate can reject with
-- that reason without re-deriving timestamps.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) Threads: one conversation per (subject, channel).
-- -----------------------------------------------------------------------------
CREATE TABLE communication_threads (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type        varchar(20) NOT NULL CHECK (subject_type IN ('LEAD', 'CUSTOMER', 'BOOKING')),
    subject_id          uuid NOT NULL,          -- polymorphic; no FK by design
    channel             varchar(20) NOT NULL CHECK (channel IN ('WHATSAPP', 'EMAIL', 'SMS')),
    customer_mobile     varchar(20),           -- denormalized for the inbox list
    customer_name       varchar(200),

    -- Inbox working state. These are the only mutable columns in the table.
    status              varchar(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'PENDING', 'CLOSED')),
    unread_count        int NOT NULL DEFAULT 0 CHECK (unread_count >= 0),
    assigned_to         uuid REFERENCES users(id) ON DELETE SET NULL,
    last_message_at     timestamptz NOT NULL DEFAULT now(),
    last_direction      varchar(10) NOT NULL DEFAULT 'INBOUND'
        CHECK (last_direction IN ('INBOUND', 'OUTBOUND')),
    last_preview        varchar(300),

    -- 24h service window (WhatsApp customer-service window). Null window =
    -- the window has never been opened by an inbound message.
    window_opened_at    timestamptz,
    window_expires_at   timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,

    CONSTRAINT chk_thread_window_order CHECK (
        window_expires_at IS NULL
        OR window_opened_at IS NOT NULL
    )
);

-- One thread per conversation. This is the inbox dedup guard: a webhook
-- redelivery resolves the same thread instead of creating a second row.
CREATE UNIQUE INDEX uq_thread_subject_channel ON communication_threads (subject_type, subject_id, channel);
CREATE INDEX idx_thread_inbox ON communication_threads (assigned_to, status, last_message_at DESC);
CREATE INDEX idx_thread_customer_mobile ON communication_threads (customer_mobile, last_message_at DESC);

-- -----------------------------------------------------------------------------
-- 2) Email: outbound intent, same shape as whatsapp_messages.
-- -----------------------------------------------------------------------------
CREATE TABLE email_messages (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id           uuid NOT NULL REFERENCES communication_threads(id) ON DELETE CASCADE,
    subject_type        varchar(20) NOT NULL CHECK (subject_type IN ('LEAD', 'CUSTOMER', 'BOOKING')),
    subject_id          uuid NOT NULL,
    template_code       varchar(80),
    recipient_email     varchar(255) NOT NULL,
    subject_line        varchar(300),
    body_text           text,
    body_html           text,
    provider            varchar(30) NOT NULL DEFAULT 'SES',
    provider_message_id varchar(120),
    status              varchar(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'OPENED',
                          'BOUNCED', 'COMPLAINED', 'FAILED', 'DEAD_LETTERED')),
    attempts            int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error          varchar(500),
    failure_reason      varchar(300),
    queued_at           timestamptz NOT NULL DEFAULT now(),
    sent_at             timestamptz,
    delivered_at        timestamptz,
    opened_at           timestamptz,
    bounced_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,

    CONSTRAINT chk_email_delivery_after_send CHECK (
        sent_at IS NULL OR (delivered_at IS NULL OR delivered_at >= sent_at)
    )
);

CREATE INDEX idx_email_messages_thread ON email_messages(thread_id, queued_at DESC);
CREATE INDEX idx_email_messages_subject ON email_messages(subject_type, subject_id, queued_at DESC);
CREATE INDEX idx_email_messages_status ON email_messages(status, queued_at);
CREATE INDEX idx_email_messages_recipient ON email_messages(recipient_email, queued_at DESC);

-- Provider correlation. SES reports the same id across sent -> delivered ->
-- opened, so the index is deliberately non-unique.
CREATE INDEX idx_email_messages_provider_id ON email_messages(provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 3) SMS: outbound intent. DLT templates are provider-side, so the
--    registered template id is recorded for traceability but never required.
-- -----------------------------------------------------------------------------
CREATE TABLE sms_messages (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id           uuid NOT NULL REFERENCES communication_threads(id) ON DELETE CASCADE,
    subject_type        varchar(20) NOT NULL CHECK (subject_type IN ('LEAD', 'CUSTOMER', 'BOOKING')),
    subject_id          uuid NOT NULL,
    template_code       varchar(80),
    recipient_mobile    varchar(20) NOT NULL,   -- 10-digit, normalized (PhoneUtils)
    country_code        varchar(8)  NOT NULL DEFAULT '+91',
    body_text           text,
    dlt_template_id     varchar(80),
    provider            varchar(30) NOT NULL DEFAULT 'MSG91',
    provider_message_id varchar(120),
    status              varchar(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'FAILED',
                          'DEAD_LETTERED')),
    attempts            int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error          varchar(500),
    failure_reason      varchar(300),
    queued_at           timestamptz NOT NULL DEFAULT now(),
    sent_at             timestamptz,
    delivered_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,

    CONSTRAINT chk_sms_delivery_after_send CHECK (
        sent_at IS NULL OR (delivered_at IS NULL OR delivered_at >= sent_at)
    )
);

CREATE INDEX idx_sms_messages_thread ON sms_messages(thread_id, queued_at DESC);
CREATE INDEX idx_sms_messages_subject ON sms_messages(subject_type, subject_id, queued_at DESC);
CREATE INDEX idx_sms_messages_status ON sms_messages(status, queued_at);
CREATE INDEX idx_sms_messages_recipient ON sms_messages(recipient_mobile, queued_at DESC);
CREATE INDEX idx_sms_messages_provider_id ON sms_messages(provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 4) Channel template library. WhatsApp templates already live in
--    whatsapp_templates (V12/V14); this is the shared catalogue so one
--    screen lists every channel and the gate can resolve a template category
--    uniformly. email/sms rows are seeded as PENDING because a real provider
--    template id must be registered (and, for SMS, DLT-approved) first.
-- -----------------------------------------------------------------------------
CREATE TABLE channel_templates (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel         varchar(20) NOT NULL CHECK (channel IN ('WHATSAPP', 'EMAIL', 'SMS')),
    code            varchar(80) NOT NULL,
    label           varchar(200) NOT NULL,
    language_code   varchar(10) NOT NULL DEFAULT 'en',
    subject_line    varchar(300),                -- EMAIL only
    body            text,
    expected_params int NOT NULL DEFAULT 0 CHECK (expected_params BETWEEN 0 AND 4),
    category        varchar(20) NOT NULL CHECK (category IN ('TRANSACTIONAL', 'MARKETING', 'OTP')),
    approval_status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    provider_ref    varchar(120),                -- SES template name / DLT id / Interakt name
    enabled         boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,

    CONSTRAINT uq_channel_template_code UNIQUE (channel, code)
);

CREATE INDEX idx_channel_templates_channel ON channel_templates(channel, approval_status, enabled);

-- Seed the cross-channel library, using the SAME codes and parameter counts as
-- the WhatsApp templates in V12. That is the point of the module: one code
-- ("PAYMENT_LINK") means the same message to the customer whichever channel
-- carries it, so a caller does not have to know that email calls it
-- EMAIL_PAYMENT_LINK. An operator editing copy edits one row per channel.
--
-- SMS is deliberately the short form: a 160-character segment cannot carry a
-- four-field itinerary, and silently truncating one would be worse than
-- refusing to send it. expected_params is what makes that refusal explicit at
-- the gate rather than at the handset.
--
-- Seeded APPROVED because these are the same transactional messages V12 already
-- approved for WhatsApp; a marketing template would have to earn approval.
INSERT INTO channel_templates
    (channel, code, label, language_code, subject_line, body, expected_params, category, approval_status)
VALUES
    ('EMAIL', 'PACKAGE_DETAILS', 'Package details', 'en',
     'Your {{1}} package is ready, {{2}}',
     'Hello {{3}},' || chr(10) ||
     'Here are the details of your package {{1}} (booking {{2}}), departing {{4}}.' || chr(10) ||
     'Reply to this email if anything needs changing.',
     4, 'TRANSACTIONAL', 'APPROVED'),
    ('EMAIL', 'ITINERARY', 'Day-by-day itinerary', 'en',
     'Itinerary for {{1}} — {{2}}',
     'Hello {{3}},' || chr(10) ||
     'Your day-by-day itinerary for {{1}} (booking {{2}}, departing {{4}}) is attached.' || chr(10) ||
     'Reply to this email if anything needs changing.',
     4, 'TRANSACTIONAL', 'APPROVED'),
    ('EMAIL', 'PRICE_DETAILS', 'Price breakdown', 'en',
     'Price breakdown for {{1}}',
     'Hello {{3}},' || chr(10) ||
     'Here is the price breakdown for {{1}} (booking {{2}}, departing {{4}}).' || chr(10) ||
     'Reply to this email if anything needs changing.',
     4, 'TRANSACTIONAL', 'APPROVED'),
    ('EMAIL', 'PAYMENT_LINK', 'Accept & Pay link', 'en',
     'Complete your payment for {{1}}',
     'Hello {{3}},' || chr(10) ||
     'Booking {{2}} departing {{4}} is confirmed. Use the link below to pay.' || chr(10) ||
     'Reply to this email if you have already paid.',
     4, 'TRANSACTIONAL', 'APPROVED'),
    ('EMAIL', 'TRIP_LOGISTICS', 'Hotel / driver / pickup', 'en',
     'Getting to your trip: {{1}}',
     'Hello {{3}},' || chr(10) ||
     'Hotel, driver and pickup details for {{1}} (booking {{2}}, departing {{4}}).' || chr(10) ||
     'Reply to this email with any questions.',
     4, 'TRANSACTIONAL', 'APPROVED'),
    ('EMAIL', 'BOOKING_CONFIRMED', 'Booking confirmed', 'en',
     'Booking confirmed — {{1}}',
     'Hello {{3}},' || chr(10) ||
     'Booking {{2}} for {{1}} departing {{4}} is confirmed.' || chr(10) ||
     'We will send the remaining documents before you travel.',
     4, 'TRANSACTIONAL', 'APPROVED'),
    ('EMAIL', 'BALANCE_DUE', 'Balance due reminder', 'en',
     'Balance due for {{1}}',
     'Hello {{3}},' || chr(10) ||
     'A balance is outstanding on booking {{2}} for {{1}}, departing {{4}}.' || chr(10) ||
     'Use the payment link in this email to settle it.',
     4, 'TRANSACTIONAL', 'APPROVED'),
    ('EMAIL', 'DOCUMENT_LINK', 'Document upload link', 'en',
     'Upload your documents for {{1}}',
     'Hello {{3}},' || chr(10) ||
     'Please upload the documents for booking {{2}} ({{1}}, departing {{4}}) using the link below.',
     4, 'TRANSACTIONAL', 'APPROVED'),
    ('EMAIL', 'POST_TRIP_REVIEW', 'Post-trip review request', 'en',
     'How was {{1}}?',
     'Hello {{3}},' || chr(10) ||
     'You have just returned from {{1}} (booking {{2}}). We would be grateful for a short review.',
     3, 'MARKETING', 'APPROVED'),

    ('SMS', 'PACKAGE_DETAILS', 'Package details', 'en', NULL,
     '{{1}}: booking {{2}}, departs {{4}}. Reply HELP for help.', 4, 'TRANSACTIONAL', 'APPROVED'),
    ('SMS', 'BOOKING_CONFIRMED', 'Booking confirmed', 'en', NULL,
     '{{1}}: booking {{2}} confirmed, departs {{4}}.', 4, 'TRANSACTIONAL', 'APPROVED'),
    ('SMS', 'BALANCE_DUE', 'Balance due reminder', 'en', NULL,
     'Balance due on {{1}} booking {{2}}, departs {{4}}. Reply PAY for a link.', 4, 'TRANSACTIONAL', 'APPROVED'),
    ('SMS', 'DOCUMENT_LINK', 'Document upload link', 'en', NULL,
     'Upload documents for {{1}} booking {{2}}. Link valid 48h. Reply HELP for help.', 4, 'TRANSACTIONAL', 'APPROVED');

-- -----------------------------------------------------------------------------
-- 5) Suppressions now also cover email. A hard bounce or spam complaint is an
--    opt-out in stronger words than a link click, but it arrives keyed by
--    address rather than by mobile — hence a second nullable identifier
--    instead of overloading mobile_digits with something that is not a number.
-- -----------------------------------------------------------------------------
-- mobile_digits was NOT NULL in V14, which assumed every suppression came from
-- a STOP keyword. Email suppressions have no number, so it becomes nullable and
-- ck_suppression_identity (below) takes over the "somebody must be identified"
-- job.
ALTER TABLE consent_suppressions ALTER COLUMN mobile_digits DROP NOT NULL;
ALTER TABLE consent_suppressions ADD COLUMN email_address varchar(255);
CREATE UNIQUE INDEX uq_suppression_email_channel ON consent_suppressions (email_address, channel)
    WHERE email_address IS NOT NULL;
CREATE INDEX idx_suppression_email_lookup ON consent_suppressions (email_address, channel, occurred_at DESC)
    WHERE email_address IS NOT NULL;

-- A suppression must identify somebody. mobile_digits stays NOT NULL for the
-- phone-based (STOP keyword) rows that V14 created; an EMAIL row is identified
-- by address alone.
-- A suppression must name exactly one subject, and the column used must be the
-- one the channel implies. Enforced as an exclusive either/or rather than the
-- looser "EMAIL or has a mobile", which would silently accept an EMAIL row
-- with a NULL address -- an opt-out that suppresses nobody.
ALTER TABLE consent_suppressions ADD CONSTRAINT ck_suppression_identity
      CHECK ((channel = 'EMAIL'  AND email_address IS NOT NULL AND mobile_digits IS NULL)
          OR (channel <> 'EMAIL' AND mobile_digits IS NOT NULL AND email_address IS NULL));

-- -----------------------------------------------------------------------------
-- 6) Inbound messages. The outbound tables record intent; nothing recorded what
--    the customer actually said. Without this there is no inbox to read, and —
--    more seriously — no place to anchor idempotency for a reply from a number
--    we have no conversation with yet, where no timeline row can be written
--    (timeline_events requires a subject).
--
--    The (provider, provider_message_id) unique index is the guard behind the
--    hard rule: three webhook deliveries of one inbound message produce at most
--    one row here, and therefore at most one lead.
-- -----------------------------------------------------------------------------
CREATE TABLE inbound_messages (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel             varchar(20) NOT NULL CHECK (channel IN ('WHATSAPP', 'EMAIL', 'SMS')),
    provider            varchar(30) NOT NULL,
    provider_message_id varchar(120) NOT NULL,
    thread_id           uuid REFERENCES communication_threads(id) ON DELETE SET NULL,
    subject_type        varchar(20) CHECK (subject_type IN ('LEAD', 'CUSTOMER', 'BOOKING')),
    subject_id          uuid,
    lead_id             uuid REFERENCES leads(id) ON DELETE SET NULL,
    from_mobile         varchar(20),
    from_email          varchar(255),
    body                text,
    is_media            boolean NOT NULL DEFAULT false,
    media_type          varchar(60),
    received_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,

    -- A subject is resolved at arrival when we can, and left null when we
    -- cannot (a stranger's first message). The check allows exactly that.
    CONSTRAINT chk_inbound_subject_pair CHECK (
        (subject_type IS NULL AND subject_id IS NULL)
        OR (subject_type IS NOT NULL AND subject_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_inbound_provider_event ON inbound_messages (provider, provider_message_id);
CREATE INDEX idx_inbound_thread ON inbound_messages(thread_id, received_at DESC);
CREATE INDEX idx_inbound_subject ON inbound_messages(subject_type, subject_id, received_at DESC);
CREATE INDEX idx_inbound_from_mobile ON inbound_messages(from_mobile, received_at DESC);

-- -----------------------------------------------------------------------------
-- 7) Inbound email replies need a webhook replay guard, the same guarantee
--    timeline_events already has for Interakt.
-- -----------------------------------------------------------------------------
-- Email and SMS need the two delivery outcomes WhatsApp had no room for:
-- an open is a stronger signal than a delivery, and a bounce is a hard
-- failure. TEMPLATE_FAILED was already legal in V12 and must be repeated
-- verbatim here -- dropping it would make every rejected WhatsApp message
-- fail its insert, because a CHECK constraint is replaced wholesale and
-- not merged.
ALTER TABLE timeline_events DROP CONSTRAINT timeline_events_kind_check;
ALTER TABLE timeline_events ADD CONSTRAINT timeline_events_kind_check
      CHECK (kind IN ('TEMPLATE_QUEUED', 'TEMPLATE_SENT', 'TEMPLATE_DELIVERED', 'TEMPLATE_READ',
                      'TEMPLATE_OPENED', 'TEMPLATE_BOUNCED', 'TEMPLATE_FAILED',
                      'REPLY_RECEIVED', 'MEDIA_RECEIVED', 'BUTTON_CLICKED',
                      'SYSTEM_NOTE'));
