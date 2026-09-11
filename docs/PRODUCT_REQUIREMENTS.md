# SecureTravels CRM — Product Requirements (the "what")

The long-term goal is a **Salesforce-class Travel Operating System** — not
just a CRM, but Sales + Marketing + Service + Travel Operations + Finance +
Analytics, all built on **one modular monolith** (see `ARCHITECTURE.md`),
evolved over many phases (see `ROADMAP.md`).

This file answers **what** the product is. `ROADMAP.md` answers **when**.
Architecture/security/API contracts are decided in the files referenced
below and are referenced forever after.

**Reviewed/updated:** Phase 1 Prompt 3.

---

## 1. The core product decision: the travel pipeline, not "Opportunities"

This platform is built around the travel-specific pipeline:

> **Lead → Enquiry → Trip Requirement → Quotation → Negotiation → Booking
> → Payment → Pre-Trip → Trip → Post-Trip → Review/Referral**

This is **explicitly not a generic "Opportunity"** model. The pipeline
stages map to real, physical events in a tour operator's lifecycle, and the
data model encodes them:

| Pipeline stage | Where it lives today (Phase 1) |
|---|---|
| **Lead** | `leads` (source, heat, owner, consent, status stepper) |
| **Enquiry / Trip Requirement** | `leads` (destination, trip_id, travel_date, num_persons, budget) |
| **Quotation** | `bookings` status `QUOTATION` → `QUOTATION_SENT` on the lead |
| **Negotiation** | `leads.follow_up_date`, `tasks` (follow-up + quotation), audit history |
| **Booking** | `bookings` (FIXED_BATCH vs CUSTOM_FIT — see ADR 0001) |
| **Payment** | `payments` (ADVANCE / BALANCE / FULL, PENDING→COMPLETED) |
| **Pre-Trip** | `operations_handoffs` (hotel/transport arrangements, trip sheet) |
| **Trip** | `batches.departure_date`, `operations_handoffs`, `travellers` |
| **Post-Trip** | `customer360` (total_trips, last_trip_date, total_spent) |
| **Review/Referral** | `customer360.offer_tags`, remarketing flags (Phase 5+ activates) |

Every future module must fit into this pipeline vocabulary rather than
introducing generic CRM concepts that fight it.

## 2. Non-goals (Phase 1, explicitly out of scope)

- Third-party SaaS CRM substitution (the whole point is building our own).
- Payment-gateway processing, GST invoicing, vendor payouts.
- WhatsApp Business / Meta / Google integrations (scheduled: see `ROADMAP.md`).
- Multi-branch tenancy and enterprise IAM.
- Anything listed as a later phase without a phase-appropriate trigger.

## 3. Full module map (the "what"; reproduced from ROADMAP.md)

> One row per product capability. The "when" for each is `ROADMAP.md`; this
> list is the complete inventory the platform is committed to.

### Phase 1 — Core CRM (built, IN PROGRESS)
- **Lead Management** — lead capture, sources, heat scoring, status stepper,
  ownership, dedup, consent, audit.
- **Trip / Batch catalogue** — trips (FIXED_BATCH / CUSTOM_FIT), batches &
  seat inventory, guides, 2-hour seat holds.
- **Booking** — fixed-departure and custom-fit bookings, traveller roster,
  consistency invariants I1/I2/I3.
- **Payment Tracking** — advances/balance/full, statuses, reminders.
- **Ops Handoff** — auto ops record on CONFIRMED, hotel/transport status, trip sheet.
- **Dashboards** — sales metrics, monthly sales targets, team performance.
- **Customer 360** — canonical person record, trip history, spend, consent.
- **Task Engine** — 5-min initial call, follow-up ladder, quotation, payment
  reminders, SLA/escalation sweeps.
- **Notifications** — in-app/email feed on automation events.
- **Audit** — append-only `audit_log` with actor/old/new.
- **Website Webhook** — HMAC-signed inbound lead intake + round-robin
  assignment + 5-min call automation chain (Module 9).

### Phase 2 — Operations & Compliance
- **Document/Compliance mgmt** — S3-backed documents (ID proof, medical
  certs, trip photos) with presigned uploads.
- **Vendor mgmt** — hotels, transport, driver records (upgrade `guides`/
  `driver_id` placeholders into a real vendor catalogue).
- **Full Automation Rules** — from the original spec.
- **WhatsApp Business API** — outbound messages + templates.
- **Redis + RabbitMQ** — shared rate limiting / caches / durable queues.

### Phase 3 — Mobile, Analytics & Scale
- **Mobile-responsive Ops app** for field staff.
- **Reporting suite** — Sales Funnel, Trip Performance, Team Performance.
- **OpenSearch** — search/analytics store.
- **Prometheus + Grafana** — metrics/alerting.

### Phase 4 — Enterprise Infra *(only if business reaches that scale)*
- Keycloak / enterprise IAM, ABAC, Vault, multi-branch support.

### Phase 5 — Marketing Platform
- Meta (Facebook/Instagram) full ads + lead-forms integration.
- Google Ads integration, campaign attribution.
- **Communications Hub** — unified WhatsApp/Email/SMS inbox.

### Phase 6 — Workflow Automation Engine
- Configurable trigger→condition→action rules (rule-based → visual builder).
- **Kafka / event-streaming first evaluated here** (see ADR 0003).

### Phase 7 — Sales CRM Depth
- Accounts (B2B/corporate), Opportunities/Pipeline configurability,
  Forecasting, Commission calculation.

### Phase 8 — Service/Support Module *(reassess need here)*
- Tickets, complaints, knowledge base.

### Phase 9 — Finance Depth
- Full invoicing, vendor payouts, commission, GST/statutory reporting,
  trip-level P&L, receivables/payables.

### Phase 10 — Advanced Analytics/BI
- Cross-module dashboards (CEO / Sales Manager / Marketing / Operations),
  forecasting models.

### Phase 11 — AI Layer
- ML-based lead scoring (upgrading Phase 1 rules), demand forecasting, chatbot.

### Phase 12 — Hardening & Scale
- Load testing, multi-region (if genuinely warranted), security audit.

## 4. Cross-cutting requirements

- **DPDPA / privacy first**: consent is captured at lead creation and is the
  single source of truth in `customer360` (details in `SECURITY.md`).
- **Auditability**: every status change and mutation is recorded (append-only).
- **Deterministic automation**: round-robin assignment, dedup, follow-up
  ladders — all reproducible and testable (see `TESTING.md`).

## 5. Reference documents

| Contract | File |
|---|---|
| Architecture & package boundaries | `ARCHITECTURE.md` |
| Schema & invariants | `DATABASE.md` |
| Security controls (implemented vs deferred) | `SECURITY.md` |
| API conventions & versioning | `API_STANDARDS.md` |
| Code conventions (Java & TypeScript) | `CODING_STANDARDS.md` |
| Async / events (no broker until Phase 6) | `EVENT_ARCHITECTURE.md`, ADR 0003 |
| External system map | `INTEGRATIONS.md` |
| Test strategy | `TESTING.md` |
| Deploy topology | `DEPLOYMENT.md` |
| Backup / restore | `DISASTER_RECOVERY.md` |
| Sequencing | `ROADMAP.md` |