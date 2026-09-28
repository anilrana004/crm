# SecureTravels CRM — ROADMAP (the "when")

> **Status: ratified since Phase 1 Prompt 3.** This is the agreed phase
> sequencing for the entire multi-year program. Every future prompt should
> start with "per ARCHITECTURE.md and ROADMAP.md, build..." and reference
> this file rather than re-explaining scope. Do **not** reorder or compress
> this table. The "what" behind each phase lives in `PRODUCT_REQUIREMENTS.md`;
> this file is only the "when".

## Sequencing principle

Every phase ships something the business actually uses before the next
phase starts — **no phase is "architecture only"** past Phase 0 (Phase 0 was
the design/approval work done before Phase 1 code). A phase may introduce
new infrastructure, but only as the carrier for user-visible capability in
the same phase.

---

## The phase map (verbatim, agreed)

- **Phase 1 — Core CRM (IN PROGRESS)**: Lead Mgmt, Trip/Batch, Booking,
  Payment Tracking, Ops Handoff, Dashboards, Customer 360, Task Engine.
- **Phase 2 — Operations & Compliance**: Document/Compliance mgmt, Vendor
  mgmt, full Automation Rules (from the original spec), WhatsApp Business
  API, Redis + RabbitMQ introduced here.
- **Phase 3 — Mobile, Analytics & Scale**: Mobile-responsive Ops app,
  Reporting suite (Sales Funnel, Trip Performance, Team Performance),
  OpenSearch introduced here, Prometheus+Grafana introduced here.
- **Phase 4 — Enterprise Infra**: Keycloak/enterprise IAM, ABAC, Vault,
  multi-branch support (only if the business genuinely reaches that scale).
  > **Reassessed 2026-09-28: NONE justified yet — phase closed at this gate,
  > modules deferred (see `PHASE_4_REASSESSMENT.md`).** Findings: 5 users,
  > single site, no SSO (Module 1); no second office (Modules 2 & 4);
  > 8 secrets, one accessor (Module 3). Re-evaluate at the Phase 7 checkpoint
  > or when any trigger fires: ~25+ active users or a real SSO/SAML need;
  > a second office or funded regional manager; secret count > 15 or >3
  > people needing distinct secret subsets; live payment/BSP credentials.
- **Phase 5 — Marketing Platform**: Meta (Facebook/Instagram) full ads +
  lead-forms integration, Google Ads integration, campaign attribution,
  Communications Hub (unified WhatsApp/Email/SMS inbox).
- **Phase 6 — Workflow Automation Engine**: Configurable trigger→condition→
  action rules (starting rule-based, evolving toward a visual builder), this
  is where Kafka/event-streaming gets evaluated for the first time if
  automation volume genuinely requires it.
- **Phase 7 — Sales CRM Depth**: Accounts (for B2B/corporate), Opportunities/
  Pipeline configurability, Forecasting, Commission calculation.
- **Phase 8 — Service/Support Module**: Tickets, complaints, knowledge base
  (only if post-sale support volume justifies a dedicated module — reassess
  at this point whether it's actually needed).
- **Phase 9 — Finance Depth**: Full invoicing, vendor payouts, commission,
  GST/statutory reporting, trip-level P&L, receivables/payables.
- **Phase 10 — Advanced Analytics/BI**: Cross-module dashboards (CEO/Sales
  Manager/Marketing/Operations views), forecasting models.
- **Phase 11 — AI Layer**: Lead scoring (ML-based, upgrading from Phase 1's
  rule-based version), demand forecasting, chatbot.
- **Phase 12 — Hardening & Scale**: Load testing, multi-region considerations
  if the business genuinely expands that far, security audit.

## Phase gates (hard checks between phases)

1. The previous phase's modules are in real business use (not just merged).
2. No cross-module capability from an earlier phase was silently skipped.
3. Any deferred item that a later phase hard-depends on is called out in the
   kickoff prompt for that phase (e.g. Phase 2 must revisit `EVENT_ARCHITECTURE.md`
   and the async/Redis decision in `ADR-0003`).
4. `DATABASE.md`, `SECURITY.md`, `INTEGRATIONS.md`, and `DEPLOYMENT.md` are
   updated to match whatever shipped.