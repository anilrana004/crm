# SecureTravels CRM — Deployment

> **Status: ratified since Phase 1 Prompt 3; verified at Prompt 4.** This file grows as the
> topology grows (single VPS → managed/managed-Postgres → multi-region in
> Phase 12).

---

## 1. Current deployment (Phase 1)

| Surface | What |
|---|---|
| Local dev | Docker Compose (`docker compose up --build`): `postgres:16` + backend jar + frontend; or run each locally (see README quick start) |
| CI | GitHub Actions (`.github/workflows/ci.yml`): backend `mvn -B verify` on JDK 25 against a `postgres:16` service; frontend `npm ci` + lint + typecheck + build on Node 24. **No production deploy step yet.** ⚠ Branch protection (require status checks + review on `main`) must be enabled manually via the GitHub web UI (Settings → Branches → Add rule) — see PHASE_1_SIGNOFF.md §5.1 for the exact settings to apply. |
| Production target | **Single Ubuntu VPS** running the same Docker Compose stack behind **Nginx reverse proxy with Let's Encrypt TLS**. Provider/domain to be recorded when provisioned (domain in plan: `securetravels.in`). |

> ⚠ Applies-on-provision (Phase 1 completion gate): the concrete VPS provider,
> DNS records, and the `JWT_SECRET` / `WEBHOOK_SECRET` rotation are an ops
> checklist item — see §5.

## 1b. Verified in Phase 1 Prompt 4 (local stand-in for the un-provisioned VPS)

Until a VPS exists, the **prod profile** was booted and exercised locally as a
stand-in, with measured results recorded here (JVM: Temurin 25, jar built with
the current code, local Postgres 16.4):

| Check | Result |
|---|---|
| `SPRING_PROFILES_ACTIVE=prod` boot, **fresh empty DB** (`securetravels_prodcheck`): Flyway applied 6/6 migrations, `ddl-auto: validate` clean | Started in **5.0 s** |
| Fresh-DB boot exposes **only** `/actuator/health` (log: *"Exposing 1 endpoint"*); `/actuator/loggers` → 401 (protected, not mapped) | ✅ |
| `GET /actuator/health` → `{"status":"UP","groups":["liveness","readiness"]}` — no DB details (`show-details: never`) | ✅ |
| `show-sql: false` — **0** Hibernate `select/insert/update/delete` lines in a full prod boot | ✅ |
| `bootstrap-demo-data: false` — **0** `[demo-data]` log lines (restore drill + fresh boot) | ✅ |
| Booted **against a restored dump** (DR drill) → Flyway validation passed on the restored schema; demo-user login accepted (`200` with access token) | ✅ (see `DISASTER_RECOVERY.md` drill log) |
| `server.error.include-stacktrace/message: never` (prod) | by config, verified in `application-prod.yml` |

> Note: prod boot logs Spring's auto-config warning about a *generated in-memory
> password*. The app's real auth is the custom `LoginFilter` over the `users`
> table (unaffected); the warning is bootstrap auto-config noise and can be
> silenced with `spring.autoconfigure.exclude=...UserDetailsServiceAutoConfiguration`
> when wiring the real deploy.

> Prompt-4 recorded reality: no VPS, managed Postgres, or PITR exists yet
> (local Postgres only). **PITR is therefore "not active"** per SECURITY.md
> "Deferred to Phase 2" and must not be claimed. The prod-stand-in results
> above plus the timed restore drill (RTO ≈ 1.1 s) are the operational
> evidence available today.

## 2. Runtime layout in production

```
Internet → Nginx (443, TLS via Let's Encrypt)
             ├── /api → backend:8080   (only /v3/api-docs & /docs opened to
             │                          staff network; everything else internal)
             └── /    → frontend:3000 (Next.js standalone build)
backend → Postgres 16 (managed, or on-VPS Docker volume for Phase 1)
env: DB_URL, DB_USER, DB_PASSWORD, JWT_SECRET, WEBHOOK_SECRET,
     CORS_ALLOWED_ORIGINS, SPRING_PROFILES_ACTIVE=prod
```

