# ADR 0002 — Modular monolith over microservices

- **Status:** Accepted · Ratified (Phase 1 Prompt 3)
- **Date:** 2026-09

## Context

The product roadmap (`ROADMAP.md`) spans Sales, Marketing, Service, Travel
Ops, Finance, and Analytics. A common reflex is "that big → microservices
now." But the actual constraints at this stage are:

- **One team, one deploy cadence.** Both frontend and backend deploy as a
  pair; there is no independent release of a "leads service" that would make
  network partitions or version skew worthwhile.
- **Transactional integrity is business-critical.** Booking→payment→ops
  handoff updates span aggregates in one logical action; in-process
  transactions make I1/I2/I3 (see ADR 0001) cheap to keep DB-enforced.
- **Operational simplicity until scale is real.** Observability tooling,
  backups, and DR are for *one* jar + *one* Postgres today
  (`DEPLOYMENT.md`, `DISASTER_RECOVERY.md`).
- **Enforced boundaries.** The things microservices are good at — module
  ownership, API contracts, isolation — are already provided in-process by
  strict package boundaries (ARCHITECTURE.md §3): one service interface per
  module, no cross-package entity access.

## Decision

Build the platform as a **modular monolith**: one Spring Boot jar, one
Postgres; every feature module confined to its own package with the only
inbound route being its service interface. Never extract a microservice
speculatively.

## What would trigger reconsideration

Extract a module **only** when at least one is *measured and specific*:

1. **Scale ceiling** — a module's resource profile (e.g. rendering/search,
   report generation) degrades the rest of the app under load, evidenced by
   load tests (Phase 12 tooling) rather than intuition.
2. **Deploy-cadence conflict** — a module genuinely needs to ship on a
   different schedule than everything else, AND the split's boundary costs
   (version skew, eventual consistency, distributed transactions) are
   budgeted.
3. **Security/compliance boundary** — PCI/statutory data (e.g. finance
   module, Phase 9) requires physical isolation that a package boundary
   cannot provide.

Process when a trigger fires: a new ADR, written with numbers, naming the
module, the boundary contract, and the rollback path. Until then the answer
to "should we split?" is recorded **no**.

## Consequences

- Simpler ops, one artifact to back up/restore/debug.
- The discipline risk (boundaries eroding over years) is actively countered
  by: package review in CI candidates, ADR-for-bypass rule, and `common/`
  confined to infrastructure.
- Later split is *cheap to do* because module seams already exist
  (interface + DTO boundary), which is exactly why deferring is safe.