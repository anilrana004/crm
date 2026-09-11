# SecureTravels CRM — Deployment

> **Status: ratified since Phase 1 Prompt 3; verified at Prompt 4.** This file grows as the
> topology grows (single VPS → managed/managed-Postgres → multi-region in
> Phase 12).

---

## 1. Current deployment (Phase 1)

| Surface | What |
|---|---|
| Local dev | Docker Compose (`docker compose up --build`): `postgres:16` + backend jar + frontend; or run each locally (see README quick start) |
| CI | GitHub Actions (`.github/workflows/ci.yml`): backend `mvn -B verify` on JDK 25 against a `postgres:16` service; frontend `npm ci` + lint + typecheck + build on Node 24. **No production deploy step yet.** ⚠ Branch protection (require status checks + review on `main`) is an open item — repo creation in progress (2026-09-11); once hosted, enable "Require status checks before merging" + "Require 1 approving review" on `main`, and turn on Dependabot alerts. |
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
- Postgres 16, volume `pgdata` via compose. Backups: see `DISASTER_RECOVERY.md`.

## 4. Release process

1. Backend change → `mvn -B verify` green; frontend change → lint +
   typecheck + build green.
2. `npm run openapi:gen` refreshed whenever payloads change with commit to same PR.
3. Merge → CI re-checks on PR (deploy pipeline lands when we provision VPS).
4. Deploy on the VPS: `docker compose build --pull && docker compose up -d` with
   env from a secret file (out of repo). Flyway migrates on backend start —
   **never** `harakiri` a DB; backup first (0.4).
5. Smoke after deploy: `GET /api/health`, login, and a single webhook POST
   against the staged `WEBHOOK_SECRET` (see `webhook-smoke.ps1` pattern).

## 5. Ops checklist (fill in at provision time)

- [ ] VPS provider + region chosen; firewall: 22 (key only), 80/443, no 8080 public.
- [ ] DNS `securetravels.in` → VPS; Nginx + certbot (Let's Encrypt, auto-renew).
- [ ] Generate & rotate strong `JWT_SECRET` (≥32 bytes) and `WEBHOOK_SECRET`.
- [ ] Managed Postgres (or on-VPS with automated backup) — see DR plan.
- [ ] `SPRING_PROFILES_ACTIVE=prod` (demo-data off, SQL logging off).
- [ ] Monitoring/restart policy for the compose services (systemd).
- [ ] Upstream proxy hardening: request size caps, per-IP nginx limits in front of the API.

## 6. Future phases (planned only)

- Phase 3: OpenSearch sidecar, Prometheus+Grafana exporters, mobile-ops host on same box.
- Phase 4 (only at real scale): Keycloak IAM, Vault for secrets, multi-branch
  regions topic re-opened (ADR 0002 triggers).
- Phase 12: load testing + multi-region *if the business genuinely expands there*.