## 3. Images & versions

- `backend/` Dockerfile builds the Spring Boot jar (Java 25 Temurin base).
- `frontend/` Dockerfile builds Next.js (Node 24) with `output: standalone`
  where worth it; `.next` shared volume not required.
- Postgres 16, volume `pgdata` via compose. Backups: automated daily via
  `scripts/backup-postgres.sh` + `securetravels-backup.timer` (tested, **not
  yet installed** — no VPS); restore drills in `DISASTER_RECOVERY.md`;
  full deploy procedure in `RUNBOOK_PRODUCTION_DEPLOY.md`.

## 4. Release process

1. Backend change → `mvn -B verify` green; frontend change → lint +
   typecheck + build green.
2. `npm run openapi:gen` refreshed whenever payloads change with commit to same PR.
3. Merge → CI re-checks on PR (deploy pipeline lands when we provision VPS).
4. Deploy on the VPS: `docker compose build --pull && docker compose up -d` with
   env from a secret file (out of repo). Flyway migrates on backend start —
   **never** `harakiri` a DB; backup first (0.4).
5. Smoke after deploy: `GET /api/health`, login, and a single webhook POST
   against the staged `WEBHOOK_SECRET`. The exact commands are in
   `RUNBOOK_PRODUCTION_DEPLOY.md` §9 — there is **no `webhook-smoke.ps1` in
   this repo**; an earlier version of this line referenced one and was wrong.

## 5. First boot / bootstrap admin

A **brand-new production database has zero users**, so there is no account that
can sign in to create the first real user. The system would be locked out.
`BootstrapAdminRunner` closes that hole.

**How it works.** On every startup it checks two environment variables. If both
are present and non-blank **and** the `users` table is empty, it creates exactly
one account with role `CEO` (top of the flat hierarchy — it satisfies every
`hasAnyRole(...)` check, so it cannot be locked out of any screen). Because the
trigger is the table-empty condition, it is naturally one-shot: the moment any
user exists the runner is a no-op, forever.

| Variable | Required | Notes |
|---|---|---|
| `BOOTSTRAP_ADMIN_EMAIL` | yes | Normalised to lowercase. Not logged. |
| `BOOTSTRAP_ADMIN_PASSWORD` | yes | Stored BCrypt strength 12. **Never logged.** |

**Deliberately not wired to `app.bootstrap-demo-data`.** That flag is a local-dev
convenience that production turns off (`application-prod.yml`); this is a
separate, always-safe-in-prod mechanism. Both can be active independently.

**Procedure for a new environment:**

```bash
export BOOTSTRAP_ADMIN_EMAIL="ceo@securetravels.in"
export BOOTSTRAP_ADMIN_PASSWORD="$(openssl rand -base64 24)"
# boot the app once, sign in as that account, change the password, then:
unset BOOTSTRAP_ADMIN_EMAIL BOOTSTRAP_ADMIN_PASSWORD
```

**Rules for operators:**

- Treat these as **write-once, then unset**. Leaving them set is not a
  privilege-escalation risk on its own (the table-empty guard means they cannot
  recreate or overwrite an account), but the values are a live password in the
  process environment, so remove them once you have signed in.
- The runner **never overwrites an existing account** and **never resets a
  password**. There is deliberately no "reset the admin" path via env var; use
  the normal admin user-management flow.
- If the boot log says `users table already populated`, the guard fired and
  nothing was created — that is the expected steady state.

Tests: `BootstrapAdminRunnerTest` — empty DB creates one CEO; password stored
hashed; non-empty DB creates no duplicate; double invocation is idempotent;
missing/blank vars are safe no-ops.

## 6. Ops checklist (fill in at provision time)

