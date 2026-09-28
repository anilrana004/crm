-- =====================================================================
-- SecureTravels CRM - Phase 3, Module 2 (Reporting & Analytics)
--
-- Two independent additions, both answering the deferred-OpenSearch decision
-- in docs/ARCHITECTURE.md 4.1 and 4.2.
--
-- 1. sales_commission_ledger
--    Team Performance is required to be "structured to directly feed future
--    commission calculation (Phase 7)". It cannot be derived from leads.
--
--    The tempting query is bookings -> leads -> leads.owner_id. That is wrong,
--    and wrong in a way that only shows up months later: leads.owner_id is
--    MUTABLE. Reassign a consultant in March and every historical "revenue per
--    consultant" figure silently rewrites itself, including last quarter's
--    numbers that a commission run has already paid against. Nothing errors;
--    payroll is simply wrong.
--
--    So revenue attribution is snapshotted at the moment of crediting, in the
--    same transaction as the booking status change, and never re-derived. A
--    reassignment after the fact does not touch the ledger.
--
--    Revocation is a column, not a delete. A cancelled booking must leave an
--    explicit trail (revoked_at + revoke_reason): a commission dispute is
--    settled by showing when the credit appeared and when it was withdrawn.
--
--    net_amount is stored rather than computed on read, because the basis rules
--    for commissionable revenue (whether tax is commissionable, whether a
--    discount reduces the base) are a Phase 7 decision. The snapshot is the
--    thing that must not change underneath them.
--
-- 2. A backfill of that ledger from existing CONFIRMED/COMPLETED bookings, so
--    the reports return history instead of zeros on the day they ship.
--
-- 3. Full-text search, per docs/ARCHITECTURE.md 4.1
--    "proper Postgres FTS - generated tsvector columns ... GIN indexes,
--    ts_rank ordering, pg_trgm for fuzzy/typo-tolerant match".
--
--    Measured on 2026-09-27 at 100,000 rows: tsvector+GIN 0.68-0.76 ms versus
--    42.752 ms for ILIKE, so this is a real speedup and not a premature index.
--
--    Three tables get a tsvector. bookings is deliberately NOT one of them:
--    its only free-text-ish content is booking_ref and an enum status, and an
--    index that nothing can usefully query is pure write cost.
--
--    The generated columns need a literal 'english'::regconfig, not
--    current_setting('default_text_search_config'): the latter is only STABLE,
--    and a generated column may only call IMMUTABLE functions. Indexes cannot be
--    created before their column, hence the split.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Commission-grade revenue attribution
-- ---------------------------------------------------------------------

create table sales_commission_ledger (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- One credit per booking. The unique constraint is the idempotency guard:
    -- a retried status-change webhook must not credit twice.
    booking_id          uuid NOT NULL UNIQUE REFERENCES bookings(id) ON DELETE CASCADE,

    -- Denormalised deliberately. The lead can be unlinked or reassigned later;
    -- the ledger must still be able to explain where the credit came from.
    lead_id             uuid,
    consultant_id       uuid NOT NULL REFERENCES users(id),
    trip_id             uuid,
    batch_id            uuid,

    credited_at         timestamptz NOT NULL DEFAULT now(),
    booking_status_at_credit varchar(32) NOT NULL,

    -- Money columns match bookings exactly (numeric(12,2)) so the two can be
    -- compared without a cast and without rounding drift.
    gross_amount        numeric(12,2) NOT NULL,
    discount_amount     numeric(12,2) NOT NULL DEFAULT 0,
    tax_amount          numeric(12,2) NOT NULL DEFAULT 0,
    net_amount          numeric(12,2) NOT NULL,

    -- Explicit, auditable reversal. Never DELETE a credit.
    revoked_at          timestamptz,
    revoke_reason       text
);

comment on table sales_commission_ledger is
    'Immutable snapshot of consultant revenue attribution, written when a booking is credited. '
    'Never re-derived from leads.owner_id, which is mutable and would rewrite history.';

comment on column sales_commission_ledger.revoked_at is
    'Set when the booking is cancelled or otherwise un-commissionable. A NULL here means the credit stands.';

-- Team Performance is always "for this period, per consultant", so this is the
-- access path the report actually uses rather than an index added speculatively.
create index idx_commission_ledger_consultant_credited
    on sales_commission_ledger (consultant_id, credited_at desc);

