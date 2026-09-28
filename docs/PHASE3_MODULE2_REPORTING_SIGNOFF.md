# Module 2 (Phase 3) — Reporting Suite & Searchable Audit Log — Sign-off Record

> **Date:** 2026-09-28 | **Author:** Senior Dev | **Status: GO** — backend 335-test suite green
> (0 failures, 0 errors), immutable revenue attribution shipped, PostgreSQL FTS shipped in `V13`,
> frontend `/reports` route builds clean. The live authenticated HTTP round-trip (§5.1) has been
> executed against the running backend with temporary BCrypt smoke users (created with approval,
> removed afterwards); it surfaced and fixed two further live-only bugs (§3.6-7).

Module 2 of Phase 3. Phase 3 Module 1 (mobile-responsive operations) delivered the trips module
whose logs do not exist yet; this module delivers the reporting suite the roadmap names for
Phase 3 — **Sales Funnel, Trip Performance, Team Performance** — plus, plainly within the same
scope, Operations Readiness, Customer Insights and a **searchable audit-log viewer**, and the
**commission-revenue attribution** those numbers need in order to be honest.

---

## 1. Executive summary

The module ships complete and green.

- **Attribution is now immutable.** Before this module, revenue "belonged" to whoever owns the
  lead *today*. `leads.owner_id` is mutable, so last week's sale changes hands when a manager
  reassigns the lead. New `sales_commission_ledger` (V13) snapshots the owner **at confirmation**,
  is credited idempotently on `CONFIRMED` and revoked (never deleted) on `CANCELLED`, and is the
  source of every revenue number in the suite. The two eligible existing bookings were credited by
  backfill with the stated, documented policy that historical attribution is "as of today" (the
  owner at confirmation was never recorded anywhere).
- **Six report endpoints + one search endpoint**, one shared filter shape, real SQL. `AnalyticsService`
  runs parameterised `JdbcTemplate` against the reports, scopes SALES callers to their own rows in
  the service layer (not just `@PreAuthorize`), and returns `scope`/`SELF|ALL` plus an honest
  "basis" note on every estimate.
- **PostgreSQL FTS + trigram shipped** in V13 (`tsvector` + GIN + `pg_trgm`), the measured
  alternative to the deferred OpenSearch cluster (ARCHITECTURE.md §4.1). The audit viewer ranks by
  `ts_rank` **and** trigram similarity and tells the reader why each row matched (`matchedBy`).
- **Two production bugs found and fixed by this module's tests:** (1) the analytics controllers
  were missing `@CurrentUser`, so every authenticated call would have 400'd; (2) `MethodArgumentTypeMismatchException`
  had no handler, so *any* malformed query parameter (a bad UUID, `?season=SUMMER`) returned 500
  across the whole API. Both fixed; the second is a shared-service fix (`GlobalExceptionHandler`).
- **Two further bugs surfaced only by the live authenticated round-trip** (§5.1) and fixed in the
  same pass: (3) the team report leaked peer rows to a self-scoped SALES caller (the getter now
  restricts the outer row set too); (4) `GET /api/customers` without a `search` term 500'd on real
  PostgreSQL with `function lower(bytea) does not exist` — invisible to the H2-based suite.
- **The suite grew net +47**: 335 tests green (baseline 288 before this module).

## 2. Module status

| Deliverable | Status | Notes |
|---|---|---|
| `sales_commission_ledger` + credit/revoke hooks | ✅ | `BookingService` confirms -> credit (idempotent), cancels -> revoke (row retained) |
| V13 migration (ledger + FTS + indexes) | ✅ | **Applied to real DB** — Flyway `version 13`, `success = true`, 2/2 backfilled credits, net_amount ties exactly, 0 duplicates |
| Analytics reports + team scoping | ✅ | six reports use real SQL and service-layer SALES scoping; `teamPerformance` row-set leak found live (§3.6) and fixed |
| Audit search (FTS + trigram, `matchedBy`) | ✅ | visible to ADMIN/CEO only; `?q=&size=&before=&after=` filters + pagination |
| Live authenticated HTTP round-trip | ✅ | executed §5.1 with temporary BCrypt users (removed afterwards) |
| Customer search on real PostgreSQL | ✅ | `lower(bytea)` null-search 500 fixed in `Customer360Repository` (§3.7) |
| `AnalyticsController` role gates + `@CurrentUser` | ✅ | audit = ADMIN/CEO; operations excludes SALES; SALES self-scoped on the rest |
| `@CurrentUser` bug | ✅ | fixed; also covered by `AnalyticsAccessIT` |
| Type-mismatch -> 400 (shared handler) | ✅ | new, improves every endpoint that takes typed query params |
| Backend tests | ✅ | **335 total, 0 failures 0 errors** |
| Frontend `/reports` (6 tabs + shared filter + audit search UI) | ✅ | `tsc --noEmit` clean, `next build` clean (`/reports` 6.31 kB, compiled) |
| Docs | ✅ | ARCHITECTURE §2.1/2.2/§4.1, DATABASE.md §12, this sign-off |

