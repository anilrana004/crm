# SecureTravels CRM — Architecture

> **Status: ratified since Phase 1 Prompt 3.** This file is the architectural
> contract for every future phase. See ADR 0002 for the reasoning behind the
> modular-monolith decision and the specific conditions that would trigger a
> split.

---

## 1. The one-sentence architecture

A single deployable **modular monolith** (Spring Boot) with strictly
separated feature packages, a typed client generated from a live OpenAPI
spec, and a Postgres schema owned by one Flyway migration chain.

Microservices are not the starting position. See ADR
`0002-modular-monolith-over-microservices` for why, and for the trigger list
that would make us reconsider.

## 2. The module map

Progression of the whole product is in `PRODUCT_REQUIREMENTS.md` /
`ROADMAP.md`. Architecturally, every module is one **package boundary**, with
exactly one route in: its service interface.

```
backend/src/main/java/com/securetravels/
├── identity/        (users, roles, permissions, auth)          — Phase 1
├── leads/           (lead management)                          — Phase 1
├── trips/           (trip catalogue, batches, guides)          — Phase 1
├── bookings/        (booking, travellers, seat holds)          — Phase 1
├── payments/        (payment tracking)                         — Phase 1
├── operations/      (ops handoff, trip sheets)                 — Phase 1
├── customers/       (customer 360)                             — Phase 1
├── tasks/           (follow-up/task engine)                    — Phase 1
├── audit/           (audit logging)                            — Phase 1
├── documents/       (compliance documents)                     — Phase 2
├── vendors/         (hotel/transport/vendor mgmt)              — Phase 2
├── automation/      (workflow engine)                          — Phase 6
├── communications/  (WhatsApp/email/SMS hub)                   — Phase 5
├── marketing/       (Meta/Google integrations)                 — Phase 5
├── finance/         (invoicing, payouts, P&L)                  — Phase 9
├── reporting/       (BI/analytics)                             — Phase 10/11
└── common/          (shared utilities, base entities, exceptions)
```

### 2.1 As-built package names (Phase 1 reality)

The canonical names above are the long-term contracts. Phase 1 shipped under
its original scaffold names; the mapping is exact and intentional:

| Canonical module | As-built packages (current) |
|---|---|
| `identity` | `auth`, `user` |
| `leads` | `lead` |
| `trips` | `trip` |
| `bookings` | `booking` |
| `payments` | `payment` |
| `operations` | `operations` |
| `customers` | `customer` |
| `tasks` | `task` |
| `audit` | `common.audit` (shared service) |
| `documents` | `document` (entity only; workflows Phase 2) |
| `reporting` | `dashboard` (Phase-1 subset), `analytics` + `commission` (Phase 3 Module 2) |
| `notifications` | `notification` (supporting module; canonical home is `communications`) |

Rename-to-canonical is a **refactor only**, to be executed when a module
next gets a real change anyway (never as a standalone no-op commit). The
package-boundary *discipline* is in force today regardless of the name.

### 2.2 Placeholder reservations

Future modules exist today as empty `package-info.java` placeholders so the
boundary is reserved *now* and nobody invents a parallel structure later:

- `automation/` — Phase 6
- `communications/` — Phase 5
- `marketing/` — Phase 5
- `finance/` — Phase 9
- `reporting/` — Phase 10/11. **Still reserved.** Phase 3 Module 2 (Reporting
  suite) ships in `analytics/` + `commission/`, not here; the canonical name
  stays reserved for the cross-module BI layer.
- `vendors/` — Phase 2
- `document/`-level workflows — Phase 2 (entity present since Phase 1)

## 3. Non-negotiable module rules

1. **One inbound route per module**: its service interface. No cross-package
   `@Autowired` into a sibling repository.
2. **No cross-package direct entity access.** If module A needs module B's
   data, it calls B's service (or reads B's read-model DTO) — it never
   imports B's entity.
3. **DTO-in / DTO-out at every controller boundary** (see `CODING_STANDARDS.md`).
4. **Ownership enforcement lives in the service**, not just in
   `@PreAuthorize` at the controller (see `CODING_STANDARDS.md`).
5. New modules may depend on `common/` and on **interfaces**, never on the
   internals of other modules.

Exception (currently permitted): `common.audit` is called by every module;
that is the shared *infrastructure* package, not a feature module.

