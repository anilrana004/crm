# SecureTravels CRM — Database Design

> **Single source of truth: `backend/src/main/resources/db/migration/`** —
> Flyway applies schema, `ddl-auto: validate` prevents drift. This document
> is a living map and must be updated in the same change as any new
> migration. Migrations shipped: **V1 → V13** (V1–V12 Phase 1–2; V12 = Module 4
> WhatsApp + timeline; **V13 = Phase 3 Module 2** reporting ledger + search).

Conventions used throughout:

- UUID PKs (`gen_random_uuid()`), `timestamptz` audit columns, `numeric(12,2)`
  money, `version bigint` optimistic locking on mutable rows.
- **Enums are varchar + named CHECK constraints** (not native PG enum types)
  so Hibernate `@Enumerated(STRING)` binds without JDBC casts while keeping
  referential guarantees.
- Every mutable table carries `created_at`, `updated_at`; append-only tables
  carry `created_at` only.
- Soft-delete is avoided; real deletions are rare and deliberate (a lead that
  turns out to be spam is handled via workflow, not row deletion).

---

## 1. Identity & auth

### `users`
| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| email | varchar(255) UNIQUE | |
| password_hash | varchar(100) | BCrypt strength 12 |
| full_name | varchar(120) | |
| role | varchar(30) | `SALES, OPS, MANAGER, ADMIN, CEO` |
| phone | varchar(20) | |
| is_active | boolean | inactive users excluded from round-robin assignment |
| created_at / updated_at / version | | |

### `refresh_tokens`
| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK → users | ON DELETE CASCADE |
| token_hash | varchar(64) UNIQUE | **SHA-256 of raw token — plaintext never stored** |
| expires_at | timestamptz | 7 days |
| revoked | boolean | rotated on every use |
| replaced_by | varchar(64) | hash of the rotated-out token (reuse detection) |

---

## 2. Trip catalogue (trips / batches / guides / seat_holds)

### `trips`
| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| name | varchar(255) | |
| slug | varchar(100) UNIQUE | |
| category | varchar(30) | `TREK, PILGRIMAGE, LEISURE, CUSTOM` |
| **booking_type** | varchar(30) | `FIXED_BATCH \| CUSTOM_FIT` — **see ADR 0001** |
| base_cost | numeric(12,2) | |
| duration_days | int | `> 0` |
| itinerary / inclusions / exclusions | text | OWASP-sanitized HTML on write |
| is_active | boolean | |

### `guides`
| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| full_name | varchar(120) | |
| phone | varchar(20) | |
| daily_rate | numeric(10,2) | |
| is_active | boolean | |

### `batches` (FIXED_BATCH departures only)
| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| trip_id | uuid FK → trips | |
| departure_date | date | UNIQUE per trip |
| max_capacity | int | `> 0` |
| seats_booked | int | **derived + stored**: CONFIRMED bookings + active HELD holds (invariant I3) |
| guide_id | uuid FK → guides | |
| transport_plan | text | |
| status | varchar(20) | `OPEN, CLOSED, CANCELLED` |

### `seat_holds` (2-hour provisional hold)
| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| batch_id | uuid FK → batches | |
| booking_id | uuid FK → bookings | backfilled on confirm |
| num_seats | int | `> 0` |
| held_until | timestamptz | auto-released when expired |
| status | varchar(20) | `HELD, CONFIRMED, RELEASED, EXPIRED` |

---

## 3. Leads (pipeline front)

