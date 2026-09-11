# SecureTravels CRM — Phase 1 (Core CRM Build)

Custom-built CRM (no third-party SaaS) for **securetravels.in** — a Dehradun-based
Himalayan trekking / tour operator handling Kedarnath, Char Dham, Kedarkantha,
Hampta Pass, Kashmir, Ladakh & Spiti packages.

> Phase 2/3 items (WhatsApp Business API, payment gateway, GST invoicing, departure
> seat inventory, vendor management, AI, native app, multi-branch RBAC) are **out of
> scope** and intentionally not implemented.

---

## New production stack (Phase 1 build — Java + Next.js)

> This is the current, actively-developed stack. The old Fastify/React prototype
> (`server/` + `web/`, sections below) is preserved side-by-side and is **not** the
> deployment target.

**Stack:** Java 25 (Temurin LTS) · Spring Boot 3.5 · Spring Security + Spring Data JPA ·
PostgreSQL 16 · Next.js 15 (App Router) + TypeScript + Tailwind · REST + OpenAPI 3
(springdoc → openapi-typescript typed client) · Docker Compose · GitHub Actions CI ·
Nginx + Let's Encrypt · PostgreSQL 16 for storage.

```
SecureTravels/
├── backend/     # Spring Boot API (port 8080)
│   └── src/main/resources/db/migration/V1__init.sql  # approved Phase-1 schema
├── frontend/    # Next.js 15 App Router UI (port 3000)
├── docker-compose.yml   # postgres:16 + backend + frontend
├── .github/workflows/ci.yml  # backend (mvn verify + PG) & frontend (lint/typecheck/build)
├── docs/       # specs: PRODUCT_REQUIREMENTS, ARCHITECTURE, DATABASE, SECURITY,
│               # API_STANDARDS, CODING_STANDARDS, EVENT_ARCHITECTURE, INTEGRATIONS,
│               # TESTING, DEPLOYMENT, DISASTER_RECOVERY, ROADMAP
├── architecture/  # ADRs (0001-0004) + Mermaid diagrams (module map, ERD)
├── server/      # ⚠ legacy Fastify prototype (port 4000) — preserved, not active
└── web/         # ⚠ legacy React/Vite prototype (port 5173) — preserved, not active
```

### Quick start (new stack)

Prereqs: Java 25, Maven 3.9+, Node 20+, PostgreSQL 16 — or Docker.

```bash
# Option A — everything with Docker Compose (Postgres + backend + frontend)
docker compose up --build          # backend :8080, frontend :3000, db :5432
#   - Flyway applies the schema on backend boot (ddl-auto is `validate`)
#   - set WEBHOOK_SECRET / JWT_SECRET env vars over the dev defaults before prod

# Option B — run locally against your own Postgres 16
createdb securetravels_crm          # or set DB_URL in backend application.yml
cd backend
mvn spring-boot:run                 # http://localhost:8080  (health /api/health, docs /{docs})
cd ../frontend
npm install
npm run openapi:gen                 # regenerate typed client from backend openapi.json
npm run dev                         # http://localhost:3000  (/api/* proxied to :8080)
```

### Demo logins (dev-seeded via `app.bootstrap-demo-data`, off in prod)

| Role    | Email                        | Password    |
|---------|------------------------------|-------------|
| Admin   | admin@securetravels.in       | admin123    |
| Manager | manager@securetravels.in     | manager123  |
| Sales   | sales.ravi@securetravels.in  | sales123    |
| Sales   | sales.meera@securetravels.in | sales123    |
| Ops     | ops.suresh@securetravels.in  | ops123      |

`DemoDataSeeder` also seeds 6 starter leads (owned by the two Sales users, spread
across sources/statuses/heats) whenever the `leads` table is empty, so the
`/leads` page has data on first login.

### What's implemented so far

- **Approved Phase-1 schema** (Flyway migration): users, refresh_tokens, trips,
  guides, batches, seat_holds, leads, customer360, bookings (+I1/I2 consistency
  trigger), travellers, payments, operations_handoffs, tasks, documents, audit_log.