- [ ] VPS provider + region chosen; firewall: 22 (key only), 80/443, no 8080 public.
- [ ] DNS `securetravels.in` → VPS; Nginx + certbot (Let's Encrypt, auto-renew).
- [ ] Generate & rotate strong `JWT_SECRET` (≥32 bytes) and `WEBHOOK_SECRET`.
- [ ] Managed Postgres (or on-VPS with automated backup) — see DR plan.
- [ ] `SPRING_PROFILES_ACTIVE=prod` (demo-data off, SQL logging off).
- [ ] `BOOTSTRAP_ADMIN_EMAIL` + `BOOTSTRAP_ADMIN_PASSWORD` set for the **first**
      boot only, then unset (see §5).
- [ ] Monitoring/restart policy for the compose services (systemd).
- [ ] Upstream proxy hardening: request size caps, per-IP nginx limits in front of the API.

## 7. Local Postgres instances on this machine

> Windows dev box. **More than one Postgres is installed, and they all default
> to port 5432.** Starting the wrong one — or starting ours while another owns
> 5432 — produces a confusing failure. Read this before starting Postgres.

| Install | Binaries | Data dir | Status |
|---|---|---|---|
| **`C:\tools\postgres\pgsql`** | `bin\postgres.exe`, `pg_ctl.exe`, `psql.exe` | **`C:\SecureTravels\.pgdata`** | ✅ **AUTHORITATIVE for this project** |
| `C:\SecureTravels\.tools\pg-extract\pgsql` | extracted PostgreSQL 16.3 | — | Superseded; same data dir works from either tree |
| `C:\Users\Admin\Desktop\st\securetravels-crm\dev-pg` | npm `embedded-postgres` 18.4.0-beta.17 | `dev-pg\data` (PG **18**) | Foreign project; ignore |
| `C:\Users\Admin\Desktop\st\securetravels-crm\.dev-pg` | PG 16 cluster | `.dev-pg` (PG **16**) | Foreign project; **previously squatted on 5432** |

Only the first row is ours. The bottom two belong to a **second clone of this
same repo** on the Desktop (same GitHub remote, same commit) and were the cause
of the port conflict documented in §8.1.

**The authoritative data directory is `C:\SecureTravels\.pgdata`.** It holds
`securetravels_crm` and `securetravels_test`, and Flyway owns the schema
(never hand-edit it).

### Pre-flight check — run this BEFORE starting Postgres

```powershell
# 1. Is anything already listening on 5432?
Get-NetTCPConnection -LocalPort 5432 -State Listen -ErrorAction SilentlyContinue

# 2. If something IS listening, identify it BEFORE killing anything:
Get-CimInstance Win32_Process -Filter "Name='postgres.exe'" |
    Select-Object ProcessId, ExecutablePath,
        @{N='DataDir';E={ ($_.CommandLine -split '-D\s+')[1] }}
```

If step 1 returns nothing, start ours. If it returns a row, **read the
`DataDir`**: anything other than `C:/SecureTravels/.pgdata` is a foreign
cluster — stop it deliberately, do not assume it is ours.

### Starting ours (see §8.1 for why not `pg_ctl` directly)

```powershell
$pg = "C:\tools\postgres\pgsql\bin\postgres.exe"
Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
    -Arguments @{ CommandLine = "`"$pg`" -D `"C:\SecureTravels\.pgdata`" -p 5432" }
```

Verify with the matching client (never a `psql` from another install):

```powershell
& "C:\tools\postgres\pgsql\bin\psql.exe" -h 127.0.0.1 -p 5432 -U postgres `
    -d securetravels_crm -c "\dt"
```

## 8. Known issues found 2026-09-26 (local dev box)

Both were hit during the Phase 1 closeout session and cost real time. They are
**environment/config bugs, not application defects** — the application code was
correct in both cases.

### 8.1 Postgres backends die with `0xC0000142` if the postmaster is started from a short-lived shell

