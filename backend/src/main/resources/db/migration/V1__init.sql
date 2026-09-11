-- =====================================================================
-- SecureTravels CRM — Phase 1 schema (approved 2026-09)
-- Trip/Batch/Booking architecture with FIXED_BATCH vs CUSTOM_FIT.
-- Invariants I1/I2 enforced by trigger trg_booking_consistency.
--
-- Enums are stored as varchar + named CHECK constraints (NOT native PG
-- enum types) so Hibernate @Enumerated(STRING) binds without JDBC casts;
-- the CHECK provides the same referential/integrity guarantees.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,           -- BCrypt strength 12
    full_name     varchar(120) NOT NULL,
    role          varchar(30) NOT NULL CHECK (role IN ('SALES', 'OPS', 'MANAGER', 'ADMIN', 'CEO')),
    phone         varchar(20),
    is_active     boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------
-- refresh_tokens (7-day refresh, rotated on use, stored hashed)
-- ---------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  varchar(64) NOT NULL UNIQUE,       -- SHA-256 hex of raw token
    expires_at  timestamptz NOT NULL,
    revoked     boolean NOT NULL DEFAULT false,
    replaced_by varchar(64),                       -- hash of the rotated-out token
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

-- ---------------------------------------------------------------------
-- trips  (catalogue; booking_type discriminates the two flows)
-- ---------------------------------------------------------------------
CREATE TABLE trips (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name          varchar(255) NOT NULL,
    slug          varchar(100) NOT NULL UNIQUE,
    category      varchar(30) NOT NULL CHECK (category IN ('TREK', 'PILGRIMAGE', 'LEISURE', 'CUSTOM')),
    booking_type  varchar(30) NOT NULL CHECK (booking_type IN ('FIXED_BATCH', 'CUSTOM_FIT')),
    base_cost     numeric(12,2) NOT NULL,
    duration_days integer NOT NULL CHECK (duration_days > 0),
    itinerary     text,                            -- OWASP-sanitized HTML
    inclusions    text,
    exclusions    text,
    is_active     boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------
-- guides  (minimal Phase-1 table; vendors land in Phase 2)
-- ---------------------------------------------------------------------
CREATE TABLE guides (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name   varchar(120) NOT NULL,
    phone       varchar(20),
    daily_rate  numeric(10,2),
    is_active   boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- batches  (FIXED_BATCH only; seats_available is DERIVED, not stored)
-- Invariant I3: seats_booked = CONFIRMED bookings + active HELD holds,
-- enforced by the service inside a PESSIMISTIC_WRITE lock.
-- ---------------------------------------------------------------------
CREATE TABLE batches (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id         uuid NOT NULL REFERENCES trips(id),
    departure_date  date NOT NULL,
    max_capacity    integer NOT NULL CHECK (max_capacity > 0),
    seats_booked    integer NOT NULL DEFAULT 0 CHECK (seats_booked >= 0),
    guide_id        uuid REFERENCES guides(id),
    transport_plan  text,
    status          varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_batch_trip_departure UNIQUE (trip_id, departure_date)
);
CREATE INDEX idx_batches_trip     ON batches(trip_id);
CREATE INDEX idx_batches_departure ON batches(departure_date);

-- ---------------------------------------------------------------------
-- seat_holds  (2-hour provisional hold, auto-release)
-- ---------------------------------------------------------------------
CREATE TABLE seat_holds (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id    uuid NOT NULL REFERENCES batches(id),
    booking_id  uuid,                              -- backfilled on confirm
    num_seats   integer NOT NULL CHECK (num_seats > 0),
    held_until  timestamptz NOT NULL,
    status      varchar(20) NOT NULL DEFAULT 'HELD' CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_seat_holds_batch_status ON seat_holds(batch_id, status);

-- ---------------------------------------------------------------------
-- leads
-- ---------------------------------------------------------------------
CREATE TABLE leads (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_name         varchar(200) NOT NULL,
    mobile_number         varchar(30) NOT NULL,
    mobile_digits         varchar(20) NOT NULL,     -- normalized for dedup
    whatsapp_number       varchar(30),
    email                 varchar(255),
    source                varchar(30) NOT NULL CHECK (source IN
        ('GOOGLE_ADS', 'FACEBOOK_ADS', 'INSTAGRAM', 'WEBSITE', 'WHATSAPP',
         'REFERRAL', 'JUSTDIAL', 'WALK_IN', 'B2B', 'EXISTING_CUSTOMER', 'OTHER')),
    destination           varchar(120),
    trip_id               uuid REFERENCES trips(id),
    travel_date           date,
    num_persons           integer NOT NULL DEFAULT 1 CHECK (num_persons > 0),
    budget                numeric(12,2),
    owner_id              uuid REFERENCES users(id),
    status                varchar(20) NOT NULL DEFAULT 'NEW' CHECK (status IN
        ('NEW', 'INTERESTED', 'QUOTATION_SENT', 'BOOKING_CONFIRMED', 'LOST')),
    heat                  varchar(10) NOT NULL DEFAULT 'COLD' CHECK (heat IN ('HOT', 'WARM', 'COLD')),
    follow_up_date        date,
    remarks               text,
    consent_given         boolean NOT NULL DEFAULT false,   -- DPDPA / security req 12
    consent_captured_at   timestamptz,
    consent_scope         varchar(200),
    lost_reason           varchar(30) CHECK (lost_reason IN
        ('BUDGET', 'TIMING_DATES', 'ALREADY_BOOKED', 'NO_RESPONSE', 'SERVICE_COMPARISON', 'OTHER')),
    duplicate_of_lead_id  uuid REFERENCES leads(id),
    last_contacted_at     timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    created_by            uuid REFERENCES users(id),
    version               bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_leads_mobile_digits ON leads(mobile_digits);
CREATE INDEX idx_leads_owner_status  ON leads(owner_id, status, created_at DESC);
CREATE INDEX idx_leads_email         ON leads(email);
CREATE INDEX idx_leads_created_at    ON leads(created_at DESC);

-- ---------------------------------------------------------------------
-- customer360  (the canonical person record; DPDPA single source of truth)
-- ---------------------------------------------------------------------
CREATE TABLE customer360 (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name           varchar(200) NOT NULL,
    mobile_number       varchar(30) NOT NULL,
    mobile_digits       varchar(20) NOT NULL,
    whatsapp_number     varchar(30),
    email               varchar(255),
    consent_given       boolean NOT NULL DEFAULT false,
    consent_captured_at timestamptz,
    consent_scope       varchar(200),
    marketing_opt_in    boolean NOT NULL DEFAULT false,
    total_trips         integer NOT NULL DEFAULT 0,
    last_trip_date      date,
    total_spent         numeric(12,2) NOT NULL DEFAULT 0,
    suggest_offer       varchar(200),
    offer_tags          text[],
    notes               text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_customer360_mobile_digits ON customer360(mobile_digits);
CREATE UNIQUE INDEX uq_customer360_email         ON customer360(email);

-- ---------------------------------------------------------------------
-- bookings
-- Invariants I1/I2 enforced by trg_booking_consistency.
-- ---------------------------------------------------------------------
CREATE TABLE bookings (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_ref          varchar(20) NOT NULL UNIQUE,
    trip_id              uuid NOT NULL REFERENCES trips(id),
    batch_id             uuid REFERENCES batches(id),
    customer_id          uuid NOT NULL REFERENCES customer360(id),
    booking_type         varchar(30) NOT NULL CHECK (booking_type IN ('FIXED_BATCH', 'CUSTOM_FIT')),
    num_travellers       integer NOT NULL CHECK (num_travellers > 0),
    total_amount         numeric(12,2) NOT NULL DEFAULT 0,
    discount_amount      numeric(12,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    tax_amount           numeric(12,2) NOT NULL DEFAULT 0,
    status               varchar(20) NOT NULL DEFAULT 'QUOTATION' CHECK (status IN
        ('QUOTATION', 'CONFIRMED', 'COMPLETED', 'CANCELLED')),
    travel_date          date NOT NULL,
    discount_approved_by uuid REFERENCES users(id),
    notes                text,
    created_by           uuid REFERENCES users(id),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_bookings_customer ON bookings(customer_id);
CREATE INDEX idx_bookings_trip     ON bookings(trip_id);
CREATE INDEX idx_bookings_batch    ON bookings(batch_id);
CREATE INDEX idx_bookings_status   ON bookings(status);
CREATE INDEX idx_bookings_travel_date ON bookings(travel_date);

CREATE OR REPLACE FUNCTION enforce_booking_consistency() RETURNS trigger AS $$
DECLARE
    t varchar(30);
    bt uuid;
BEGIN
    SELECT booking_type INTO t FROM trips WHERE id = NEW.trip_id;
    IF t IS NULL THEN
        RAISE EXCEPTION 'trip not found: %', NEW.trip_id;
    END IF;

    -- I1: booking_type must equal trip.booking_type (denormalized copy).
    IF NEW.booking_type IS NULL THEN
        NEW.booking_type := t;
    ELSIF NEW.booking_type <> t THEN
        RAISE EXCEPTION 'booking_type % does not match trip booking_type %', NEW.booking_type, t;
    END IF;

    -- I2: FIXED_BATCH requires a batch from the same trip; CUSTOM_FIT forbids batch.
    IF NEW.booking_type = 'FIXED_BATCH' THEN
        IF NEW.batch_id IS NULL THEN
            RAISE EXCEPTION 'FIXED_BATCH booking requires batch_id';
        END IF;
        SELECT trip_id INTO bt FROM batches WHERE id = NEW.batch_id;
        IF bt IS DISTINCT FROM NEW.trip_id THEN
            RAISE EXCEPTION 'batch % does not belong to trip %', NEW.batch_id, NEW.trip_id;
        END IF;
    ELSE
        IF NEW.batch_id IS NOT NULL THEN
            RAISE EXCEPTION 'CUSTOM_FIT booking must have NULL batch_id';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_booking_consistency
    BEFORE INSERT OR UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION enforce_booking_consistency();

-- ---------------------------------------------------------------------
-- travellers  (PII: links to customer360 when identifiable)
-- ---------------------------------------------------------------------
CREATE TABLE travellers (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id            uuid NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    customer360_id        uuid REFERENCES customer360(id),
    full_name             varchar(200) NOT NULL,
    age                   integer,
    gender                varchar(10),
    phone                 varchar(20),
    medical_cert_required boolean NOT NULL DEFAULT false,
    created_at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_travellers_booking ON travellers(booking_id);

-- ---------------------------------------------------------------------
-- payments  (manual entry in Phase 1; gateway_ref lands with the gateway)
-- ---------------------------------------------------------------------
CREATE TABLE payments (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  uuid NOT NULL REFERENCES bookings(id),
    amount      numeric(12,2) NOT NULL CHECK (amount > 0),
    amount_type varchar(20) NOT NULL CHECK (amount_type IN ('ADVANCE', 'BALANCE', 'FULL')),
    status      varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN
        ('PENDING', 'PARTIAL', 'COMPLETED', 'OVERDUE', 'CANCELLED', 'REFUNDED')),
    due_date    date,
    paid_at     timestamptz,
    gateway_ref varchar(120),                       -- NEVER raw card data
    notes       text,
    recorded_by uuid REFERENCES users(id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT chk_payment_status_date CHECK (
        (status = 'COMPLETED' AND paid_at IS NOT NULL) OR status <> 'COMPLETED'
    )
);
CREATE INDEX idx_payments_booking ON payments(booking_id);

-- ---------------------------------------------------------------------
-- operations_handoffs  (auto-created once on CONFIRMED; I6)
-- ---------------------------------------------------------------------
CREATE TABLE operations_handoffs (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id             uuid NOT NULL UNIQUE REFERENCES bookings(id),
    ops_ref                varchar(20) NOT NULL UNIQUE,
    batch_id               uuid REFERENCES batches(id),
    travel_date            date NOT NULL,
    pax                    integer NOT NULL CHECK (pax > 0),
    hotel_status           varchar(20) NOT NULL DEFAULT 'NOT_ARRANGED'
        CHECK (hotel_status IN ('NOT_ARRANGED', 'PENDING', 'CONFIRMED')),
    transport_status       varchar(20) NOT NULL DEFAULT 'NOT_ARRANGED'
        CHECK (transport_status IN ('NOT_ARRANGED', 'PENDING', 'CONFIRMED')),
    guide_id               uuid REFERENCES guides(id),
    driver_id              uuid,                    -- Phase-2 vendor record
    payment_status         varchar(20) CHECK (payment_status IN
        ('PENDING', 'PARTIAL', 'COMPLETED', 'OVERDUE', 'CANCELLED', 'REFUNDED')),
    trip_sheet_generated_at timestamptz,
    notes                  text,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_ops_handoffs_travel_date ON operations_handoffs(travel_date);

-- ---------------------------------------------------------------------
-- tasks  (automation: 5-min first contact, +1/2/5/7d, SLA escalation)
-- ---------------------------------------------------------------------
CREATE TABLE tasks (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    lead_id       uuid REFERENCES leads(id),
    booking_id    uuid REFERENCES bookings(id),
    assignee_id   uuid NOT NULL REFERENCES users(id),
    type          varchar(30) NOT NULL CHECK (type IN
        ('INITIAL_CALL', 'FOLLOW_UP_1D', 'FOLLOW_UP_2D', 'FOLLOW_UP_5D', 'FOLLOW_UP_7D',
         'QUOTATION', 'PAYMENT_REMINDER', 'OPS', 'REVIEW', 'CUSTOM')),
    status        varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'OVERDUE', 'CANCELLED')),
    due_at        timestamptz NOT NULL,
    sla_deadline  timestamptz,
    completed_at  timestamptz,
    escalated_at  timestamptz,
    notes         text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_tasks_assignee_due ON tasks(assignee_id, status, due_at);
CREATE INDEX idx_tasks_lead ON tasks(lead_id);

-- ---------------------------------------------------------------------
-- documents  (S3 bridge; no file bytes in Postgres)
-- ---------------------------------------------------------------------
CREATE TABLE documents (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    related_type varchar(20) NOT NULL CHECK (related_type IN ('TRAVELLER', 'BOOKING', 'LEAD')),
    traveller_id uuid REFERENCES travellers(id),
    booking_id   uuid REFERENCES bookings(id),
    doc_type     varchar(20) NOT NULL CHECK (doc_type IN ('ID_PROOF', 'MEDICAL_CERT', 'TRIP_PHOTO')),
    storage_key  varchar(300) NOT NULL,
    mime_type    varchar(120),
    size_bytes   bigint,
    uploaded_by  uuid REFERENCES users(id),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_related ON documents(related_type);

-- ---------------------------------------------------------------------
-- audit_log  (requirement 9: who, old, new, when)
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    entity     varchar(80) NOT NULL,
    entity_id  uuid NOT NULL,
    action     varchar(30) NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'STATUS_CHANGE', 'DELETE', 'LOGIN', 'LOGOUT')),
    field      varchar(80),
    old_value  text,
    new_value  text,
    actor_id   uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_log(entity, entity_id);
CREATE INDEX idx_audit_created ON audit_log(created_at DESC);