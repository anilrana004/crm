# ADR 0001 — FIXED_BATCH vs CUSTOM_FIT booking model

- **Status:** Accepted (Phase 1 Prompt 1) · Ratified (Phase 1 Prompt 3)
- **Date:** 2026-09
- **Deciders:** Project owner + build lead

## Context

A Himalayan trekking / pilgrimage operator sells two fundamentally different
products under one roof:

1. **Fixed departures** — e.g. "Kedarnath Yatra batch of 12th May" — seats
   sold against a known departure, capacity-limited (max pax), with strong
   incentives to *hold* seats while a customer decides.
2. **Custom-fit itineraries** — a group of 4 wants "any 6 days in June,
   our own route" — no batch, no seat pool, quoted and priced per-question.

A generic CRM would model both as one amorphous "Opportunity" row. That loses
the seat-inventory mechanics of case 1 and bloats case 2 with meaningless
fields. Worse, retrofitting the distinction after bookings exist is a data
migration with real business risk (a mischaracterized booking corrupts
inventory numbers).

## Decision

Make `booking_type` a **first-class, foundational discriminator**, not a bolt-on:

- `trips.booking_type` ∈ `{FIXED_BATCH, CUSTOM_FIT}` decides the rest of the
  data model *at schema level*.
- `batches` exist **only** for FIXED_BATCH trips; `seat_holds` (2-hour
  provisional inventory holds) bind to batches.
- `bookings` carry their own copy of `booking_type`, and the consistency
  rules are enforced by the **DB trigger `trg_booking_consistency`** so no
  code path can produce an inconsistent row:
  - **I1** — `bookings.booking_type` must equal `trips.booking_type`.
  - **I2** — FIXED_BATCH requires a `batch_id` belonging to that trip;
    CUSTOM_FIT must have `batch_id IS NULL`.
- **I3** — `batches.seats_booked` is derived (CONFIRMED bookings + active
  HELD holds), mutated only under `PESSIMISTIC_WRITE` in the service.

## Consequences

- Inventory math is trustworthy *by construction* (DB-enforced), which is
  worth more than any ORM convenience.
- Custom-fit bookings skip batch/seat mechanics entirely — simpler service
  logic and no misleading UI.
- Cost: a `booking_type` column must exist on every trip and booking; new
  queries must respect I1/I2 (the trigger turns mistakes into loud errors).
- Landing in Phase 1 (not Phase 7) is deliberate: sales volume and seat
  pressure is exactly where a travel operator starts; schema decisions made
  under zero legacy data are cheap, later ones are not.

## Alternatives considered

- Single flexible "Opportunity" model (rejected — no seat integrity, generic).
- Two separate tables `fixed_bookings` / `custom_bookings` (rejected — a
  trip is *almost never* both, but a booking always belongs to one trip;
  one shared table + discriminator expresses reality with the fewest joins).