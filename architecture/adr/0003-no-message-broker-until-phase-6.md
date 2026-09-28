# ADR 0003 — No message broker until Phase 6

> **SUPERSEDED (2026-09-27) by [ADR 0005](0005-rabbitmq-for-phase-2-communication-delivery.md).**
>
> The hard gate set in the Phase 1 closeout below was re-verified at the Phase 2
> kickoff, as that gate required. It opened: Module 4's customer-facing WhatsApp
> delivery meets revisit trigger #3 (a genuine at-least-once requirement across
> process failure) and #1 (a second consumer that cannot accept the synchronous
> path). **Kafka remains deferred to Phase 6** and Redis Streams remains
> rejected. This file is kept for history — do not follow the decision below.
>
> - **Status:** ~~Accepted · Ratified (Phase 1 Prompt 3)~~ — **Superseded by ADR 0005**
> - **Date:** 2026-09 (superseded 2026-09-27)

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

## Phase 1 closeout — 2026-09-26

At Phase 1 completion, the above decision is reaffirmed with the following
**hard gate** for Phase 2:

- **Redis:** cache-only in Phase 2 (shared rate limiting + read-through caching
  where measured to help). No Redis Streams as a general-purpose event bus.
- **RabbitMQ:** introduced **only** if volume justifies it, and **only** for the
  specified Phase 2 async jobs (durable delivery of notification/reminder work
  that cannot remain purely in-process under measured load). It must not be
  used as a cross-service domain-event bus at this stage.
- **Kafka:** **out of scope** for Phase 2. Evaluation remains deferred to
  Phase 6 as stated above.

This gate must be re-verified at the Phase 2 kickoff before any broker
deployment proceeds. Any expansion of RabbitMQ's scope requires a new ADR.

## Consequences

- Tests stay hermetic and deterministic (no queue fixtures needed).
- The async contract in `EVENT_ARCHITECTURE.md` §4 governs how far we can
  stretch the in-process model before the Phase-6 evaluation.
- When we do add a broker, the `automation/` package boundary (already
  reserved) is the seam it plugs into.