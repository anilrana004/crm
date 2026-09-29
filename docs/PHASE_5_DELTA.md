# Phase 5 — Step 0 Overlap Audit / Delta Plan

> **Date:** 2026-09-28
> **Audit result:** the kickoff prompt's claim that Phase 3 already shipped
> "Meta Lead Ads auto-fetch + campaign-level reporting", "Google Ads conversion
> tag + UTM attribution", "a Marketing ROI report with manual ad-spend entry",
> and "segment-based bulk WhatsApp sends" **does not match the codebase**.
> Inventory below is taken from the actual tree (`grep` over
> `backend/src/main/java`, `db/migration`, tests) on this date.

## What actually exists (verified)

| Capability | Where | Notes |
|---|---|---|
| WhatsApp Business API (Interakt) — template sends, delivery status, idempotent inbound webhook, timeline events, RabbitMQ-or-inline dispatch with DLQ | `communications/` (Phase 2 Module 4, Phase 3) — `Interakt*`, `WhatsAppDispatchService`, `WhatsAppSender`, `WhatsAppMessage`/`Template`/`MessageRepository`, `TimelineEvent(-Repository)`, V12 | The single outbound entry point today is `WhatsAppDispatchService.enqueue(...)`; failed sends retry then DLQ; inbound dedupe via unique `(provider, provider_message_id, kind)` on `timeline_events` |
| `timeline_events` is already channel-aware | `communications/TimelineEvent` | `channel` enum + `customerMobile` + `Direction` — the natural substrate for the unified inbox |
| In-app notifications | `notification/` (V3) | follow-up engine notifications, separate from the hub |
| Email "stub" | `common/notify/EmailNotifier` | logs only; no transport, no record |
| Customer marketing flags | `customer360.marketing_opt_in`, `consent_given`, `consent_scope` (V1) | legacy booleans; **no history, no per-channel consent** |
| Analytics suite | `analytics/` + `commission/` (Phase 3 Module 2, V13) | budgeting/funnel/etc.; **no marketing ROI endpoint** |
| `marketing/` package | `marketing/package-info.java` only | **empty placeholder** (ARCHITECTURE §2.2) |
| Ad spend tables, campaign tables, segment builder, UTM/gclid/fbclid capture | — | **do not exist** (no tables, no columns, no code) |

## Delta per module

### Module 1 — Consent & Preference Management
**Build new.** No consent history exists; `marketing_opt_in` is a single mutable boolean.
- New `consent_records` (append-only) + `ConsentService`; per channel (WHATSAPP/EMAIL/SMS) and purpose (TRANSACTIONAL/MARKETING), status GRANTED/REVOKED/UNKNOWN, source, timestamp, evidence ref. Legacy `marketing_opt_in=false` migrates as UNKNOWN (never assumed granted).
- New **`SendGateService`** — the single mandatory outbound gate. All sends (manual, bulk, sequence step, automated) must go through it; channel senders are no longer callable directly. Grep-verified: reroute `WhatsAppDispatchService`/`WhatsAppSender` callers (BookingConfirmationNotifier; WhatsAppController; tests) through the gate.
- Inbound opt-out: STOP / UNSUBSCRIBE / "band karo" on WhatsApp + SMS revoke and auto-reply; signed expiring unsubscribe link for email.
- Customer 360 consent view (per channel).

### Module 2 — Unified Communications Hub
**Extend** (WhatsApp exists): build the missing channels, inbox, template library, window enforcement; **build new** email/SMS transport records + adapters.
- `threads` + `InboxService`/controller: one conversation per Customer/Lead across WhatsApp/Email/SMS, badge, assignee, Open/Pending/Resolved, full history = `timeline_events` (already unified).
- Inbound WhatsApp: **extend** webhook to create a Lead (Source=WHATSAPP) when unmatched (today it attaches only when a conversation exists); idempotency already guaranteed by the unique index — keep, add a test at webhook level ×3 deliveries.
- **24h service window** server-side: free-form inbound replies only within 24h of last customer inbound; templates outside it; UI countdown. Computed from `timeline_events` (`REPLY_RECEIVED`/`MEDIA_RECEIVED` per phone).
- Email: **build new** `EmailMessage` + gateway interface (SES adapter selected — the repo's object storage is S3-compatible, docs already prefer AWS-shaped choices; SendGrid as documented alternative), outbound via the same dispatch pattern, inbound reply webhook (HMAC). DNS records → `docs/RUNBOOK_EMAIL_DNS.md`, marked **pending DNS access** (human-only step).
- SMS: **build new**, TRAI DLT-aware; provider adapter interface (MSG91/Gupshup; MSG91 chosen as default), fake for tests, runbook marked **built, pending DLT registration**; used as fallback channel.
- **Template library** across channels: extend `whatsapp_templates` with `category` (TRANSACTIONAL/MARKETING) + `approval_status`; email/SMS templates in the same registry.
- Delivery tracking: per-channel message tables mirror `whatsapp_messages` status lifecycle; retry→DLQ pattern reused.

### Module 3 — Nurture Sequences
**Build new** (no sequence/segment/campaign code exists).
- `Sequence` + ordered steps (wait + send template on channel) in `marketing/`; fixed enrollment triggers (lead created w/o booking after N days; quotation sent unanswered; post-trip review + 90-day re-engagement; enquiry without quotation) via domain events; **build new** `SegmentService` (no Phase 3 segment builder exists to reuse — noted as delta from kickoff assumptions).
- Auto-exit: customer reply, booking created, opt-out, lead Lost — events; must all stop the sequence. Tests for each exit.

### Module 4 — Closed-Loop Ad Platforms
**Build new** end-to-end (no tables/columns/code exist).
- `ad_spend` ingest job (Meta Marketing API + Google Ads daily; provider interfaces + fakes; manual entry as override) — replaces the kickoff's "existing manual entry" (there is none; manual entry *created* here as fallback).
- Meta Conversions API + Google offline conversions: on booking CONFIRMED / payment received, hashed-SHA256 identifiers, **only with advertising consent**; dedicated events; tests assert no event without consent.
- gclid/fbclid capture at lead creation; lead→booking quality report by campaign/ad.
- Marked **built, pending live credentials** where the environment lacks them.

### Module 5 — Marketing Dashboards
**Build new.** Spend/leads/CPL/bookings/CAC/ROAS by channel+campaign, channel delivery/read/reply rates, sequence performance (enrolled/exited-by-reason/converted), consent coverage. Reconciliation rule: bookings attributed across sources == total bookings in period, remainder explicitly "Unknown".

## Cross-cutting
- V14+ migrations (next after V13). RabbitMQ (IDs only), Redis cache-only, HMAC webhooks, `@PreAuthorize` + service ownership, audit on state changes, package-by-feature — all per existing standards.
- External-access items (DLT, DNS/ad credentials) recorded with exact next steps and **never block code sign-off**.
- New SDKs (Meta/Google/SES/MSG91) — none added beyond already-present deps; OSV-Scanner re-run at hardening.