- **Auth slice**: login, `/me`, refresh rotation, logout — JWT 15-min access /
  7-day refresh (hashed), BCrypt-12.
- **Leads vertical slice**: `POST /api/leads` (DPDPA consent required, dup → 409,
  heat scoring), `GET /api/leads` (filtered + paginated), `PATCH /api/leads/{id}/status`
  (validated transitions, LOST requires reason, audit logged). RBAC + service-level
  ownership enforced.
- **Security posture** in `docs/SECURITY.md` (rate limiting, XSS/OWASP sanitizer, CORS
  whitelist, no stack traces, DPDPA consent).
- **Frontend**: login + leads list/create with status controls, driven by the typed
  OpenAPI client; `protected` routes and `/api` proxy to the backend.

- **Automation chain (Module 9)**: public **HMAC-signed webhook**
  `POST /api/webhook/lead` → duplicate check (soft 200) → **round-robin** sales
  owner (`assignment_state`) → lead → **5-minute call task + in-app/email
  notification** → every call audited in `webhook_logs`. Website-form payloads are
  accepted in camelCase or legacy snake_case (see "Website webhook" below).

### Website webhook (end-to-end automation chain)

Server-to-server endpoint — the website's enquiry form signs every POST with the
shared secret (`WEBHOOK_SECRET`, default dev value `dev-webhook-secret-insecure-change-me`).

```bash
# signature = sha256=<hex>(HMAC-SHA256(secret, raw JSON body))
# (PowerShell)  $sig = "sha256=" + $((hmac...)); see webhook-smoke for the helper

curl -i -X POST http://localhost:8080/api/webhook/lead \
  -H "Content-Type: application/json" \
  -H "X-Webhook-Signature: sha256=<computed>..." \
  -d '{"customer_name":"Demo Visitor","mobile_number":"9876543210",\
       "destination":"Kedarnath","num_persons":2,"consent_given":true}'
```

Response codes:

| Code | Meaning |
|---|---|
| **201** | Lead created, owner returned, `INITIAL_CALL` task scheduled (+5 min) |
| **200** | Duplicate lead (same phone, non-LOST) — existing `leadId` returned, nothing re-created |
| **400** | Malformed / missing fields, invalid mobile, or `consent_given` is not `true` (DPDPA) |
| **401** | `INVALID_SIGNATURE` — bad or missing `X-Webhook-Signature` |
| **503** | No active SALES user (and no manager fallback) available |
| **429** | Rate limit — 20 req/min per IP |

Assignment is round-robin: the least-recently-assigned active SALES exec first
(tie-broken deterministically), falling back to the first active MANAGER. No UI is
needed for this endpoint — exercise it with the curl above or the integration
suite, then see the lead, its `INITIAL_CALL` task and the `IN_APP` notification in
the UI/DB.

### Run the CI checks locally before pushing

CI (`.github/workflows/ci.yml`) runs two jobs on every PR — mirror them locally
before pushing:

```bash
# Backend: full Spring Boot test suite against a real Postgres test DB.
#   (Test config: backend/src/test/resources/application-test.yml ->
#    jdbc:postgresql://127.0.0.1:5432/securetravels_test)
createdb securetravels_test
cd backend
mvn -B verify

# Frontend: lint, typecheck, build (Node 24)
cd ../frontend
npm ci
npm run lint
npm run typecheck
npm run build
```

`mvn -B verify` runs the 131-test suite (auth, leads, trips/batches, bookings,
payments, operations, customer360, dashboard/targets, task automation, the
Module 9 webhook chain, and the Phase-1 hardening gates: seat-hold concurrency,
discount-approval threshold, and the lead→booking→payment→ops E2E). Remember
`npm run openapi:gen` after any backend API change so the generated typed
client stays in sync.

See `docs/` — `SECURITY.md` (implemented vs deferred controls), `ROADMAP.md`
and `PRODUCT_REQUIREMENTS.md` (the full module map and phase sequencing, the
reference for every future prompt), plus ADRs under `architecture/adr/`.