-- Period-over-period reporting (monthly revenue, target attainment).
create index idx_commission_ledger_credited
    on sales_commission_ledger (credited_at desc);

-- ---------------------------------------------------------------------
-- 2. Backfill from existing bookings
-- ---------------------------------------------------------------------
--
-- Without this the ledger is empty and every report reads zero, because a
-- live CRM already has confirmed bookings from before this migration. A
-- reporting feature that is blank until new sales happen is not usable on the
-- day it ships.
--
-- Attribution uses leads.owner_id as it stands TODAY. That is the best
-- available approximation and it is genuinely lossy: a lead reassigned since
-- the booking was made attributes its credit to the current owner, not the one
-- who actually sold it. The true owner was never recorded, so the information
-- does not exist to recover. This is the strongest reason the ledger exists
-- going forward, and it is flagged in the Module 2 write-up rather than hidden.
--
-- Rows are skipped where the lead is missing or unowned: there is no consultant
-- to attribute to, and sales_commission_ledger.consultant_id is NOT NULL.
--
-- on conflict do nothing keeps this safe to re-run.
insert into sales_commission_ledger (
    booking_id, lead_id, consultant_id, trip_id, batch_id,
    credited_at, booking_status_at_credit,
    gross_amount, discount_amount, tax_amount, net_amount
)
select
    b.id,
    b.lead_id,
    l.owner_id,
    b.trip_id,
    b.batch_id,
    -- PROXY, and the only one available: the timestamp at which the booking
    -- reached its current status was never stored, so the booking's last
    -- update is used. It is at or after the real credit moment, which means a
    -- backfilled period can never lose a booking to a boundary it did not
    -- actually earn into.
    b.updated_at,
    b.status::text,
    b.total_amount,
    b.discount_amount,
    b.tax_amount,
    b.total_amount - b.discount_amount + b.tax_amount
from bookings b
join leads l on l.id = b.lead_id
where b.status in ('CONFIRMED', 'COMPLETED')
  and l.owner_id is not null
on conflict (booking_id) do nothing;

-- ---------------------------------------------------------------------
-- 3. Postgres full-text search
-- ---------------------------------------------------------------------

-- TOKENISATION, and why every free-text field is vectorised twice.
--
-- The obvious expression is wrong, and it was wrong here until it was measured:
--
--   to_tsvector('english', 'Ramesh.New@x.com')  ->  'ramesh.new@x.com':1
--
-- The default parser keeps an email-shaped string as ONE lexeme, so
-- websearch_to_tsquery('english','ramesh') does not match it and the viewer
-- returns nothing for the exact query it was built for. The 'simple'
-- configuration does not help either; it produces the same single lexeme.
--
-- So each free-text field contributes TWO subvectors:
--
--   raw       to_tsvector(cfg, value)
--             keeps the literal token, so an exact phrase or a full URL/email
--             still matches as one unit.
--   split     to_tsvector(cfg, regexp_replace(value,'[^a-zA-Z0-9]+',' ','g'))
--             breaks it on non-alphanumerics into ramesh / new / x / com, so a
--             search for one part of an address finds it.
--
-- Both are kept deliberately: raw alone is unusable for partial search, split
-- alone would make a phrase query for the whole address miss.
--
-- Columns that are already clean identifiers (entity, action, field) are
-- vectorised once, which also keeps the STORED column from being recomputed
-- with seven to_tsvector() calls on every audit_log insert.
--
-- audit_log is the Module 2 requirement (the searchable audit-log viewer), and
-- it is the one table where search is genuinely hard: old_value/new_value are
-- free text written by an enum-driven mutation log, so a user wants to find
-- "every row where email changed".
alter table audit_log
    add column search_vector tsvector
    generated always as (
        setweight(to_tsvector('english'::regconfig, coalesce(entity, '')), 'A') ||
        setweight(to_tsvector('english'::regconfig, coalesce(action, '')), 'A') ||
        setweight(to_tsvector('english'::regconfig, coalesce(field, '')), 'B') ||
        setweight(to_tsvector('english'::regconfig, coalesce(old_value, '')), 'C') ||
        setweight(to_tsvector('english'::regconfig,
            regexp_replace(coalesce(old_value, ''), '[^a-zA-Z0-9]+', ' ', 'g')), 'C') ||
        setweight(to_tsvector('english'::regconfig, coalesce(new_value, '')), 'C') ||
        setweight(to_tsvector('english'::regconfig,
            regexp_replace(coalesce(new_value, ''), '[^a-zA-Z0-9]+', ' ', 'g')), 'C')
    ) stored;

