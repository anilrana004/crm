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
| `reporting` | `dashboard` (Phase-1 subset) |
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
- `reporting/` — Phase 10/11
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
| Search (future) | OpenSearch in Phase 3 | — |
| Observability (future) | Prometheus + Grafana in Phase 3 | actuator now |
| Objects/files | S3-compatible bucket, presigned URLs (documents, Phase 2) | no file bytes in Postgres |

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