### `leads`
| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| customer_name | varchar(200) | |
| mobile_number | varchar(30) | |
| mobile_digits | varchar(20) | normalized for dedup |
| whatsapp_number / email | varchar | optional |
| source | varchar(30) | `GOOGLE_ADS, FACEBOOK_ADS, INSTAGRAM, WEBSITE, WHATSAPP, REFERRAL, JUSTDIAL, WALK_IN, B2B, EXISTING_CUSTOMER, OTHER` |
| destination | varchar(120) | |
| trip_id | uuid FK → trips | |
| travel_date | date | |
| num_persons | int | 1..50 at API boundary |
| budget | numeric(12,2) | `>= 0`, ≤12 int digits, ≤2 fraction digits |
| owner_id | uuid FK → users | |
| status | varchar(20) | `NEW → INTERESTED → QUOTATION_SENT → BOOKING_CONFIRMED` / `LOST` |
| heat | varchar(10) | `HOT, WARM, COLD` — rule-based (ADR 0004) |
| follow_up_date | date | |
| remarks | text | XSS-sanitized |
| consent_given | boolean | **DPDPA — mandatory true to create** |
| consent_captured_at / consent_scope | | |
| lost_reason | varchar(30) | `PRICE_TOO_HIGH, DATES_UNAVAILABLE, CHOSE_COMPETITOR, WENT_SILENT, NOT_GENUINE, POSTPONED` |
| duplicate_of_lead_id | uuid FK → leads | duplicate soft-linking |
| last_contacted_at | timestamptz | |
| customer360_id | uuid FK → customer360 | written when phone matches a canonical customer |
| created_by | uuid FK → users | NULL for webhook/website leads (=system) |
| created_at / updated_at / version | | |

---

## 4. Customers & bookings

### `customer360` (canonical person record; DPDPA source of truth)
| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| full_name | varchar(200) | |
| mobile_number / mobile_digits | | UNIQUE on mobile_digits and email |
| whatsapp_number / email | | |
| consent_given / consent_captured_at / consent_scope | | canonical consent |
| marketing_opt_in | boolean | explicit, separate flag |
| total_trips | int | derived counters |
| last_trip_date | date | |
| total_spent | numeric(12,2) | |
| suggest_offer | varchar(200) | |
| offer_tags | text[] | remarketing tags (Kashmir, Char Dham, …) |

### `bookings`
| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| booking_ref | varchar(20) UNIQUE | |
| trip_id | uuid FK → trips | |
| batch_id | uuid FK → batches | FIXED_BATCH only (I2) |
| customer_id | uuid FK → customer360 | |
| lead_id | uuid FK → leads | **originating lead** (added V4) |
| booking_type | varchar(30) | copy of trip.booking_type (I1) |
| num_travellers | int | `> 0` |
| total_amount / discount_amount / tax_amount | numeric(12,2) | discounts require `discount_approved_by` at UI level |
| status | varchar(20) | `QUOTATION, CONFIRMED, COMPLETED, CANCELLED` |
| travel_date | date | |
| discount_approved_by | uuid FK → users | |
| notes / created_by | | |

**Invariants enforced by trigger `trg_booking_consistency`:**
- **I1** `bookings.booking_type` must equal `trips.booking_type`.
- **I2** FIXED_BATCH requires a `batch_id` of the same trip; CUSTOM_FIT must have `batch_id IS NULL`.
- **I3** `batches.seats_booked` = CONFIRMED bookings + active HELD holds (service-enforced under `PESSIMISTIC_WRITE`).

### `travellers`
| Field | Type | Notes |
|---|---|---|
| id / booking_id (FK, CASCADE) / customer360_id (FK) | | PII links upward when identifiable |
| full_name | varchar(200) | |
| age / gender / phone | | |
| medical_cert_required | boolean | |

---

## 5. Payments

### `payments`
| Field | Type | Notes |
|---|---|---|
| id / booking_id (FK) | | |
| amount | numeric(12,2) | `> 0` |
| amount_type | varchar(20) | `ADVANCE, BALANCE, FULL` |
| status | varchar(20) | `PENDING, PARTIAL, COMPLETED, OVERDUE, CANCELLED, REFUNDED` |
| due_date / paid_at | | CHECK: `STATUS=COMPLETED ⇒ paid_at NOT NULL` |
| gateway_ref | varchar(120) | **never raw card data** (gateway lands Phase 2) |
| recorded_by / notes | | |

---

## 6. Operations

