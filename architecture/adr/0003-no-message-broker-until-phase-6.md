# ADR 0003 — No message broker until Phase 6

- **Status:** Accepted · Ratified (Phase 1 Prompt 3)
- **Date:** 2026-09

## Context

Automation (5-min first call, follow-up ladder, payment reminders,
notifications, round-robin assignment) is event-driven in spirit. It is
tempting to introduce Kafka/RabbitMQ in Phase 1 as "the event backbone." The
costs at this stage:

- An always-on broker is another failure domain, another secret, another
  backup concern — against a single-instance, one-team reality
  (ADR 0002).
- Event ordering/durability/replay semantics are non-trivial to get right and
  make tests hermeticity hard (every request would fan out).
- The current consumers are all *synchronous-callable*: the task engine,
  notification bell, and audit are local. There is no second service that
  must learn about an event after the fact.

## Decision

Until Phase 6, events are delivered by the **cheapest mechanism that meets
the need** (see `EVENT_ARCHITECTURE.md`):

1. **Synchronous service calls** for anything whose outcome shapes the
   response (booking → ops handoff, lead → task materialisation).
2. **Spring `@Async`** for fire-and-forget notifications (idempotent, logged,
   never silently dropped).
3. **Scheduled, idempotent sweeps** for time-based logic (seat-hold expiry,
   follow-up escalation, payment overdue) instead of timestamped event
   contracts.

This is **not forgotten scope — it is sequenced.** Specifically:

- **Redis + RabbitMQ are introduced in Phase 2** for shared rate limiting,
  caching, and durable notification queues **if** volume already justifies
  it (sign-off check on the Phase-2 kickoff).
- **Kafka/event-streaming is first *evaluated* in Phase 6.** The workflow
  automation engine (configurable trigger→condition→action) is the first
  consumer that genuinely wants decoupled fan-out: rules firing without
  being coupled into the business transaction that triggered them.

## Revisit triggers (concrete, before deferring any longer)

1. A second consumer of an event exists that can't accept the current
   synchronous path (e.g. an audit sink in Phase 2, or marketing attribution
   in Phase 5).
2. Automation rule volume creates a hot-path stall measured in load tests.
3. A genuine at-least-once delivery requirement across process failures
   (payment gateway callbacks are the leading candidate in Phase 2).

No trigger met → answer remains "no broker", even in review.

## Consequences

- Tests stay hermetic and deterministic (no queue fixtures needed).
- The async contract in `EVENT_ARCHITECTURE.md` §4 governs how far we can
  stretch the in-process model before the Phase-6 evaluation.
- When we do add a broker, the `automation/` package boundary (already
  reserved) is the seam it plugs into.