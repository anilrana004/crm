# ADR 0004 — Rule-based lead scoring before ML

- **Status:** Accepted · Ratified (Phase 1 Prompt 3)
- **Date:** 2026-09

## Context

Lead scoring exists in Phase 1 as the `heat` field (`HOT / WARM / COLD`) on
`leads`, recomputed on every field change by `LeadScoringService`, and is
exercised by the sales dashboard and follow-up ladder. The roadmap promises
**ML-based scoring in Phase 11**. The open question is whether to leap to ML
now.

## Decision

Phase 1–10 keeps **deterministic, rule-based scoring**:

- Rules are explicit, readable, and testable (`LeadScoringServiceTest`
  covers every branch), e.g. a `WALK_IN`/`EXISTING_CUSTOMER` source with a
  matching trip and broad budget and short travel window scores HOT.
- Rules don't need training data, don't drift silently, and are auditable in
  `audit_log` — a salesperson can be told *why* a lead is HOT, which matters
  for trust and for DPDPA-adjacent explainability.

## What would justify upgrading to ML (Phase 11 eval)

Only when a **measured gap** appears — and with data to train on:

1. Enough labeled history (booked vs lost leads, thousands of rows from real
   Phase-1 use) to learn from.
2. A live evidence of rule mis-ranking: e.g. conversion-rate analysis shows
   leads the rules mark HOT convert *worse* than WARM in some segment
   (source/destination/season), not as a guess but from dashboard data
   (Phase 3 reporting suite makes this measurable).
3. A concrete improvement target (e.g. ≥10% lift in lead-to-booking rate vs
   rules) so "better" is falsifiable.
4. Explainability retained: the Phase-11 model must expose feature importance
   to keep the *why* audit trail that rules give us free.

## Consequences

- Zero upfront ML infra now; scoring stays fast, offline-reproducible, and
  covered by unit tests.
- The rule engine's feature set (source, destination match, budget fit,
  travel window, recency, consent, num_persons) is the *feature list* the
  Phase-11 model inherits — no re-engineering of what features exist.
- `reporting/` (Phase 10) matures the data pipeline the ML layer will consume.