## 4. Technology decisions (Phase 1, ratified)

| Concern | Choice | Notes |
|---|---|---|
| Runtime | Java 25 (Temurin LTS), Spring Boot 3.5 | single jar |
| Persistence | Spring Data JPA over PostgreSQL 16, Flyway | `ddl-auto: validate` |
| AuthN/AuthZ | JWT (15 min) + hashed rotating refresh (7 days), BCrypt-12, RBAC `@PreAuthorize` | Phase 4 moves to Keycloak/ABAC |
| API contract | springdoc-openapi → `/v3/api-docs` → openapi-typescript typed client | see `API_STANDARDS.md` |
| Rate limiting | Bucket4j in-memory per instance | Phase 2 → Redis shared |
| Async | Spring `@Async` for fire-and-forget | NO broker until Phase 6 (ADR 0003) |
| Search | **PostgreSQL full-text (`tsvector` + GIN + `pg_trgm`)** | OpenSearch **deferred 2026-09-27** — measured, not assumed. **Implemented 2026-09-28 in `V13`. See §4.1** |
| Observability | Prometheus + Grafana + alert rules (Phase 3) | **implemented 2026-09-28** - `docs/OBSERVABILITY.md` |
| Log aggregation | host-retained structured logs | ELK **deferred 2026-09-28** - see 4.2 |
| Objects/files | S3-compatible bucket, presigned URLs (documents, Phase 2) | no file bytes in Postgres |

### 4.1 OpenSearch: deferred, with the measurement (2026-09-27)

Phase 3 originally specified "OpenSearch for full-text search across Leads,
Customers, Bookings **once Postgres tsvector search genuinely becomes
insufficient at current data volume**", with an explicit instruction to confirm
that condition before standing up a cluster. It was measured rather than assumed,
and the condition **is not met.**

Current production-shape volume in `securetravels_crm`:

| Table | Rows |
|---|---|
| `leads` | 30 |
| `bookings` | 16 |
| `customer360` | 16 |
| `travellers` | 59 |
| `trips` | 15 |

There is additionally **no full-text search implemented at all** — 0 `tsvector`
columns, 0 GIN indexes, 0 trigram indexes in the schema. The gap is not "Postgres
is too slow", it is "search has not been built".

Measured latency (`EXPLAIN ANALYZE`, local PostgreSQL 16):

| Query | Rows scanned | Execution time |
|---|---|---|
| Cross-entity customer search (`ILIKE` across `full_name`/`email`/`mobile_number`) | 16 | **0.040 ms** |
| Funnel-style aggregate, 3-table join + `GROUP BY source` | 30 + 16 + 16 | **0.093 ms** |

To find where Postgres would *actually* become the bottleneck, 100,000 synthetic
rows (≈3,300× current lead volume) were loaded and the same query shapes timed:

| Strategy at 100k rows | Execution time |
|---|---|
| Naive `ILIKE` (seq scan, worst case) | 42.8 ms |
| Btree on `lower(name)` + `ILIKE` | 35.1 ms |
| **`tsvector` + GIN** (correct Postgres FTS) | **0.76 ms** cold, **0.68 ms** warm |
| **`tsvector` + GIN**, ranked `ORDER BY ts_rank ... LIMIT 20` | **0.72 ms** |

**Conclusion: defer OpenSearch.** A GIN-indexed `tsvector` search answers
sub-millisecond at 100k rows — roughly three orders of magnitude more headroom
than the 136 rows the system actually holds today. Standing up a second search
cluster (and an ELK pipeline behind it) would add operational surface, a JVM/heap
tuning burden, index-sync correctness problems and a consistency model
(still-async reindex) in exchange for latency the current data volume does not
need. The premise for the cluster does not exist.

**What Phase 3 should build instead:** proper Postgres FTS — generated `tsvector`
columns over the searchable `customer360` / `leads` / `bookings` text, GIN
indexes, `ts_rank` ordering, `pg_trgm` for fuzzy/typo-tolerant match. That is a
migration, not a cluster.

