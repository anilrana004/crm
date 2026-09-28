# SecureTravels CRM — Integrations Map

> **Status: ratified since Phase 1 Prompt 3.** This file answers "what's
> connected to what" at any point in the project. Status column is the
> single source of truth: **Not Started / Planned Phase X / Built**.

All planned external systems are listed with the phase that owns them
(`ROADMAP.md`). New integration ideas get a row here **and** a phase decision
before any work starts.

## 1. Integration table

| # | System | Purpose | Status | Phase | Notes / references |
|---|---|---|---|---|---|
| 1 | **SecureTravels website** | Inbound lead intake | **Built** | 1 | `POST /api/webhook/lead`, HMAC-SHA256 `X-Webhook-Signature`, rate-limited, audited in `webhook_logs` (`API_STANDARDS.md` §6) |
| 2 | **Email (SMTP)** | Follow-up / notification emails | Partial | 1→2 | `IN_APP` rows always persisted; `EMAIL` rows emitted by stub behind a flag — real SMTP wiring in Phase 2 (`notifications` table, `EmailNotifier`) |
| 3 | **S3-compatible object storage** | ID proofs, medical certs, trip photos | Schema only | 2 | `documents` table stores `storage_key`; presigned upload path + compliance workflows land in Phase 2 |
| 4 | **Interakt (WhatsApp Business API)** | Outbound template sends, delivery status, inbound replies | **Built** (sandbox default) | 4 | `communications` package, 9 templates, `Interakt-Signature` HMAC webhook; `app.whatsapp.mode=INTERAKT` + `INTERAKT_API_KEY` activates the live adapter (`API_INTEGRATIONS.md`) |
| 5 | **Razorpay / Cashfree** | Payment gateway (advance/full/balance) | Planned | 5 | `payments.gateway_ref` reserved; **never** store raw card data |
| 6 | **Redis** | Shared rate limiting, cache, automation state | Planned | 2 | lands with Phase 2 (see `EVENT_ARCHITECTURE.md`) |
| 7 | **RabbitMQ** | Durable queue for outbound WhatsApp delivery | **Built** (optional) | 4 | `app.messaging.mode=BROKER`; publish failure falls back inline. ADR 0005 supersedes 0003 |
| 8 | **OpenSearch** | Search/analytics store | Not Started | 3 | reporting suite reads land here |
| 9 | **Prometheus + Grafana** | Metrics & alerting | Not Started | 3 | actuator `/actuator/prometheus` exposure planned |
| 10 | **Meta (Facebook/Instagram) Ads + Lead Forms** | Ads management, lead-form webhooks, attribution | Not Started | 5 | marketing module; lead-form payloads map to `leads` + `marketing` package |
| 11 | **Google Ads** | Ads + click/conversion attribution | Not Started | 5 | marketing module |
| 12 | **Tally / Zoho Books** | GST books sync, invoices | Not Started | 9 | finance module |
| 13 | **Keycloak / enterprise IAM** | SSO, ABAC | Not Started | 4 | replaces in-app JWT at that phase |

## 2. Outbound vs inbound

- **Inbound today:** website webhook (HMAC), Interakt webhook (HMAC, delivery
  status + customer replies, Module 4). Planned: Meta lead-forms (P9),
  Google (P9), Razorpay (P5).
- **Outbound today:** Interakt template messages (Module 4) via the `INLINE`
  sandbox adapter by default; email remains a stub. A `POST /api/whatsapp/send`
  or a confirmed booking both produce real `whatsapp_messages` rows and
  timeline entries, so the pipeline is exercised end to end without credentials.

## 3. Module 4 send pipeline (in one paragraph)

A send is **queued first, delivered second**. `WhatsAppDispatchService.enqueue`
writes the `whatsapp_messages` row and a `TEMPLATE_QUEUED` timeline entry in one
transaction, then publishes a `WhatsAppQueuedEvent`. Delivery runs
`AFTER_COMMIT`, because routing inside the enqueue transaction lets a broker
consumer claim a row that is not committed yet, see nothing, and discard the
message. `WhatsAppSender` then claims the row atomically (`QUEUED`→`SENDING`),
calls Interakt with no database connection held, and records `SENT`,
`QUEUED` (retry), `FAILED`, or `DEAD_LETTERED`. Confirming a booking publishes
`BookingConfirmedEvent` and the customer is messaged automatically.

## 4. Rules

1. Every integration has **one adapter package** (`communications/`,
   `marketing/`, `finance/`) with the vendor behind an interface; the rest of
   the system depends on the interface only.
2. No integration is built ahead of its phase (`ROADMAP.md`).
3. Secrets (`API_KEYS`, webhook secrets, tokens) come from environment/secrets
   (Vault from Phase 4) — never in code or the repo.
4. An integration is only "Built" when it has a test with a real/vendor-test
   fixture, not when credentials exist.
5. An inbound webhook always answers 2xx for anything it *recognises*,
   including duplicates, and only fails hard on a bad signature. Returning an
   error for an unknown event type makes the vendor retry forever.

## 5. Interakt: what is verified and what is not

Verified against Interakt's published API and enforced by
`InteraktWhatsAppGatewayTest`:

- `POST https://api.interakt.ai/v1/public/message/` — the trailing slash is
  required.
- `Authorization: Basic <INTERAKT_API_KEY>`, where the dashboard key is already
  `base64(accessToken + ":")`. Send it **verbatim**; rebuilding it is the
  documented mistake.
- `bodyValues` is a positional **array** matching `{{1}}..{{n}}` in the
  dashboard template, not a `{"1": ...}` map.
- Response is `{"result": true|false, "message": ..., "id": "..."}`; there is no
  error code, so the HTTP status is the only reliable retryability signal.

Still unverified without a live account, and therefore treated as
assumption-not-fact:

- The exact signature header Interakt sends on webhooks
  (`Interakt-Signature: sha256=<hex>` is implemented per its documentation and
  is configurable via `INTERAKT_WEBHOOK_SECRET`).
- Whether `bodyValues` may be omitted entirely for a zero-placeholder template.
  We send an explicit empty array.
- The optional `campaignId` field, and which templates need one.

Because the account has no live credentials, Module 4 ships with
`app.whatsapp.mode=SANDBOX` as the default: a real send path that logs and
records a synthetic provider id. Switching to Interakt is a config change.