# ADR 0007 — Kafka gate: does Phase 6 automation volume justify an event broker?

- **Status:** Deferred decision — **gate closed for Phase 6 as designed; NO
  KAFKA expected.** Measured re-opening 2026-09-29; re-checked at Phase 6
  signoff and then as part of the normal Phase-ready checkpoint (per ADR 0003's
  "Kafka is first evaluated in Phase 6", which this ADR is).
- **Date:** 2026-09-29
- **Note on numbering:** the kickoff called this "ADR-0006", but `0006` is the
  build-vs-buy ADR; this is **0007**.

## Context

ADR 0003 (superseded in part by 0005) explicitly deferred Kafka to "the first
time automation volume requires it". The Phase 6 workflow engine is the first
consumer that *wants* decoupled fan-out: rules firing without being coupled
into the business transaction that triggered them. This ADR is the required
evaluation. It is a **gate with a number**, not a vibes decision.

### Measured data (as of 2026-09-29)

The production-adjacent database (`securetravels_crm`) holds **demo data only**;
there is no measured production volume to extrapolate from:

| Table | Rows (dev) |
|---|---|
| `leads` | 7 |
| `tasks` | 6 |
| `notifications` | 12 |
| `whatsapp_messages` / `timeline_events` | 0 |
| `bookings` / `payments` | 0 |
| `audit_log` (dated 2026-09-28) | 1 |
| `inbound_messages` | 1 |
| `webhook_logs` / `customer360` | 0 |

Conclusion: **current volume cannot be measured**, so the gate below is set
from the projected design-scale model, and the same gate formula must be
re-applied on real production numbers the moment the business opens to live
traffic.

### Projected event model (design scale, single-site travel agency)

Trigger events the engine consumes per day (from `AUTOMATION_INVENTORY.md` §2):

| Event family | Avg/day | Peak/day |
|---|---|---|
| `lead.created` (+ website, inbound-reply leads) | 20 | 60 |
| `lead.updated` (including every status transition) | 80 | 240 |
| `booking.confirmed` + `booking.cancelled` | 3 | 12 |
| `payment.*` (created / updated / overdue) | 8 | 30 |
| `task.*` (created / due / overdue / SLA) | 40 | 120 |
| `message.queued` (WhatsApp + Email + SMS, delivery plane) | 30 | 90 |
| `inbound.received` (replies + opt-outs) | 10 | 40 |
| `document.uploaded` | 5 | 20 |
| `compliance.item_updated` | 10 | 40 |
| `batch.seats_updated` (+ seat-hold events) | 6 | 24 |
| **Total domain events/day** | **≈ 212** | **≈ 676** |

With workflow fan-out (each event evaluated against all ACTIVE workflows; the
172 template library is 8–30 definitions), the engine does at most:

- **≈ 6,000–20,000 workflow evaluations/day** at 10× peak (676 × 30),
  i.e. **2–10 evaluations/sec** sustained.

An in-process loop indexed by trigger name handles this with single-digit
microsecond to low-millisecond work per evaluation, in the same JVM as the
trigger. This is several orders of magnitude below anything that warrants a
broker.

## Decision rule (the gate)

**Do not adopt Kafka for Phase 6.** Re-open (and raise a new ADR) only if **at
least one** of the following is observed on *production* data:

1. **Volume trip:** measured sustained **> 5,000 trigger events/day over 8
   consecutive weeks** (≥ 3× the 676/day 10× peak projection, i.e. the
   projection itself is wrong by 8×), **and** the Module-3 load test (§ below)
   at that measured rate shows the in-process engine adding material latency —
   defined as p95 trigger-emission overhead **> 10 ms**, or scheduler lag that
   moves the `WAIT` poller past its 5-minute alert threshold, or a deadlock/
   lock-wait cluster in `scheduled_steps`.
2. **Second consumer:** any second service/process must observe workflow state
   or trigger events that cannot read the monolith's Postgres (the ADR 0005
   "real cross-service bus" case).
3. **Replay demand:** a genuine, function-scoped need for out-of-order
   re-processing of past trigger streams (audit/analytic re-derivation at
   scale) that Postgres tables + the existing `audit_log`/`timeline_events`
   cannot serve.

If none fires, **the answer to "Kafka?" stays NO at the Phase 6 signoff and at
every later phase checkpoint**, exactly as ADR 0003/0005 intended.

## The 10× load test (required by the Phase 6 signoff)

ADR 0006's engine must demonstrate headroom. Normally this runs in the sandbox;
in this environment RabbitMQ-dependent tests are skipped (no Docker), so this
test is written as an **opt-in IT that runs against the real engine with
`app.messaging.mode=INLINE`** (broker-free), driven through the controller
path, and documented for the operator. Passing bars — all must hold at
**10× projected peak = 6,760 trigger events/day, played as a burst at
10× instantaneous peak**:

1. Every trigger emission completes within its business transaction with p95
   added overhead **≤ 10 ms** (fan-out evaluation included).
2. All effects are exactly-once: replaying the same trigger stream (crash
   between effect/commit and commit/ack) yields zero duplicate outbox rows or
   twice-applied `CREATE_TASK`/`SEND_MESSAGE`/`UPDATE_FIELD` effects.
3. Durable `WAIT` steps scheduled 30s/5m/1h ahead all fire after a mid-test
   application restart.
4. `scheduled_steps` poller lag stays **< 5 min** (NoSKIP-lock contention below
   alert threshold); no deadlocks.
5. Daily send cap and causal-depth caps hold under the burst (no runaway runs).

The recorded number (events/sec sustained, max lag, p95 overhead) goes into
`PHASE_6_SIGNOFF.md`.

## Why the broker is still not the Phase 6 contract, in one paragraph

The engine's needs — durable, retryable, exactly-once effects — are met by the
same transaction the trigger already performs (outbox/dedup row committed with
the effect) plus a `scheduled_steps` table polled under `SKIP LOCKED`. That is
the Phase 5 dispatch pattern re-applied. A broker would add a second state
store, a new operator surface, ordering/acks to debug, and a hard dependency
for a measurably tiny event rate — precisely what ADR 0002/0003/0005 have
consistently kept out of a 1-instance, 1-Postgres build.

## Consequences

- RabbitMQ stays exactly where ADR 0005 put it: outbound communication
  delivery only. The engine publishes nothing to it.
- The trigger path stays synchronous in-process: `EventPublisher` → matching
  workflow scan → condition eval → step execution, all inside the trigger's
  transaction (or after-commit where a step must not roll back with it).
- `AUTOMATION_INVENTORY.md` §2 remains the master list of emit points; the
  emit surface is stable and id-growth is the re-evaluation signal.
- If gate trigger #1 ever fires, the recorded number already tells us what
  Kafka would need to carry and at what rate.

## Revisit triggers (superset, for the record)

1. Any of the three gate conditions above.
2. A second office/region (Phase 4 reassessment conditions) splits Postgres
   into multiple instances and something must fan events across them — at that
   point the *backbone* question is a new infrastructure decision, not an
   automation-scope decision.
3. Kafka is independently required by a later phase's exhaust/analytics
   (e.g. Phase 10 BI pipelines) — that is a separate ADR, this one does not
   pre-approve it.