### `operations_handoffs` (auto-created once on booking CONFIRMED — invariant I6)
| Field | Type | Notes |
|---|---|---|
| id / booking_id (FK, **UNIQUE**) / batch_id (FK) | | one ops record per booking |
| ops_ref | varchar(20) UNIQUE | e.g. `TOH-2026-0002` |
| travel_date / pax | | `pax > 0` |
| hotel_status | | `NOT_ARRANGED, PENDING, CONFIRMED` |
| transport_status | | same triad |
| guide_id | uuid FK → guides | |
| driver_id | uuid | Phase-2 vendor record |
| payment_status | | mirrors payments tri-state |
| trip_sheet_generated_at | timestamptz | |

---

## 7. Tasks & notifications

### `tasks` (automation engine, Phase-1 form)
| Field | Type | Notes |
|---|---|---|
| id / lead_id (FK) / booking_id (FK) / assignee_id (FK users) | | assignee required |
| type | varchar(30) | `INITIAL_CALL, FOLLOW_UP_1D, FOLLOW_UP_3D, FOLLOW_UP_8D, FOLLOW_UP_15D, QUOTATION, PAYMENT_REMINDER, OPS, REVIEW, CUSTOM` (cadence per spec §19.2: cumulative +1/+3/+8/+15; codified by migration V7) |
| status | | `PENDING, COMPLETED, OVERDUE, CANCELLED` |
| due_at | timestamptz | |
| sla_deadline / completed_at / escalated_at / notes | | |

### `notifications`
| Field | Type | Notes |
|---|---|---|
| id / user_id (FK) | | |
| channel | | `IN_APP, EMAIL` |
| title / body / link | | |
| is_read / read_at | | |

---

## 8. Documents (Phase 2 workflows; entity since Phase 1)

### `documents`
| Field | Type | Notes |
|---|---|---|
| id / related_type | | `TRAVELLER, BOOKING, LEAD` |
| traveller_id / booking_id (FK) | | |
| doc_type | | `ID_PROOF, MEDICAL_CERT, TRIP_PHOTO` |
| storage_key | varchar(300) | **S3 object key — file bytes never in Postgres** |
| mime_type / size_bytes / uploaded_by | | |

---

## 9. Dashboard & targets

### `sales_targets`
| Field | Type | Notes |
|---|---|---|
| id / user_id (FK) | NULL = company-wide row for the month |
| month | date | first day of target month |
| target_bookings / target_revenue | numeric(12,2) | at least one > 0 |
| created_by | | |
| — | | UNIQUE (user, month) where user not null; UNIQUE (month) where user null |

---

## 10. Audit

### `audit_log` (append-only)
| Field | Type | Notes |
|---|---|---|
| id / entity / entity_id | | |
| action | | `CREATE, UPDATE, STATUS_CHANGE, DELETE, LOGIN, LOGOUT` |
| field / old_value / new_value | | |
| actor_id (FK users) | | NULL = system/webhook |
| created_at / seq (bigserial) | | monotonic key for activity timelines |

---

## 11. Communications — Module 4 (`V12__whatsapp_communication_timeline.sql`)

### `whatsapp_templates` (reference data, seeded by V12)
The nine approved templates. Reference data, not configuration churn: Interakt
has no template-management API, so the Interakt side is created in the dashboard
and this table mirrors it.

| Field | Type | Notes |
|---|---|---|
| id | bigserial | |
| code | varchar(40) unique | internal key, e.g. `BOOKING_CONFIRMED` |
| interakt_name | varchar(80) unique | the **dashboard** name, e.g. `securetravels_booking_confirmed` |
| label / language_code | varchar | `languageCode` is always `en` today |
| expected_params | int | positional `bodyValues` count; enforced in `enqueue` so a caller bug is a 400, not a wasted provider quota |
| enabled | boolean | operational toggle; a disabled template is a permanent failure, never a retry |

`TRIP_LOGISTICS` deliberately merges hotel, driver, and pickup, which is what
brings the catalogue to nine distinct templates.

