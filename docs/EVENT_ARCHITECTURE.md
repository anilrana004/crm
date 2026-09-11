# SecureTravels CRM — Event & Async Architecture

> **Status: ratified since Phase 1 Prompt 3.** Decision record: **no message
> broker until Phase 6** — see ADR `0003-no-message-broker-until-phase-6`.

---

## 1. Current model (Phase 1–2)

Three mechanisms, deliberately ordered by cost:

1. **Synchronous service calls** — the default. A booking confirm synchronously
   creates the ops handoff, updates the lead, writes audit rows. The caller
   needs the outcome to render a response (`createBooking(...)` must know
   whether it succeeded).
2. **`@Async` fire-and-forget** — for work that does not need to block the
   response but must still be *observable*: sending the in-app/email
   notification after booking confirmation, follow-up task scheduling that
   isn't on the request hot path. Failures are logged, never swallowed.
3. **Scheduled sweeps** — for *time* rather than *event* logic:
   - `SeatHoldSweep` — releases 2-hour seat holds that expired.
- `FollowUpSweep` — materialises the +1/+3/+8/+15d follow-up ladder and
      SLA escalation on `tasks`.
   - `PaymentSweep` — flags `PENDING`/`PARTIAL` payments `OVERDUE` and emits
     balance reminders.
   - `TaskAutomationIT` / `WebhookAutomationIT` cover sweep behaviour
     (advancing a clocked due_at in tests).

These sweeps are idempotent and safe to run more than once — that is the
contract any future sweep must keep.

## 2. What events exist today (conceptual table)

| Event | Producer | Consumers | Mechanism |
|---|---|---|---|
| `lead.created` (UI) | `LeadService` | task engine (5-min INITIAL_CALL), notification bell, audit | sync + @Async notify |
| `lead.created` (webhook) | `WebhookService` | round-robin assignment, INITIAL_CALL task, notify | sync + @Async notify |
| `lead.status_changed` | `LeadService` | follow-up ladder, lost-reason/reopen logic, audit | sync |
| `booking.confirmed` | booking service | ops handoff, lead stepper, payment reminders | sync + @Async notify |
| `seat_hold.expired` | `SeatHoldSweep` | batch seats recompute | sweep |
| `payment.overdue` | `PaymentSweep` | reminders | sweep |
| `notification.send` | any | in-app rows / email stub | @Async |

## 3. Why no broker — and the Phase-6 revisit

- **Why now:** a broker adds an always-on dependency, ordering/durability
  semantics, and replay logic. Phase 1–2 volume and single-writer ownership
  don't need it; in-process calls + sweeps keep every test hermetic and every
  failure visible in the unit it belongs to.
- **Why not forever:** `automation/` (Phase 6) — user-configured
  trigger→condition→action rules are exactly the kind of cross-cutting fan-out
  that a durable queue makes tractable (rules firing without being coupled to
  the business transaction that triggered them).
- **Revisit trigger (from ADR 0003):** automation rule volume, a genuine
  requirement for at-least-once delivery across failures, or a second consumer
  of an event that can't accept the current synchronous path. Evaluation
  happens during Phase 6 planning with concrete numbers, never speculatively.
- **Redis lands in Phase 2** (shared rate limiting + cache for the automation
  rules cache); RabbitMQ is *evaluated* there only for the notification/queue
  need if volume already demands it, with explicit sign-off per the ADR.

## 4. Contract for every future async path

1. Async work must be idempotent (retries safe).
2. Async work must not silently die — a failure is logged with context.
3. Events that change business state stay synchronous; async is for
   *notifications* and non-critical fan-out.
4. No new always-on external runtime dependency without an ADR.