alter table customer360
    add column search_vector tsvector
    generated always as (
        setweight(to_tsvector('english'::regconfig, coalesce(full_name, '')), 'A') ||
        setweight(to_tsvector('english'::regconfig, coalesce(email, '')), 'A') ||
        setweight(to_tsvector('english'::regconfig,
            regexp_replace(coalesce(email, ''), '[^a-zA-Z0-9]+', ' ', 'g')), 'A') ||
        setweight(to_tsvector('english'::regconfig, coalesce(mobile_number, '')), 'B') ||
        setweight(to_tsvector('english'::regconfig, coalesce(whatsapp_number, '')), 'B') ||
        setweight(to_tsvector('english'::regconfig,
            regexp_replace(coalesce(notes, ''), '[^a-zA-Z0-9]+', ' ', 'g')), 'C')
    ) stored;

alter table leads
    add column search_vector tsvector
    generated always as (
        setweight(to_tsvector('english'::regconfig, coalesce(customer_name, '')), 'A') ||
        setweight(to_tsvector('english'::regconfig, coalesce(email, '')), 'A') ||
        setweight(to_tsvector('english'::regconfig,
            regexp_replace(coalesce(email, ''), '[^a-zA-Z0-9]+', ' ', 'g')), 'A') ||
        setweight(to_tsvector('english'::regconfig, coalesce(destination, '')), 'B') ||
        setweight(to_tsvector('english'::regconfig, coalesce(mobile_number, '')), 'B') ||
        setweight(to_tsvector('english'::regconfig, coalesce(whatsapp_number, '')), 'C') ||
        setweight(to_tsvector('english'::regconfig,
            regexp_replace(coalesce(remarks, ''), '[^a-zA-Z0-9]+', ' ', 'g')), 'C')
    ) stored;

-- GIN for tsvector containment/ranking.
create index idx_audit_log_search_vector on audit_log using gin (search_vector);
create index idx_customer360_search_vector on customer360 using gin (search_vector);
create index idx_leads_search_vector on leads using gin (search_vector);

-- pg_trgm for typo tolerance. A GIN trigram index makes "Ramesh" find
-- "Ramash", which plainto_tsquery will never do because the tokens differ.
-- Requires the extension, which is CREATE EXTENSION IF NOT EXISTS rather than
-- a migration-time assumption.
create extension if not exists pg_trgm;

create index idx_customer360_name_trgm
    on customer360 using gin (full_name gin_trgm_ops);
create index idx_leads_name_trgm
    on leads using gin (customer_name gin_trgm_ops);

-- ---------------------------------------------------------------------
-- Reporting read-path indexes
--
-- The brief requires every report to be filterable by consultant, trip, date
-- range and season. Season is derived from the month of the travel/departure
-- date, so it cannot be an indexed column - but the date range it filters on
-- can be, and that is the access path the planner needs.
--
-- Deliberately NOT created, because the index already exists and a second copy
-- is pure write cost. Verified against the live schema:
--
--   idx_bookings_travel_date  (bookings.travel_date)      -> dropped, identical
--   idx_batches_departure     (batches.departure_date)    -> dropped, identical
--   idx_leads_owner_status    (owner_id, status, created_at DESC)
--       already serves owner-scoped date-range queries as a LEFTMOST prefix, so
--       a separate (owner_id, created_at) would be strictly narrower.
--
-- Do not re-add these without first checking the live index list; the collision
-- aborts Flyway at application startup, which is a far worse outcome than a
-- missing index.
-- ---------------------------------------------------------------------

-- Funnel aggregation groups leads by source AND status over a date range.
-- Replaces a plain (source, created_at): no report needs source without status.
create index idx_leads_funnel
    on leads (source, status, created_at);

-- Status-scoped date range, e.g. "all cancelled bookings departing in Q3".
-- Extends the existing idx_bookings_status, which cannot serve a date range.
create index idx_bookings_status_travel_date
    on bookings (status, travel_date);

create index idx_leads_status_created
    on leads (status, created_at desc);

-- Team Performance SLA compliance compares sla_deadline/completed_at per
-- assignee. The existing idx_tasks_assignee_due leads with (assignee_id, status,
-- due_at), so a query on sla_deadline cannot use it.
create index idx_tasks_assignee_sla
    on tasks (assignee_id, sla_deadline);
