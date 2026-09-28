# ADR 0005 — RabbitMQ for Phase 2 communication delivery

- **Status:** Accepted · Supersedes ADR 0003
- **Date:** 2026-09-27
- **Supersedes:** `0003-no-message-broker-until-phase-6.md`

## Context

ADR 0003 deferred a message broker and set a hard gate for Phase 2: RabbitMQ
is introduced **only if volume justifies it**, and only for durable delivery of
notification work that cannot stay in-process. It explicitly required that the
gate be **re-verified at the Phase 2 kickoff**, and that any expansion of scope
require a new ADR. This is that ADR.

Phase 2 Module 4 changes the picture. Customer-facing WhatsApp templated
messages introduce properties that the in-process model cannot provide:

1. **Genuine at-least-once delivery across process failure.** ADR 0003 named
   payment-gateway callbacks as the leading candidate; outbound WhatsApp to a
   customer is the same shape of problem. An in-app `Notification` row or an
   `@Async` call dies with the JVM. A message owed to a paying customer must not
   die with it — the retry must survive a restart.
2. **A third-party rate limit we do not control.** Interakt enforces a
   per-minute plan quota (300/min Growth, 600/min Advanced) and returns `429`
   with no `Retry-After`. Concurrency, batching, and backoff have to be
   *serialised somewhere*. An unbounded thread pool in front of a quota is how
   you get an IP-level throttle from Meta.
3. **A visible, bounded failure pile.** ADR 0003's own contract requires async
   work to "not silently die". A dead-letter queue is the concrete artefact
   that turns "we lost a message" from an assumption into a countable,
   replayable list an operator can inspect.
4. **The Module 7 requirement.** Module 7 automates an explicit rule set
   (document-expiry, batch readiness, refund routing). Those are asynchronous
   and retryable by nature, not synchronous call chains.

Measured current volume does **not** justify a broker for Phase 1's internal
staff notifications, and this ADR does not claim otherwise. The justification is
the *contract* (durable delivery + quota-shaped backpressure + inspectable
dead letters) against an external system we do not control.

## Decision

RabbitMQ is introduced in Phase 2, scoped to **outbound communication delivery
only**.

### In scope

- One direct exchange, `securetravels.communication`.
- One durable queue, `securetravels.whatsapp.dispatch`.
- One dead-letter queue, `securetravels.whatsapp.dispatch.dlq`, wired through
  the broker's native DLX mechanism rather than application-level re-publishing
  — a message that fails N times lands in the DLQ and the listener stops.
- A single `@RabbitListener` consumer so outbound WhatsApp is **serialised per
  instance**; horizontal scaling is bounded by the queue, not by quota theft.

### Explicitly out of scope

- **Not a cross-service domain-event bus.** A broker is not a general-purpose
  internal event bus, and internal events stay in-process.
- **Not Kafka.** Kafka remains deferred to the Phase 6 evaluation, per ADR 0003.
- **Not Redis Streams.** Redis, when it lands, is cache-only.
- **No new synchronous business state behind the queue.** A booking confirmation
  must still be able to tell the caller whether it succeeded. The queue carries
  *notifications*, never the truth of a transaction.

### Broker-optional by design

The broker is **degraded-tolerant, never load-bearing for boot or for
correctness**. `app.messaging.mode` selects:

- `INLINE` (default) — the dispatcher invokes the gateway on the calling
  thread, inside a local retry with the same backoff the consumer would use.
  Used by tests, by local dev, and as the automatic fallback.
- `BROKER` — publish to the exchange and let the consumer deliver. A publish
  that fails is caught and executed inline rather than dropped.

This is a deliberate reversal of ADR 0003's "no queue fixtures needed" test
property, but only where it buys something: 215 existing tests must keep passing
with **no broker running**, so the broker cannot be a hard context dependency.
`management.health.rabbit` is **disabled by default** for the same reason — a
stopped broker must not fail the health endpoint and take the whole app's
readiness with it. Enable it once RabbitMQ is genuinely mandatory, and only
alongside a policy for what the deployment does when the broker is down.

## Consequences

- The existing hermetic test story is preserved: the full suite runs with no
  broker. Module 4 tests run in `INLINE`; broker-specific behaviour
  (topology, DLQ routing) is verified by a separate opt-in test that skips when
  no broker is reachable.
- Delivery becomes at-least-once, so **the consumer must be idempotent**. This
  is the sharp edge of this ADR: Interakt exposes no `Idempotency-Key`, and a
  retry after a timeout can double-send to a real customer. Deduplication is
  therefore owned by us — a `whatsapp_messages` row is written before the send
  attempt and its state is the guard, not the provider's.
- A queue makes a new operational surface: a DLQ that nobody drains is a silent
  failure with extra steps. `RUNBOOK_PRODUCTION_DEPLOY.md` gains a drain
  procedure, and the DLQ depth is an alert condition in Phase 3.
- Operators can now see delivery failures. That is the point.

## Revisit triggers

1. **Interakt's quota becomes the throughput ceiling** rather than the template
   catalogue — re-evaluate batching, multiple WABA numbers, or a plan upgrade.
2. **A second consumer** needs messages that Interakt does not produce (a real
   cross-service bus, not a notification queue) — that is the ADR 0003 Phase 6
   Kafka question, unchanged.
3. **DLQ depth is routinely non-zero** — the queue is then hiding an upstream
   defect, not absorbing a transient failure.