## 3. Bugs found by this work, and why the suite had been green

1. **Missing `@CurrentUser` on the analytics endpoints** — discovered by `AnalyticsAccessIT`
   (every authenticated call returned 400 `VALIDATION_FAILED` on `active`). Nothing else used
   those six endpoints, so no endpoint-level test had ever touched them; the rest of the app uses
   `@CurrentUser` uniformly. Fixed, and the IT now proves 200/403/401 wiring through the real
   security filter chain.
2. **Unparseable query params -> 500 everywhere** — `MethodArgumentTypeMismatchException` had no
   handler, so `...?season=SUMMER` (and any malformed UUID/date on *any* controller) fell into the
   catch-all 500. The suite had no test that passed a wrong-typed query parameter. Now returns 400
   `INVALID_PARAMETER` with only the parameter name echoed (no content reflection).
3. **`ts_rank`/`similarity` are Postgres `real` (Float)** — a direct `(Double)` cast would have been
   a production `ClassCastException` on the first real search. Test-backed `asDouble` helper used.
4. **`users.active` vs `users.is_active`** — the team query referenced the wrong column name;
   caught by `AnalyticsServiceIT` against real Postgres (would have been a runtime SQL failure).
5. **`BatchReadiness.batchId` held the ops-handoff id** — fixed to `batches.id` (+ separate
   `handoff_id`); the readiness UI is keyed on real batch ids now.
6. **Team performance leaked peer rows to a self-scoped SALES caller** (found live, §5.1). The
   `teamPerformance` outer `users` row set was decided by *presence gates*
   (`exists(leads owner=u)` / `exists(tasks assignee=u)` / `exists(ledger consultant=u)`); the
   per-consultant dates were applied but the caller's `consultantId` never was, so a SALES user
   asking about themselves still received every consultant with leads or tasks — including their
   revenue. Fixed by appending `and u.id = :consultantId` to the outer WHERE when scoped
   (`userScopeWhere`, in `AnalyticsService`), and pinned by
   `AnalyticsAccessIT.salesCanReadTeamScoped`, which gives a colleague a lead and asserts the
   colleague's row is absent. Invisible to the earlier service tests because they only ever scoped
   principals that owned data.
7. **`GET /api/customers` without `search` 500'd on real PostgreSQL**
   (`function lower(bytea) does not exist`) — found live. `Customer360Repository.search` bound the
   nullable `:search` inside `lower(concat('%', :search, '%'))`; with a NULL value PostgreSQL types
   the untyped parameter as `bytea` and `lower(bytea)` does not exist. The H2-backed suite never
   saw it (H2 has no `bytea`). Fixed by lower-casing and `%`-wrapping the term in Java and binding a
   plain `:pattern` (`lower(c.fullName) like :pattern`), with no parameter inside `lower()`/`concat()`
   at all. Live-verified: no-search list and `?search=` both 200 on the running server.

The reason these reached the fix list early and cheaply is that the new ITs run **real SQL on real
Postgres** (BaseIT + Flyway), never mocks or H2 — and the live round-trip (§5.1) then exercised the
same stack over real HTTP with real credentials, which is the only place bug 7 could ever appear.

## 4. Decision notes that must not be "fixed later" into wrongness

- **`dashboard.performance` (legacy) and the ledger reports intentionally disagree.** Dashboard
  attributes via `created_by` + payments; Module 2 attributes owner-at-confirmation. Both are
  documented in code; the numbers are different by design.