### `whatsapp_messages` (one row per outbound message)
| Field | Type | Notes |
|---|---|---|
| id | uuid | also the queue payload in `BROKER` mode |
| subject_type / subject_id | varchar(20) / uuid | polymorphic `LEAD, CUSTOMER, BOOKING`; **no FK** — the owning module owns the row |
| template_code | varchar(40) | logical reference to `whatsapp_templates` |
| recipient_mobile | varchar(20) | normalised digits; correlated to inbound replies by this |
| country_code | varchar(6) | quoted (`"+91"`); the documented Interakt trap |
| body_values | text[] | positional `{{1}}..{{n}}` values |
| status | varchar(16) | `QUEUED, SENDING, SENT, DELIVERED, READ, FAILED, DEAD_LETTERED` |
| attempts | int | advanced by the same `UPDATE` that claims the row, so the two cannot drift |
| provider / provider_message_id | varchar | |
| callback_data | varchar(64) unique | `st-<uuid>`; **unique** makes a replayed send a constraint violation rather than a second message |
| queued_at / sent_at / delivered_at / read_at | timestamptz | |
| last_error / channel_error_code / channel_failure_reason | varchar | Interakt's error code is a *string* (e.g. `1013`) |
| created_at / updated_at | timestamptz | |

Indexes: partial on `(status, queued_at)` for the `SENDING` recovery sweep, and
a unique `callback_data` for idempotency.

### `timeline_events` (unified Lead/Customer/Booking timeline)
| Field | Type | Notes |
|---|---|---|
| id / seq | uuid / bigserial | `seq` breaks ties within one timestamp |
| subject_type / subject_id | varchar(20) / uuid | polymorphic, no FK |
| direction | varchar(10) | `INBOUND, OUTBOUND` |
| channel | varchar(20) | `WHATSAPP` (future: `EMAIL, SYSTEM`) |
| kind | varchar(30) | `TEMPLATE_QUEUED, TEMPLATE_SENT, TEMPLATE_DELIVERED, TEMPLATE_READ, TEMPLATE_FAILED, REPLY_RECEIVED, MEDIA_RECEIVED, BUTTON_CLICKED, SYSTEM_NOTE` |
| template_code / summary / body | | `body` is inbound customer text; never store it in a log line |
| provider / provider_message_id / actor_mobile | | correlation |
| body_values | jsonb | what was actually sent, for audit |
| created_at | timestamptz | |

**Unique `(provider, provider_message_id, kind)`** is the backstop that makes
re-delivered webhooks harmless. The service also checks for existence first, so
a duplicate becomes a no-op rather than a 500 that provokes another retry.

Deliberately **separate from `audit_log`**: audit answers "who changed what",
the timeline answers "what did this customer receive and say". Conflating them
produces a log nobody can read.

---

## 12. Reporting — Phase 3 Module 2 (`V13__reporting_commission_ledger_and_fts.sql`)

### `sales_commission_ledger` (credited revenue snapshot)
| Field | Type | Notes |
|---|---|---|
| id | uuid | |
| booking_id | uuid **unique** | one credit per booking — `ON CONFLICT DO NOTHING` makes re-credit harmless |
| lead_id / customer_id (FK) | | customer from `bookings.customer_id`; lead via `bookings.lead_id` |
| consultant_id (FK users) | | **owner at confirmation**, snapshotted — `leads.owner_id` is mutable |
| trip_id / batch_id (FK) | | for trip-level attribution |
| source | varchar(30) | `Web, Referral, Walk-in, ...` (string, not a lookup) |
| gross_amount / discount_amount / net_amount | numeric(12,2) | `net_amount` ties to `bookings` (IT-verified) |
| base_cost_assumed | numeric(12,2) | derived; see Phase 9 for true P&L |
| effective_net_amount | numeric(12,2) | source of the report's "net booked value" |
| credited_at | timestamptz | confirmed-at time |
| revoked_at / revoked_by (FK users) | timestamptz / uuid **null** | **revoke never deletes**; cancelled booking keeps its row |
| version | bigint | |

One row per confirmed booking. `revoked_at` set on cancellation. Direct DML
never changes it — only `CommissionLedgerService` writes.

