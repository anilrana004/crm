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
| 4 | **WhatsApp Business API** | Outbound templates, click-to-chat→API | Planned | 2 | Cloud API; replaces legacy prototype's `wa.me` links |
| 5 | **Razorpay / Cashfree** | Payment gateway (advance/full/balance) | Planned | 2 | `payments.gateway_ref` reserved; **never** store raw card data |
| 6 | **Redis** | Shared rate limiting, cache, automation state | Planned | 2 | lands with Phase 2 (see `EVENT_ARCHITECTURE.md`) |
| 7 | **RabbitMQ** | Durable queues for notification fan-out | Planned | 2 | only if volume justifies; see ADR 0003 revisit trigger |
| 8 | **OpenSearch** | Search/analytics store | Not Started | 3 | reporting suite reads land here |
| 9 | **Prometheus + Grafana** | Metrics & alerting | Not Started | 3 | actuator `/actuator/prometheus` exposure planned |
| 10 | **Meta (Facebook/Instagram) Ads + Lead Forms** | Ads management, lead-form webhooks, attribution | Not Started | 5 | marketing module; lead-form payloads map to `leads` + `marketing` package |
| 11 | **Google Ads** | Ads + click/conversion attribution | Not Started | 5 | marketing module |
| 12 | **Tally / Zoho Books** | GST books sync, invoices | Not Started | 9 | finance module |
| 13 | **Keycloak / enterprise IAM** | SSO, ABAC | Not Started | 4 | replaces in-app JWT at that phase |

## 2. Outbound vs inbound

- **Inbound today:** website webhook (HMAC). Planned: WhatsApp in (P2),
  Meta lead-forms (P5), Google (P5).
- **Outbound today:** none live (email stub only). Planned: WhatsApp (P2),
  email (P2), outbound webhooks to our own systems confirmed before P4.

## 3. Rules

1. Every integration has **one adapter package** (`communications/`,
   `marketing/`, `finance/`) with the vendor behind an interface; the rest of
   the system depends on the interface only.
2. No integration is built ahead of its phase (`ROADMAP.md`).
3. Secrets (`API_KEYS`, webhook secrets, tokens) come from environment/secrets
   (Vault from Phase 4) — never in code or the repo.
4. An integration is only "Built" when it has a test with a real/vendor-test
   fixture, not when credentials exist.