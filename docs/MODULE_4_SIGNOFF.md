# Module 4 (Phase 2) — WhatsApp & Unified Timeline — Sign-off Record

> **Date:** 2026-09-27 | **Author:** Senior Dev | **Status: GO with two
> credential-blocked gaps** (both external, neither a code defect — see §5)

Module 4 of Phase 2. Sits between the Phase 1 core and Phase 5's
Communications Hub, and delivers the outbound WhatsApp capability the Phase 2
scope calls for plus the unified Lead/Customer/Booking timeline it needs in
order to be usable.

---

## 1. Executive summary

The module ships complete: a durable outbound message record, a provider
gateway with a real Interakt implementation and a no-network sandbox twin, a
`QUEUED → SENDING → SENT/DELIVERED/READ/FAILED/DEAD_LETTERED` state machine with
atomic claim, an inbound webhook that writes the other half of the conversation
into the same timeline, and booking-confirmation automation that cannot roll
back a confirmed booking.

**256-test suite green** (215 before this module, +41 new). 0 failures, 0
errors, 0 skipped — the 4 RabbitMQ tests ran for real, not self-skipped.
`tsc --noEmit` clean, `next build` clean (12 routes).

Delivery is **optional-broker by design**: `INLINE` is the default and needs no
infrastructure; `BROKER` (ADR 0005) adds RabbitMQ for durable hand-off. The
booking-confirmation path was a no-op until this commit, so this module is what
actually makes a confirmed booking visible to the customer.

**What is not verified:** anything requiring live Interakt credentials — the
real send, the live webhook signature, and whether Interakt's live account
enforces a `campaignId` its sandbox omits. The wire contract is pinned by
fixtures derived from Interakt's published API docs, and every such assumption
is enumerated in `INTEGRATIONS.md` §5 rather than left implicit.

---

## 2. Module status

| Capability | Status | Evidence |
|---|---|---|
| Outbound message record + state machine | ✅ verified | `WhatsAppSenderTest` (9) — claim is atomic under concurrency, attempts cannot drift from the claim, terminal states are write-once |
| Interakt gateway (live wire contract) | ✅ verified against published docs, ❌ not against a live account | `InteraktWhatsAppGatewayTest` (7) — trailing-slash URL, `Basic <key>` verbatim, positional `bodyValues`, HTTP status drives retryability, `countryCode` quoted, error code is a string |
| Sandbox gateway | ✅ verified | `WhatsAppCommunicationIT` — sends never leave the box and still produce a full timeline |
| Inbound webhook → timeline | ✅ verified | `WhatsAppCommunicationIT` (17) — idempotent on replay, replies correlated by `recipient_mobile`, inbound text stored but never logged |
| Unified timeline API | ✅ verified | `GET /api/timeline/{subjectType}/{subjectId}`, ownership-checked |
| RabbitMQ topology + DLQ | ✅ **verified live** | `WhatsAppBrokerIT` (4) against real RabbitMQ 4.3.6 — see §3 |
| Booking-confirmation automation | ✅ verified | confirm booking → customer message queued, booking still `CONFIRMED` even when the provider is down |
| Shared HMAC signer | ✅ verified | `HmacSignerTest` (4) — constant-time compare, RFC 2202 vector |
| Flyway V12 | ✅ verified | applied to an existing DB and a fresh DB; 9 template rows seeded; `ddl-auto: validate` clean |
| Live Interakt send / webhook | ❌ **never exercised** | no credentials in this environment |
| `campaignId` on live account | ❌ **unknown** | sandbox does not send it; docs say it may be required |
| Browser send button in `frontend/` | ❌ **not built** | API + typed client shipped; no UI wired yet (not in Module 4 API scope) |

---

## 3. Live broker verification (not mocked)

Real RabbitMQ 4.3.6 on Erlang/OTP 27.3.4, AMQP on `127.0.0.1:5672`.

| Check | Result |
|---|---|
| App start in `BROKER` mode | direct exchange `securetravels.communication`, queue `securetravels.whatsapp.dispatch` with **1 consumer**, plus `…dispatch.dlq` — all declared |
| Queued message | consumed off the broker, `attempts=1`, status `SENT`, dispatch queue drained to 0 |
| Gateway fails retryably, forever | retries to `maxAttempts` (row `attempts>=3`), then **rejected** → lands in the DLQ, dispatch queue drained |
| Unparseable payload (`not-a-uuid`) | lands in the DLQ instead of looping |
| Broker stopped, suite re-run | 4 tests **skip themselves** — a machine without RabbitMQ still gets a green suite |