### FTS search vectors
Generated `tsvector` columns over **raw + `regexp_replace`-stripped** text
(single vectors lose partial-email matches, e.g. `Ramesh.New@x.com` becomes one
lexeme):

| Table | Column | Contents |
|---|---|---|
| `customer360` | `full_name_search` / `email_search` | name; email |
| `customer360` | trigram on `mobile_number` | typo-tolerant number search |
| `leads` | `contact_search` | name + email + mobile |
| `bookings` | `reference_search` | ref + customer name + trip name |

GIN indexes on each vector; `pg_trgm` GIN on mobile. Audit search ranks by
`ts_rank` + `similarity()` and reports `matchedBy`.

### Indexes added
`idx_bookings_travel_date`, `idx_leads_travel_date`, `idx_leads_owner_created`,
`idx_batches_departure_date`, `idx_customer360_created_at`,
`idx_operations_handoffs_departure`. (`idx_bookings_travel_date` etc. were
verified against the live schema as genuinely missing before being created —
duplicate/prefix-covered indexes were **not** re-added.)

### Backfill (ratified)
Two eligible bookings were credited to their *current* `leads.owner_id` with
`credited_at = bookings.updated_at`. This is documented as **lossy**: the owner
at confirmation was never recorded before Module 2, so historical attribution is
"as of today" by stated policy. `ON CONFLICT (booking_id) DO NOTHING` guards the
rerun.

---

## 13. Module 9 — webhook automation

### `assignment_state` (round-robin sales-assignment cursor)
| Field | Type | Notes |
|---|---|---|
| id / user_id (FK) | | one row per (user, month) |
| month | date | resets alignment per month |
| last_assigned_at | timestamptz | least-recently-assigned first |
| leads_assigned_this_month | int | |

### `webhook_logs` (every inbound call, audit)
| Field | Type | Notes |
|---|---|---|
| id / source | | e.g. `WEBSITE` |
| payload | text | bounded to 10,000 chars at write |
| lead_id (FK) | | set on success |
| status | | `success, duplicate, failed` |
| error_message | text | |
| created_at | timestamptz | indexed |

---

## 14. Relationship map

```
users 1─* refresh_tokens
users 1─* leads.owner_id        users 1─* tasks.assignee_id
users 1─* audit_log.actor_id    users 1─* sales_targets.user_id
users 1─* payments.recorded_by  users 1─* assignment_state.user_id

trips 1─* batches              trips 1─* leads.trip_id
batches 1─* seat_holds         trips 1─* bookings
batches 1─* operations_handoffs
guides 1─* batches.guide_id    guides 1─* operations_handoffs.guide_id

leads 1─0..1 customer360        leads 1─0..1 duplicate leads
leads 1─0..* bookings.lead_id   leads 1─* tasks.lead_id
leads 1─* audit_log.lead        leads 1─* webhook_logs.lead_id

customer360 1─* bookings        customer360 1─* travellers
bookings 1─* travellers         bookings 1─* payments
bookings 1─1 operations_handoffs
bookings 1─* tasks.booking_id   bookings 1─* documents.booking_id
bookings 1─0..1 sales_commission_ledger.booking_id   (revocable credit)

sales_commission_ledger *─1 users.consultant_id   sales_commission_ledger *─1 leads.lead_id

whatsapp_templates 1─0..1 whatsapp_messages  (logical ref on template_code, no FK)
whatsapp_messages  1─0..* timeline_events     (same subject_type/subject_id, no FK)
```

## 15. Booking-type decision

FIXED_BATCH vs CUSTOM_FIT is the **foundational schema decision** of the
Phase-1 build (it shapes `trips`, `batches`, `seat_holds`, `bookings`, and
the I1/I2/I3 invariants). Full rationale: **ADR `0001-booking-type-model`**.

## 16. Change discipline

- Every new table/column ships as a new `V{n}__*.sql` in order — **never
  auto-DDL** (`ddl-auto: validate` enforces this).
- Update this document in the same commit, and update `WebhookAutomationIT`
  / `BaseIT` truncation lists and `DATABASE.md` together.
- Money columns stay `numeric(12,2)`; never float.
