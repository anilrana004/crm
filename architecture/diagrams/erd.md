# Entity-Relationship Diagram — SecureTravels CRM (Phase 1, V1–V6)

Mermaid `erDiagram` mirroring `docs/DATABASE.md`. Notation: `}o` = zero-or-more,
`}|` = exactly-one, `|o` = zero-or-one.

```mermaid
erDiagram
    USERS ||--o{ REFRESH_TOKENS : "owns"
    USERS ||--o{ LEADS : "owns (owner_id)"
    USERS ||--o{ TASKS : "assignee"
    USERS ||--o{ SALES_TARGETS : "target for"
    USERS ||--o{ ASSIGNMENT_STATE : "round-robin cursor"
    USERS ||--o{ AUDIT_LOG : "actor"
    USERS ||--o{ PAYMENTS : "recorded_by"

    TRIPS ||--o{ BATCHES : "departures"
    TRIPS ||--o{ LEADS : "interested in"
    TRIPS ||--o{ BOOKINGS : "bought as"
    BATCHES ||--o{ SEAT_HOLDS : "2h holds"
    BATCHES ||--o{ BOOKINGS : "FIXED_BATCH only (I2)"
    BATCHES ||--o{ OPERATIONS_HANDOFFS : "ops for departure"
    GUIDES ||--o{ BATCHES : "leads"
    GUIDES ||--o{ OPERATIONS_HANDOFFS : "assigned"

    LEADS |o--o{ LEADS : "duplicate_of"
    LEADS |o--o| CUSTOMER360 : "matched person"
    LEADS ||--o{ BOOKINGS : "originating lead"
    LEADS ||--o{ TASKS : "follow-ups"
    LEADS ||--o{ AUDIT_LOG : "history"
    LEADS ||--o{ WEBHOOK_LOGS : "inbound audits"
    LEADS |o--o| TRIPS : "preferred"

    CUSTOMER360 ||--o{ BOOKINGS : "customer"
    CUSTOMER360 ||--o{ TRAVELLERS : "person"
    BOOKINGS ||--o{ TRAVELLERS : "roster"
    BOOKINGS ||--o{ PAYMENTS : "amounts"
    BOOKINGS ||--o{ TASKS : "booking tasks"
    BOOKINGS ||--o{ DOCUMENTS : "booking docs"
    BOOKINGS |o--o| OPERATIONS_HANDOFFS : "auto on CONFIRMED (I6)"

    BOOKINGS {
        uuid id PK
        varchar booking_ref UK
        varchar booking_type "FIXED_BATCH | CUSTOM_FIT (I1)"
        varchar status "QUOTATION | CONFIRMED | COMPLETED | CANCELLED"
        numeric total_amount
    }
    TRIPS {
        uuid id PK
        varchar slug UK
        varchar booking_type "FIXED_BATCH | CUSTOM_FIT"
        numeric base_cost
    }
    BATCHES {
        uuid id PK
        date departure_date
        int max_capacity
        int seats_booked "derived (I3)"
    }
    LEADS {
        uuid id PK
        varchar status "NEW..BOOKING_CONFIRMED | LOST"
        varchar heat "HOT | WARM | COLD"
        boolean consent_given "DPDPA"
        varchar source
    }
    CUSTOMER360 {
        uuid id PK
        varchar mobile_digits UK
        boolean marketing_opt_in
        int total_trips
        numeric total_spent
    }
    PAYMENTS {
        uuid id PK
        varchar amount_type "ADVANCE | BALANCE | FULL"
        varchar status
        date due_date
    }
    OPERATIONS_HANDOFFS {
        uuid id PK
        uuid booking_id UK
        varchar ops_ref UK
    }
    TASKS {
        uuid id PK
        varchar type "INITIAL_CALL | FOLLOW_UP_xD | QUOTATION | PAYMENT_REMINDER | OPS | REVIEW"
        timestamptz due_at
    }
    SEAT_HOLDS {
        uuid id PK
        int num_seats
        timestamptz held_until
        varchar status "HELD | CONFIRMED | RELEASED | EXPIRED"
    }
    AUDIT_LOG {
        uuid id PK
        varchar entity
        varchar action
        text old_value
        text new_value
    }
    WEBHOOK_LOGS {
        uuid id PK
        varchar status "success | duplicate | failed"
        text payload
    }
    ASSIGNMENT_STATE {
        uuid id PK
        date month
        timestamptz last_assigned_at
    }
```

Key invariants (see `docs/DATABASE.md`):
- **I1/I2** — booking ↔ trip booking_type consistency, FIXED_BATCH ⇔ batch,
  enforced by `trg_booking_consistency`.
- **I3** — `batches.seats_booked` = CONFIRMED + active HELD, PESSIMISTIC_WRITE.
- **I6** — exactly one `operations_handoffs` per booking (created on CONFIRMED).