> **As-built (2026-09-28): shipped.** `V13__reporting_commission_ledger_and_fts.sql`
> adds generated `search_vector` columns (raw `to_tsvector` + a second
> `regexp_replace`-stripped vector so partial-email and exact-phrase searches both
> match), GIN indexes, and `pg_trgm` with `similarity()`:
>
> - `customer360`: `full_name_search`, `email_search` (+ trigram on `mobile_number`)
> - `leads`: `contact_search` (name + email + mobile), `owner_id`, indexed `travel_date`
> - `bookings`: `reference_search` (ref + customer + trip name), `travel_date`
>
> The audit-log search (`/api/analytics/audit`) ranks by
> `ts_rank(raw_vector, websearch_to_tsquery(q)) + similarity()` and labels each
> hit `matchedBy` (`FTS` / `TRIGRAM` / both). Functional cost measured: a chess
> game's worth of rows, sub-millisecond. The "0 tsvector columns" paragraphs
> above are superseded; the decision-discipline they record still stands.

**Revisit OpenSearch only when one of these is true** (any one is sufficient):

1. Searchable rows exceed ~1,000,000, or GIN p99 latency exceeds ~100 ms.
2. A requirement appears that Postgres FTS genuinely cannot serve: cross-entity
   faceted navigation with dynamic aggregations at scale, typo-tolerant
   re-ranking tuned beyond `pg_trgm`, per-field analyzers with language-specific
   stemming/stopwords, or relevance tuned by ML rather than `ts_rank`.
3. Log aggregation is required — but that decision stands on its own merits
   (see the observability note) and still does not by itself justify *this*
   cluster.

Re-measure with the same queries before reopening this; do not adopt OpenSearch
on enthusiasm.

### 4.2 Log aggregation: deferred, with the same discipline (2026-09-28)

The Phase 3 brief also mentioned ELK-style log aggregation. **Not adopted**, on
the same reasoning as 4.1: an operational cluster is a standing cost and a
standing failure surface, and the requirement it would serve — "find the log
line behind this request" — is already served for the cases that matter.

What exists instead:

- **Metrics** for anything that is a rate, a latency, a depth or a count:
  Prometheus + Grafana (`docs/OBSERVABILITY.md`). These are cheap, bounded, and
  queryable, and they are what alerting should be built on.
- **Structured application logs** retained on the host, which is sufficient to
  diagnose a single bad request. Log aggregation becomes worthwhile when the
  question is no longer about one request.

**Revisit when** any of these is true: logs must be retained beyond the host's
disk; more than one application instance makes local log access impractical;
there is a compliance requirement to keep an immutable copy; or an on-call
rotation needs to search logs across instances. At that point Loki or
OpenSearch — not necessarily ELK, which is the heaviest of the options — is
justified.

This is a deferral, not a rejection. Re-measure log volume and retention
requirements before reopening it, exactly as 4.1 requires.

## 5. Layering inside a module (vertical slice)

```
<module>/
  <Module>Controller        # HTTP surface: DTOs, @PreAuthorize, status codes
  <Module>Service           # orchestration, transactions, ownership, audit
  <Module>Repository        # Spring Data; parameters only, no concatenated SQL
  <Module>.java             # entity (never leaves the package)
  dto/
    <Module>CreateRequest   # validated input record
    <Module>UpdateRequest
    <Module>Response        # read-only output record
```

Tests mirror this: `*ServiceTest` (pure unit) and `*FlowIT` (Postgres-backed
integration) — see `TESTING.md`.

## 6. Failure handling (contract)

- Stable error schema `ApiError {timestamp, status, code, message,
  fieldErrors[]}` from `GlobalExceptionHandler` (see `API_STANDARDS.md`).
- No stack traces ever returned to clients (`include-stacktrace: never`).
- Domain exceptions are typed (`NotFound`, `Forbidden`, `Conflict`,
  `BadRequest`, `ServiceUnavailable`, `WebhookSignature`) → stable HTTP codes.

## 7. Configuration & secrets

- All secrets injected via environment variables (`JWT_SECRET`,
  `WEBHOOK_SECRET`, `DB_URL`/`DB_USER`/`DB_PASSWORD`,
  `CORS_ALLOWED_ORIGINS`); dev defaults never promoted to prod.
- `application-prod.yml` disables demo-data bootstrap and SQL logging.
- Never commit `.env`/secret files (see `.gitignore`).

## 8. When we would split a service out

Full trigger list and process in ADR
`0002-modular-monolith-over-microservices`. Short version: only after a
module shows a concrete, measured reason (scale ceiling, deploy cadence
conflict, security boundary), and never speculatively.