---

## Legacy prototype (Fastify + React/Vite) — NOT the active stack

> Retained for reference; do not deploy. Its own walkthrough and commands follow.

**Stack (legacy):** Fastify (Node.js) · PostgreSQL · React + Tailwind (Vite) · JWT auth
**Hosting target (legacy):** single VPS / Railway / Render

## 1. What's inside

```
SecureTravels/
├── server/                 # Fastify API (port 4000)
│   ├── src/
│   │   ├── sql/schema.sql  # Postgres DDL (16 tables)
│   │   ├── index.js        # App bootstrap + route registration + cron
│   │   ├── config.js       # env config
│   │   ├── db.js           # pg pool + query helpers
│   │   ├── cron.js         # hourly jobs (24h follow-up, payment reminders, overdue sync)
│   │   ├── plugins/auth.js # JWT + requireAuth / requireRole
│   │   ├── services/       # automation (lead lifecycle), notifications, round-robin, whatsapp
│   │   └── routes/         # auth, leads, tasks, packages, payments, operations,
│   │                       # customers, dashboard, targets, reports, whatsapp,
│   │                       # notifications, webhook
│   └── scripts/            # setup.js, schema.js, seed.js
└── web/                    # React + Tailwind admin panel (port 5173)
```

## 2. Setup

Prereqs: Node.js 18+, PostgreSQL 14+.

```bash
# 1. Database
createdb securetravels            # or set DATABASE_URL in server/.env

# 2. Backend
cd server
npm install
cp .env.example .env              # adjust credentials if needed
npm run setup                     # applies schema + seeds demo data

# 3. Frontend
cd ../web
npm install
npm run dev                       # http://localhost:5173
```

Backend started separately with:

```bash
cd server
npm run dev                       # http://localhost:4000  (health: /api/health)
```

Demo logins (created by seed):

| Role    | Email                        | Password    |
|---------|------------------------------|-------------|
| Admin   | admin@securetravels.in       | admin123    |
| Manager | manager@securetravels.in     | manager123  |
| Sales   | sales.ravi@securetravels.in  | sales123    |
| Sales   | sales.meera@securetravels.in | sales123    |
| Ops     | ops.suresh@securetravels.in  | ops123      |

## 3. Environment variables (`server/.env`)

| Variable         | Default                                      |
|------------------|----------------------------------------------|
| `DATABASE_URL`   | `postgres://postgres@127.0.0.1:5432/securetravels` |
| `PORT`           | `4000`                                       |
| `JWT_SECRET`     | dev default (change in prod)                 |
| `FRONTEND_URL`   | `http://localhost:5173` (comma-separated CORS) |
| `COMPANY_NAME`   | `SecureTravels`                              |
| `SMTP_*`         | empty → email reminders logged as failed (staging mode); set to enable real sends |
| `DISABLE_CRON`   | `false` (set true to disable scheduled jobs) |

## 4. Production notes (VPS / Railway / Render)

- `web`: build with `npm run build`, serve `dist/` and proxy `/api` → API.
- `server`: run `npm start`. Point `DATABASE_URL` at managed Postgres.
- Cron is inside the same Node process (single-instance deployment). For multi-instance,
  run `server/src/cron.js` once elsewhere.
- Set a strong `JWT_SECRET`, real `FRONTEND_URL`, and SMTP credentials.

---

## 5. Walkthrough — all 14 modules (1:1)

### M1 Lead Management
`Leads` page → create/edit via modal, list with filters (status / owner / source /
date range / search). Status pipeline `New → Interested → Quotation Sent →
Booking Confirmed → Lost` is a clickable stepper on every lead detail. Fields match
the spec exactly. API: `GET/POST/PATCH/DELETE /api/leads`, `GET /api/leads/:id`.

### M2 Lead Sources (auto-tag)
`source` is a DB enum (`google_ads, facebook_ads, instagram, website, whatsapp,
referral, justdial, walk_in, b2b, existing_customer`) and is required on creation —
every lead is tagged. Dropdown in the lead form; also for the website webhook.

