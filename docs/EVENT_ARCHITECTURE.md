# SecureTravels CRM — Event & Async Architecture

> **Status: Module 4 (Phase 2) added a broker for outbound WhatsApp delivery.**
> ADR `0003-no-message-broker-until-phase-6` is **superseded** by ADR
> `0005-rabbitmq-for-phase-2-communication-delivery`. A broker remains
> unnecessary for in-app notifications and business-state events — the scope
> change in ADR 0005 is delivery of messages owed to an external channel, not
> general event infrastructure.

---

## 1. Current model (Phase 1–2)

Four mechanisms, deliberately ordered by cost:

1. **Synchronous service calls** — the default. A booking confirm synchronously
   creates the ops handoff, updates the lead, writes audit rows. The caller
   needs the outcome to render a response (`createBooking(...)` must know
   whether it succeeded).
2. **Spring application events, `AFTER_COMMIT`** — work that must not be able
   to fail or block the business transaction, but is still in-process. Two
   exist as of Module 4:
   - `BookingConfirmedEvent` → `BookingConfirmationNotifier` queues the
     customer's WhatsApp confirmation.
   - `WhatsAppQueuedEvent` → `WhatsAppRoutingListener` routes a committed
     message for delivery.
   These differ in one deliberate way. `WhatsAppRoutingListener` sets
   `fallbackExecution = true`: the *publishing* transaction is
   `WhatsAppDispatchService.enqueue`, which always opens one, so a caller with no
   transaction is not a shape this listener should silently paper over.
   `BookingConfirmationNotifier` does **not** set it, because its publisher is
   `BookingService.confirm`, which is `@Transactional` on the class — a
   non-transactional path there would be a bug to fix, not to accommodate. Keep
   the two settings aligned with the actual publisher before changing either.
3. **Scheduled sweeps** — for *time* rather than *event* logic:
   - `SeatHoldSweep` — releases 2-hour seat holds that expired.
   - `FollowUpSweep` — materialises the +1/+3/+8/+15d follow-up ladder and
     SLA escalation on `tasks`.
   - `PaymentSweep` — flags `PENDING`/`PARTIAL` payments `OVERDUE` and emits
     balance reminders.
   - `WhatsAppDispatchService.recoverStuckMessages` — re-routes rows stranded in
     `SENDING` by a process death, which would otherwise be invisible to both
     the queue (already acked) and the DLQ.
   These sweeps are idempotent and safe to run more than once — that is the
   contract any future sweep must keep.
4. **RabbitMQ (optional, ADR 0005)** — durable delivery of outbound WhatsApp
   messages when `app.messaging.mode=BROKER`. See §4.

## 2. What events exist today (conceptual table)

| Event | Producer | Consumers | Mechanism |
|---|---|---|---|
| `lead.created` (UI) | `LeadService` | task engine (5-min INITIAL_CALL), notification bell, audit | sync + @Async notify |
| `lead.created` (webhook) | `WebhookService` | round-robin assignment, INITIAL_CALL task, notify | sync + @Async notify |
| `lead.status_changed` | `LeadService` | follow-up ladder, lost-reason/reopen logic, audit | sync |
| `booking.confirmed` | `BookingService` | ops handoff, lead stepper, payment reminders, **WhatsApp confirmation** | sync + AFTER_COMMIT event |
| `whatsapp.queued` | `WhatsAppDispatchService` | **WhatsApp delivery** (inline or broker) | AFTER_COMMIT event → optional queue |
| `seat_hold.expired` | `SeatHoldSweep` | batch seats recompute | sweep |
| `payment.overdue` | `PaymentSweep` | reminders | sweep |
| `notification.send` | any | in-app rows / email stub | @Async |

## 3. Why a broker was not needed before — and what changed

- **Why not before:** a broker adds an always-on dependency plus
  ordering/durability/replay semantics. Phase 1 volume and single-writer
  ownership didn't need it; in-process calls and sweeps keep every test hermetic
  and every failure visible in the unit it belongs to.
- **Why now (Module 4):** an outbound WhatsApp message is a promise to a
  *third party* to talk to a *customer*. Three properties matter that nothing
  in-process provides: the request thread must not be held across a provider
  HTTP call, a provider outage must not roll back the business action that
  triggered the message, and a message that has been promised must not vanish
  when the process restarts. Those need a durable row plus an at-least-once
  queue — not a general event bus.
- **Scope discipline:** the broker carries message **ids**, nothing else, and is
  declared only in `BROKER` mode. In the default `INLINE` mode not a single AMQP
  bean exists, so the app has no connection to a broker it does not use and the
  test suite needs nothing installed.
- **Automation** (Phase 6) still gets its own evaluation; the reasoning in ADR
  0003 about cross-cutting trigger→condition→action fan-out is unaffected.

## 4. Delivery pipeline

```
enqueue  ──▶  whatsapp_messages row (QUEUED) + TEMPLATE_QUEUED timeline   [tx]
                        │  committed
                        ▼
             WhatsAppRoutingListener  (AFTER_COMMIT)
                        │
        ┌───────────────┴───────────────┐
        ▼ INLINE                       ▼ BROKER
   retry loop on the request      publish UUID to
   thread, bounded budget          securetravels.communication
                                        │  whatsapp.dispatch
                                        ▼
                              securetravels.whatsapp.dispatch ──▶ @RabbitListener
                                        │ nack after maxAttempts
                                        ▼ (default exchange)
                              securetravels.whatsapp.dispatch.dlq
```

Three things make this safe rather than merely working:

1. **Commit before deliver.** Routing inside the enqueue transaction lets a
   consumer run `claimForSending` against an uncommitted row, get zero rows
   back, and discard the delivery.
2. **Exactly one sender.** The claim is a single `UPDATE ... WHERE
   status = 'QUEUED'` returning the affected row count. A read-then-write would
   let two consumers both send a "you are booked".
3. **Reject, don't requeue.** `defaultRequeueRejected = false` plus
   `RejectAndDontRequeueRecoverer`. Without this a rejected message is
   requeued forever and the DLQ silently receives nothing — the single most
   common way a RabbitMQ dead-letter setup does nothing.

**Retry ownership is exclusive.** In `INLINE` mode the loop in
`WhatsAppDispatchService` owns the budget and the sender marks the row
`DEAD_LETTERED` when it runs out. In `BROKER` mode the container's retry advice
owns it, the sender is passed `Integer.MAX_VALUE` and never declares exhaustion,
and the DLQ is the artifact of record (the row stays `QUEUED`). Two counters
racing over one row is how a message ends up both dead-lettered and retried.

**Retryability is never a guess about the message text alone.** The Interakt
adapter treats HTTP 429 and 5xx as retryable, 4xx as permanent, and — because
Interakt returns `result: false` with a human-readable string — also treats a
small set of that vocabulary (rate limit, service window) as transient.
Transport failures are always retryable.

## 5. Contract for every future async path

1. Async work must be idempotent (retries safe).
2. Async work must not silently die — a failure is logged with context.
3. Events that change business state stay synchronous; async is for
   *notifications* and non-critical fan-out.
4. No new always-on external runtime dependency without an ADR.
5. Anything routed through a queue must be idempotent **and** must have a
   durable row identifying it, because the queue payload is an id. A message
   with no row is unrecoverable.
6. Publish to a queue only after the row is committed, never before.