**Symptom.** `pg_ctl start` reports success and the postmaster reaches
`database system is ready to accept connections`, but the **first client
connection kills the whole cluster**:

```
LOG:  server process (PID 3432) was terminated by exception 0xC0000142
LOG:  all server processes terminated; reinitializing
LOG:  startup process (PID 3380) was terminated by exception 0xC0000142
LOG:  aborting startup due to startup process failure
LOG:  database system is shut down
```

Client-side this looks like a server crash:
`psql: error: connection to server at "127.0.0.1", port 5432 failed: server closed the connection unexpectedly`.

**Cause.** `0xC0000142` is `STATUS_DLL_INIT_FAILED`. The postmaster was launched
by a transient shell (PowerShell/cmd) that is then torn down. The postmaster
survives, but each **forked backend** it later creates inherits that dead
shell's console handles and dies during DLL initialisation. Note the
`startup process` dies too — this is not specific to client connections.

**How it was confirmed** (worth repeating before assuming a data problem):

- A **brand-new empty cluster** on a different port failed identically → not
  the data directory.
- Both binary trees (`C:\tools\postgres` and `.tools\pg-extract`) failed
  identically → not a corrupt payload.
- `postgres --single -D <datadir>` worked fine → the data directory and the
  executables are healthy; only the fork path is affected.

**Fix — start the postmaster detached from any console.** Use the
`Win32_Process.Create` pattern in §7. It creates the process with no inherited
console, so forked backends initialise normally.

**Not a fix:** adding Defender exclusions. That is a plausible-sounding remedy
for `0xC0000142` but was ruled out here — Defender was the only AV present, and
the fresh-cluster test above points at console inheritance instead. Exclusions
also require an elevated shell.

**If you see this:** confirm with the three checks above, then relaunch
detached. Do not start deleting data directories.

### 8.2 `next.config.ts` doubled the `/api` prefix, so every UI call 401'd

**Symptom.** The Next.js UI could not talk to the backend at all. Every
proxied call returned `401 {"code":"UNAUTHORIZED"}` — including
`/api/health`, which is on the permit-list and cannot legitimately 401. A
`curl` straight at port 8080 worked, so it looked like an auth bug.

**Cause.** `next.config.ts` built the rewrite as `` `${API_TARGET}/:path*` ``
while `API_TARGET` already ended in `/api`:

```ts
const API_TARGET = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api";
// -> /api/health  became  http://localhost:8080/api/api/health
```

Spring received `/api/api/health`, which matches no controller and no
permit-list entry → 401. The tell:

```
http://localhost:8080/api/health       -> 200
http://localhost:8080/api/api/health   -> 401
```

This was **pre-existing** and hit the config's own default, so nobody using
the default could have had a working UI. It is not specific to this session.

**Fix — normalise to a bare origin, append the prefix exactly once:**

```ts
const RAW_API_TARGET = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080/api";
const API_ORIGIN = RAW_API_TARGET.replace(/\/+$/, "").replace(/\/api$/, "");
// ... destination: `${API_ORIGIN}/api/:path*`
```

This now accepts **either** form of the env var (origin-only or
origin+`/api`).

**Verification:** `/api/health` → 200, `POST /api/auth/login` → 200 with
tokens, `GET /api/auth/me` with the bearer token → 200.

> Note: `/api/does-not-exist` returns 401 (not 404) without a token, because
> `.anyRequest().authenticated()` rejects anonymous callers before routing.
> That ordering is correct; send a token when testing routing behaviour.

## 9. Future phases (planned only)

- Phase 3: OpenSearch sidecar, Prometheus+Grafana exporters, mobile-ops host on same box.
- Phase 4 (only at real scale): Keycloak IAM, Vault for secrets, multi-branch
  regions topic re-opened (ADR 0002 triggers).
- Phase 12: load testing + multi-region *if the business genuinely expands there*.