### M3 Sales Follow-up Automation
On lead creation a **“Call within 5 minutes”** task is auto-created and the owner is
notified (in-app + email). Marking a lead **Interested** auto-schedules follow-ups at
**+1, +2, +5, +7 days**; **Quotation Sent** schedules a +2 day quotation follow-up.
Each task creates an in-app + email notification. See `services/automation.js`.

### M4 Sales Dashboard
`Dashboard` page with Today / This Month toggle. Cards: Total Leads, New Leads,
Follow-up Due, Interested, Quotation Sent, Booking Confirmed, Lost, and Revenue
(confirmed bookings).

### M5 Sales Employee Performance
`Dashboard` bottom-left table: per sales exec — Leads assigned, Follow-ups completed,
Bookings closed, Revenue generated, with totals row. Manager sees everyone side by side.

### M6 Sales Target Tracking
Manager sets a monthly target (bookings + optional revenue) per employee or company-wide
via the `Targets` page. Dashboard shows **Achieved / Remaining / Achievement %** with
progress bars, overall and per employee.

### M7 Payment Tracking
From a lead detail → “Add payment”: booking amount, advance, balance (auto-computed),
due date, payment status. Reminders **“Balance payment due in N days”** are auto-created
starting **3 days before** `due_date` (verified via cron job + Payments page badge).
Manual advance receipt / full-payment recording updates `advance_status`/`payment_status`.

### M8 WhatsApp Integration (click-to-chat)
9 seeded templates (package details, itinerary, price, payment details, hotel,
driver, pickup, booking confirmation, reminder). Each lead detail has a **“Send on
WhatsApp”** grid → previews the rendered message → one click opens `wa.me/<number>?text=…`
pre-filled. Uses the customer's WhatsApp number automatically.

### M9 Mobile-Responsive View
React UI is fully responsive: bottom nav bar + hamburger drawer on phones, sidebar +
tables on desktop. Sales can view leads, update call details (+ call log), set
follow-ups (status stepper + tasks), check customer info, update booking status, add
notes — from a phone browser.

### M10 Package / Trip Management
`Packages` page — CRUD with name, cost, duration, itinerary (rich/HTML), inclusions,
exclusions, departure date. Seeded with Kedarnath, Char Dham, Kashmir.

### M11 Operations Module
Status **Booking Confirmed** auto-creates an Ops record with booking ID
**TOH-YYYY-XXXX**, customer, package, pax, travel date, and default hotel/transport/
payment statuses, then notifies the Ops role in-app + email. `Operations` page is the
Ops team dashboard: set hotel/transport status, assign driver (separate `drivers`
table), track advance/balance, add notes.

### M12 Marketing Report
`Marketing` page (Reports) — source-wise Leads → Bookings conversion with date-range
filter, plus lost, revenue and conversion % per source.

### M13 Customer Database
After a trip, customers are retained permanently (separate `customers` +
`customer_trips` tables) with full trip history. `Customers` page lists everyone with
trip count / spend / last trip. **Suggest-offer** flag + remarketing tags (Kashmir,
Char Dham, Family tour, Anniversary) are toggled manually, ready for Phase 3.

### M14 End-to-End Automation Chain
`POST /api/webhook/lead` (public) = website form → creates lead → **round-robin
assigns** the next available sales exec → notifies them → creates the 5-minute call
task → if no status update in **24h**, cron creates the next follow-up → quotation
sent (manual step) → booking confirmed → **Ops auto-notified** + ops record created.
Verified end-to-end: webhook → ops record `TOH-2026-0002` with owner notifications.

---

## 6. Test webhook

```bash
curl -X POST http://localhost:4000/api/webhook/lead \
  -H "Content-Type: application/json" \
  -d '{"customer_name":"Demo Visitor","mobile_number":"9876543210","destination":"Kedarnath","package":"Kedarnath Yatra","source":"website"}'
```

## 7. Resetting demo data

```bash
cd server && npm run setup   # drops & recreates schema, re-seeds
```