- **`revenueLessBaseCost` is NOT a margin.** `trips.base_cost` is treated per-person and is a
  partial cost; the response says so in `costBasis` and the UI renders a callout.
- **Incident summary is `available=false`**, not zero. Phase 3 Module 1 did not produce the trip
  logs incidents would come from; "unavailable" survives serialisation so the UI can never imply
  "no incidents".
- **FTS searches raw AND `regexp_replace`-stripped vectors** — a single `to_tsvector('english',
  'Ramesh.New@x.com')` collapses to one lexeme and breaks partial-email search.
- **Season** (SPRING/MONSOON/AUTUMN/WINTER) is a business convention mapped to month sets, not
  quarters.

## 5. Open items (external, not code defects)

1. ~~**Live authenticated HTTP round-trip.**~~ **Executed.** No seeded credential existed
   (`BOOTSTRAP_ADMIN_EMAIL/PASSWORD` only works on an empty database), so — with explicit approval —
   two BCrypt temporary users were created directly in the real DB: an ADMIN (`smoke.admin@securetravels.in`)
   and a SALES (`smoke.sales@securetravels.in`), both since deleted (their 10 refresh tokens were
   removed first; no domain rows referenced them). Results against the running backend:
   - Login contract: bad creds → 401, empty body → 400 `VALIDATION_FAILED`, malformed JSON → 400
     `MALFORMED_BODY`; repeat logins hit the Bucket4j rate limiter (429) — tokens must be reused.
   - ADMIN: funnel / trips / team / operations / customers / audit all 200, `scope=ALL`
     (4 / 15 / 7 / 1 / 16 rows); `?season=SUMMER` → 400 `INVALID_PARAMETER`;
     `?actorId=<garbage>` → 400; `?q=meera` audit search 200.
   - SALES: funnel 200 `scope=SELF`; team 200 `scope=SELF` but returned **4 rows** — the §3.6 leak;
     operations → 403; audit → 403.
   - After the §3.6 fix and rebuild: SALES team returns `consultants: []` whether asked for their
     own id or a peer's id (peer rows absent), `scope=SELF` throughout.
   - `GET /api/customers` (no `search`) → 500 `lower(bytea)` until the §3.7 fix; now 200 with 16
     customers, and `?search=` still 200.
   The server currently runs jar `securetravels-crm-0.1.0.jar`, health `UP`, `:8080`.
2. **Docker daemon down** → Prometheus/Grafana dashboards are config-validated, not running;
   Testcontainers cannot be used (hence BaseIT against local Postgres).
3. **Production Nginx/VPS + alert delivery** remain externally unverified (carried from M5).

## 6. Artifacts changed

Backend (`C:\SecureTravels\backend`):
- `src/main/resources/db/migration/V13__reporting_commission_ledger_and_fts.sql` (applied)
- `src/main/java/com/securetravels/crm/commission/*` (entity, repository, service, package-info)
- `src/main/java/com/securetravels/crm/analytics/*` (`AnalyticsService`, `AnalyticsController`,
  `Season`, `dto/` ×8, `package-info`)
- `src/main/java/com/securetravels/crm/booking/BookingService.java` (credit/revoke hooks)
- `src/main/java/com/securetravels/crm/common/exception/GlobalExceptionHandler.java`
  (type-mismatch -> 400)
- `src/main/java/com/securetravels/crm/customer/Customer360Repository.java` (`lower(bytea)`
  null-search fix, §3.7)
- Tests: `AnalyticsServiceIT`, `AnalyticsAccessIT`, `AnalyticsServiceScopeTest`, `SeasonTest`,
  `commission/CommissionLedgerTest`, `booking/BookingFlowIT` (+3), `BaseIT` (+truncation)

Frontend (`C:\SecureTravels\frontend`):
- `src/app/reports/page.tsx`, `src/components/report/*` (8 files), `src/lib/api.ts` (+6 methods),
  `src/components/Sidebar.tsx` (+Reports), regenerated `src/openapi/generated.ts`,
  `openapi.json` (re-exported from the running backend)

Docs: `docs/ARCHITECTURE.md` (§2.1, §2.2, §4.1), `docs/DATABASE.md` (header, §12, relationship map),
`docs/PHASE3_MODULE2_REPORTING_SIGNOFF.md` (this file).