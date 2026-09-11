# SecureTravels CRM — Coding Standards

> **Status: ratified since Phase 1 Prompt 3.** Language toolchains: Java 25
> (Temurin LTS, Spring Boot 3.5) and TypeScript 5.8 (Next.js 15 App Router).

---

## 1. Java & Spring — package-by-feature, vertical slices

### Package layout (repeat of `ARCHITECTURE.md` §2)
- One package per module (`lead`, `trip`, `booking`, …), never package-by-layer.
- Inside a module: `Controller`, `Service`, `Repository`, entity class(es),
  and a `dto/` subpackage (records).
- No cross-package `@Autowired` into a sibling module's repository; go through
  the owning service interface.
- No module imports another module's entity.

### The reference slice (every new slice copies `lead/`)
1. **DTO-in / DTO-out at the controller boundary.** Controller accepts a
   `*CreateRequest` / `*UpdateRequest` / `*StatusRequest` and returns a
   `*Response`. Entities never cross the HTTP boundary.
2. **Bean Validation on every request record** (`@NotBlank`, `@Size`, `@Email`,
   `@Future`, ranges). Semantic rules that can't be expressed as annotations
   live in the service (see `WebhookService.validate` for the manual-parity pattern).
3. **`@PreAuthorize` is a gate; the service is the boundary.** Role checks at
   the controller; ownership (SALES/OPS see & mutate only their own;
   MANAGER/ADMIN/CEO all) **always re-enforced in the service**, else a
   cross-owner 403 test will (rightly) fail.
4. **Write-through validation + sanitization.** Free-text is OWASP-sanitized
   on write (`XssSanitizer.text`); stored values are re-escaped by React on
   render. No double-encoding on write.
5. **Typed exceptions, no surface 500s.** Use `NotFound/Forbidden/Conflict/
   BadRequest/ServiceUnavailable/WebhookSignature` exceptions; reserves for
   genuinely-not-answerable cases.
6. **Audit every mutation.** `AuditService` writes actor/old/new for creates,
   updates, and `STATUS_CHANGE` (including the webhook path with a NULL actor).
7. **Enums as `@Enumerated(STRING)`** matching DB CHECK constraints; UUID PKs;
   optimistic `@Version long version` on mutable entities.
8. **Base entities:** `common.audit.CreatedOnly` / `CreatedUpdated`.
9. **Money:** `BigDecimal`, never float; format `numeric(12,2)`.

### Concurrency
- Seat inventory (`batches.seats_booked`, invariant I3) is mutated under
  `PESSIMISTIC_WRITE` in a transaction — see `SeatHoldSweep` / booking service.
- Idempotency: duplicate leads (same phone) → 409 in the UI API, soft
  `duplicate:true` in the webhook API. Never blind inserts without the check.

## 2. TypeScript & Next.js

### Folder structure
```
frontend/src/
  app/            # routes (page.tsx per route; e.g. leads, trips, bookings…)
  components/     # feature components, one folder per feature (lead/, trip/…)
                  #   <Feature>/<Feature>Table.tsx, <Feature>Form.tsx…
  lib/            # api.ts (typed client facade), auth.tsx, lostReasons.ts
  openapi/        # generated.ts — regenerated, never hand-edited
```

### Conventions
- **One API facade:** all fetches live in `lib/api.ts`. Components never call
  `fetch` or read `localStorage` tokens directly; they call `api.*` and use
  types re-exported from `components["schemas"]` (e.g.
  `export type Lead = components["schemas"]["LeadResponse"]`).
- **Token handling** (`tokenStore`) is centralized; on 401 the facade clears
  tokens and redirects to `/login`.
- **Server components are the default**; client components (`"use client"`)
  only where interactivity makes it necessary (forms, dnd board, bell).
- **No `dangerouslySetInnerHTML`.** Rendered text is escaped by React.
- Relative paths via `@/` alias (`@/lib/api`, `@/components/...`).
- Tailwind v4 utilities; no CSS-in-JS.
- Run `npm run openapi:gen` after every backend API change, then
  `npm run lint && npm run typecheck && npm run build` locally (mirrors CI).

## 3. Tests

- **Unit:** `*ServiceTest.java` — pure JUnit/Mockito, no Spring context
  (`LeadScoringServiceTest`, `PhoneUtilsTest`).
- **Integration:** `*FlowIT.java` — full Spring Boot + real Postgres via
  `application-test.yml`, `BaseIT` truncates tables between tests; Flyway
  rebuilds schema. CI provides a `postgres:16` service (see `TESTING.md`).
- Name math: a *change that fixes a bug* should add a regression test first.
- Test DB is `securetravels_test` (never the dev `securetravels_crm`).

## 4. Git & change discipline

- One logical change per commit; schema migration + entity + DTO + service +
  test + `DATABASE.md` update land together.
- Never commit secrets, `.env`, build output, `.next/`, `target/`, `.pgdata/`.
- Frontend-only or backend-only changes still must pass their own local CI
  mirror before push.