The retry-exhaustion case is the one that matters most: with
`defaultRequeueRejected=false` absent, RabbitMQ would requeue forever and the
DLQ would stay permanently empty — the failure would look like "no problems"
while customers silently received nothing.

---

## 4. Bugs found by this work, and why the suite had been green

Three defects, all invisible to the tests that existed before it, all fatal in
`BROKER` mode only. Recording them because each is a trap that recurs.

1. **`@RabbitListener(queues = "#{@appProperties.messaging.queue}")`** — a
   `@ConfigurationProperties` class has no contractual bean name, so the SpEL
   reference failed to resolve and the **whole application context refused to
   start**. No unit test loads a `BROKER`-mode context, so nothing could have
   caught it. Now `"${app.messaging.queue}"`.
2. **Routing inside the enqueue transaction.** A consumer could claim a message
   row before commit, see zero rows, conclude it was already handled, and ack —
   **silent message loss**, row stranded in `QUEUED`, invisible to both the
   queue and the recovery sweep. Fixed with `WhatsAppQueuedEvent` +
   `AFTER_COMMIT` routing.
3. **`TransactionRequiredException: no transaction is in progress`** — the
   post-commit phantom transaction. A `@Transactional` method called from
   inside an `AFTER_COMMIT` listener silently *joins* the already-completed
   transaction. Both the enqueue and the sender's claim/record boundaries are
   now explicitly `REQUIRES_NEW`. Lesson: in a post-commit callback, an
   implicit `REQUIRED` is a latent failure, not a no-op.

A fourth, environmental: **RabbitMQ 4.3.x will not boot on Erlang/OTP 29** —
`{horus,do_disassemble_and_cache,4}` / `beam_disasm`. The fault is in the OTP
28+ JIT, not RabbitMQ, and no RabbitMQ setting fixes it. Pin **OTP 27.x**. The
full trace and recovery procedure are in `RUNBOOK_PRODUCTION_DEPLOY.md` §5.1 so
the next operator does not lose an hour to it.

---

## 5. Open items (external, not code)

- [ ] **Live Interakt send** — needs `INTERAKT_API_KEY` from a real Interakt
      account. Until then the send path is proven only against fixtures.
- [ ] **Live Interakt webhook signature** — needs
      `INTERAKT_WEBHOOK_SECRET` and the dashboard-side secret to match.
- [ ] **Confirm `campaignId` behaviour** on a live account.
- [ ] **Confirm the explicit empty `bodyValues` array** is accepted for
      zero-placeholder templates on a live account.
- [ ] Decide whether a frontend send button ships with Module 5 or later.
- [ ] On the first deploy, confirm `securetravels.whatsapp.dispatch` reports 1
      consumer — a 0 means the app started without the listener and publishes
      are going nowhere.

Do not read the ❌ rows as shipped. They are unverified because the credentials
do not exist in this environment, not because the code is incomplete.

---

## 6. Artifacts changed

**Schema** — `V12__whatsapp_communication_timeline.sql`:
`whatsapp_templates` (9 seeded, reference data), `whatsapp_messages`
(one row per outbound, `callback_data` unique for idempotency), `timeline_events`
(unified, unique on `(provider, provider_message_id, kind)`).

**New package** `crm/communications` — 24 classes + 7 DTOs: two gateways,
sender, dispatch service, three listeners, webhook service/controller, timeline
service/controller, three entities, three repositories.

**Changed** — `BookingService` (publishes `BookingConfirmedEvent`),
`AppProperties`, `WebhookSignature` (now delegates to the shared signer),
`application.yml`, `BaseIT` (resets disabled templates), `application-test.yml`,
`backend/pom.xml` (AMQP).

**Docs** — `DATABASE.md` §11, `EVENT_ARCHITECTURE.md`, `INTEGRATIONS.md`,
`RUNBOOK_PRODUCTION_DEPLOY.md` §5.1 + status table, `architecture/adr/0005`
(accepted) and `0003` (superseded), `architecture/diagrams/module-map.md`
(communications moved into the Phase 2 cluster).

**API contract** — `frontend/openapi.json` refreshed from a live
`GET /v3/api-docs` (47 → 52 paths, 68 schemas) and
`frontend/src/openapi/generated.ts` regenerated via `npm run openapi:gen`
(+321 lines, 4 new schemas).
