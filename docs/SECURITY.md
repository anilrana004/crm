# SecureTravels CRM — Security & Privacy

> **Status: ratified since Phase 1 Prompt 3.** This is the **single source of
> truth** for what is *implemented* vs *deferred*. It is a living document —
> update it whenever a control ships or moves phase.

Related decisions: architecture in `ARCHITECTURE.md`; async/broker timing in
`EVENT_ARCHITECTURE.md` and ADR 0003; DB invariants in `DATABASE.md`. The old
Fastify prototype in `server/`/`web/` is preserved side-by-side and is **not**
part of this posture.

Consolidated here from the original root `SECURITY.md` (Phase 1 Prompt 1)
and the Module 9 (webhook chain) additions.

---

## Threat model (in scope for Phase 1)

- Unauthenticated callers probing the API.
- Account takeover via weak/stolen credentials or refresh-token theft.
- Lateral movement between roles (sales/ops vs manager/admin/ceo).
- Data leakage across tenant-like boundaries (one salesperson seeing another's leads).
- Injection (SQL, NoSQL, XSS), which is why all persistence is parameterized and
  all free-text is sanitized on write and escaped on render.
- Logic abuse: forced status transitions, duplicate leads, missing consent.
- Abuse via a single shared source IP (credential stuffing / brute force).

Out of scope for the threat model here: third-party SaaS, multi-branch/org tenancy
(Phase 4 ABAC), and the payment gateway itself (Phase 2).

---

## Implemented in Phase 1

### Authentication & session management
| Control | Where |
|---|---|
| BCrypt password hashing, strength **12** | `SecurityConfig`, `User` |
| **JWT access token, 15-minute** expiry (stateless, HS256 via `jjwt`) | `JwtService`, `JwtAuthenticationFilter` |
| **Refresh token, 7-day** expiry, **rotated on every use** (old token revoked, replaced) | `AuthService`, `RefreshToken` entity |
| Refresh tokens persisted as **SHA-256 hash** only (plaintext never stored) | `refresh_tokens.token_hash` |
| Refresh reuse detection (replay of a rotated token rejected) | `AuthService.refresh` |
| Logout revokes the refresh token server-side | `AuthService.logout` |
| `ROLE_` claim namespacing; stateless filter, no server-side session | JWT filter |
| Tokens never exposed in `localStorage` for the server (kept client-side for SPA, cleared on 401) | `frontend/src/lib/api.ts` |
| Login recovery: 401 for both unknown-email and wrong-password (no user enumeration) | `GlobalExceptionHandler` |

### Authorization (RBAC)
| Control | Where |
|---|---|
| Method security via `@PreAuthorize` (e.g. `POST /api/leads` requires SALES/MANAGER/ADMIN/CEO) | `LeadController` |
| **Service-level ownership** enforcement: SALES/OPS only see & mutate their own records; MANAGER/ADMIN/CEO see all — enforced in `LeadService`, not just at the filter | `LeadService` |
| Cross-owner mutation returns `403 FORBIDDEN` | `GlobalExceptionHandler`, `ForbiddenException` |
| Role-aware UI guard (`Protected` wrapper, redirect on 401) | `frontend/src/components/Protected.tsx` |

### Rate limiting & abuse prevention
| Control | Where |
|---|---|
| **Login rate limit: 5 attempts / IP / 15 min** via Bucket4j | `RateLimitingFilter` |
| **Webhook rate limit: 20 requests / IP / min** on `POST /api/webhook/lead` | `RateLimitingFilter` |
| `429 Too Many Requests` with `Retry-After` on breach | `RateLimitingFilter` |
| (Rate limits are in-memory per-instance in Phase 1; a shared Redis-backed limiter lands in Phase 2) | — |

### Public webhook integrity (website lead intake)
| Control | Where |
|---|---|
| **HMAC-SHA256 request signing**: caller sends `X-Webhook-Signature: sha256=<hex>` computed over the raw body with the shared secret; verified **constant-time** (`MessageDigest.isEqual`) | `WebhookSignature`, `WebhookService` |
| Endpoint is public but **per-request authenticated by the signature**; invalid/missing signature → `401 INVALID_SIGNATURE` | `WebhookController`, `WebhookSignatureException` |
| Secret injected via `WEBHOOK_SECRET` env (dev default only); rotate before any real deployment | `application.yml`, `AppProperties.Webhook` |
| Every inbound call (success/duplicate/failure) is **audited in `webhook_logs`** with the raw payload and outcome | `V6__webhook_assignment.sql`, `WebhookLog` |
| Payload validated manually server-side (name, Indian mobile regex, email, **mandatory DPDPA consent**) before any lead is created | `WebhookService.validate` |
| Oversized payloads (>16 KB) and oversized fields rejected with 400 **before** ingest; stored log payload bounded to 10,000 chars | `WebhookController`, `WebhookService.log` |
| Duplicate phone numbers are soft-rejected (`200 duplicate:true`, existing lead returned) rather than duplicated | `WebhookService` |
| Time-limited owner assignment: **round-robin cursor `assignment_state`** (least-recently-assigned SALES first, fallback MANAGER, else `503`) | `RoundRobinService` |

### Data validation & integrity
| Control | Where |
|---|---|
| Jakarta Bean Validation on every DTO (`@NotBlank`, `@Email`, `@Size`, `@Future`, ranges) | all `dto/*` records |
| Server-side Indian mobile regex `^(\+?91[- ]?)?[6-9][0-9]{9}$` | `LeadCreateRequest`, `PhoneUtils` |
| **OWASP HTML Sanitizer** strips active markup on write (e.g. `<script>` removed from remarks) | `XssSanitizer.text` |
| React escapes/sanitizes on render (no `dangerouslySetInnerHTML`) | `frontend/` |
| Parameterized queries only (JPA/JDBC `?` binding; **no string-concatenated SQL**) | repositories |
| DB-level **CHECK constraints** replicate enum/domain integrity (reachable invariants) | migrations |
| **Booking invariant** `I1`/`I2` (booking_type == trip.booking_type; FIXED_BATCH⇔batch) enforced by DB trigger | `trg_booking_consistency` |
| Duplicate-lead detection (same phone, non-LOST) returns `409 CONFLICT` | `LeadRepository.findFirstActiveDuplicate`, `LeadService` |

### Privacy & consent (DPDPA alignment)
| Control | Where |
|---|---|
| **Explicit consent required (mandatory) at lead creation** — API rejects `consentGiven=false` | `LeadService.create` |
| Consent timestamp + scope captured and returned | `Lead` entity (`consent_captured_at`, `consent_scope`) |
| `customer360` keeps the **single canonical consent/opt-in record** referenced by downstream flows | `DATABASE.md` §4 |
| Marketing opt-in is a separate, explicitly-stored flag (not inferred) | `customer360.marketing_opt_in` |
| PII is stored in typed columns; travellers link to `customer360` when identifiable | `travellers`, `customer360` |

### Auditability
| Control | Where |
|---|---|
| **`audit_log`** records every `STATUS_CHANGE` (and create/update) with actor, entity, old/new values, timestamp | `AuditService`, `LeadService` |
| Audit rows are append-only in Phase 1; signed/immutable audit lands with P2 observability | — |

### Transport & config security
| Control | Where |
|---|---|
| CORS whitelist only `http://localhost:3000` / `http://127.0.0.1:3000` (overridable via env) | `SecurityConfig` |
| JSON `401`/`403` bodies, no redirects on auth failures | `SecurityConfig` |
| **No stack traces leaked** in error responses — stable codes via `ApiError` | `GlobalExceptionHandler` |
| Secrets via environment variables only (JWT secret, DB URL); no keys in repo | `application*.yml` |
| `spring.jpa.hibernate.ddl-auto: validate` (schema is Flyway-managed, not auto-DDL) | `application.yml` |
| Demo-data bootstrap enabled only in dev profile; **off in prod** | `application-prod.yml`, `AppProperties` |
| TLS / Let's Encrypt configured at the reverse proxy (Nginx) in the deploy stack | `DEPLOYMENT.md` |

---

## Deferred to later phases (intentionally not in Phase 1)

| Area | Phase | Reason |
|---|---|---|
| Shared/centralized rate limiting (Redis/Bucket4j cluster) | P2 | in-memory limiter is fine for single-instance dev |
| WhatsApp Business / outbound webhooks | P2 | inbound webhook is HMAC-signed in Phase 1 |
| Payment gateway (PCI-relevant data, tokenization) | P2 | no gateways in Phase 1; `gateway_ref` is a placeholder ref, never raw card data |
| Rewarded/immutable audit (signing, WORM), DLP | P2/P3 | audit is append-only for now |
| Encrypted field-level PII at rest, full data-retention policy automation | P3/P4 | DPDPA consent is captured; retention tooling later |
| Backup/PITR via managed Postgres, backup rotation | P2 | tracked in `DISASTER_RECOVERY.md` |
| Grafana / ELK observability + alerting | P3 | Phase 1 uses structured logs + actuator only |
| Keycloak / OIDC SSO | P4 | overkill for Phase 1; JWT suffices |
| **Attribute-Based Access Control** (multi-branch regions, field-level) | P4 | RBAC + ownership covers Phase 1 |

---

## Phase 1 Prompt 4 — verification results (2026-09-11)

Every item under "Implemented in Phase 1" above was re-verified against the
current codebase during the Prompt-4 sign-off, not assumed. Results:

| Control | Result | Evidence |
|---|---|---|
| `@PreAuthorize` on every non-public controller endpoint | **Verified** | grep across 13 controllers; public-only set = auth login/refresh/logout, webhook (HMAC), both health endpoints, springdoc/docs |
| Service-level ownership (SALES/OPS own-record isolation) | **Verified** | `LeadSliceIT.salesCannotManageOthersLeads` (SALES B PATCH on SALES A's lead → 403); cross-owner 403s also in `PaymentFlowIT`, `OperationsFlowIT`, `Customer360FlowIT` |
| No PII (mobile / whatsapp / email) in log output | **Verified** | grep of `src/main` — no SLF4J/sysout statement interpolates those fields; `webhook_logs` stores raw payload by design (audit table, not logs) |
| HMAC webhook signature enforced (constant-time) | **Verified** | `WebhookAutomationIT` signed → 201; missing/invalid → `401 INVALID_SIGNATURE` |
| Rate limiting wired, not planned | **Verified** | `RateLimitIT` (new): login 5×/IP/15 min → `429 RATE_LIMITED` + `Retry-After` on 6th; webhook 20/min → 429 on breach |
| Prod hardening: `show-sql:false`, `bootstrap-demo-data:false`, actuator = health only, `include-stacktrace:never`, Hibernate SQL OFF | **Verified** | `application-prod.yml` + Prompt-4 prod boot log (DEPLOYMENT.md §1b) |
| OWASP Dependency-Check HIGH/CRITICAL with released fix | **Verified** | report-only run (75 deps); residual = newest versions; **re-checked 2026-09-11** vs spring.io/security + Maven Central: no OSS fix exists for the Boot 3.5 line (EOL 2026-06-30), exposure LOW-to-none (no webflux/websocket/ldap on classpath; SSE/SpEL-compiler unused); accepted-risk with monthly RSS + quarterly OSV cadence, next re-check **2026-10-09** (PHASE_1_SIGNOFF.md §7.2) |
| **OSV-Scanner over the resolved runtime graph (Module 4 deps)** | **Verified — 1 real finding, fixed** | **2026-09-27.** OSV-Scanner **v2.6.0** (SHA-256 `e0ed7644…` matched the published `SHA256SUMS`), queried **all 104 resolved runtime coordinates** via `api.osv.dev/v1/querybatch`. **Finding: 7 advisories in `com.rabbitmq:amqp-client:5.25.0`**, the jar Module 4 introduced — `GHSA-jh4v-gfqj-7rhx` (HIGH, frame-level OOM via `Math.min(maxInboundMessageBodySize, 0)`), `GHSA-68mj-5wr7-6fgg` (HIGH, oversized LongString → OOM), `GHSA-93j5-89vc-pph4` (HIGH, unbounded nesting → StackOverflow DoS), `GHSA-6g32-pxv4-2wfj` (HIGH, unvalidated `Class.forName` in JSON-RPC), `GHSA-5m9f-rphj-c435` (MODERATE, `TrustEverythingTrustManager` default in `useSslProtocol()` → MITM), `GHSA-5xwg-cfvj-gff5` (LOW), `GHSA-qx7j-jv8m-fppr` (MODERATE). All fixed in ≥ 5.34.0. Three of the HIGHs are **remotely triggerable availability attacks on the exact component whose queue carries outbound customer messages**, so this was a real production risk, not a hygiene item. **Fixed:** `rabbit-amqp-client.version` → **5.36.0**. That upgrade transitively introduced 7 Netty jars carrying 3 further advisories (`GHSA-c4c3-7fpv-j4q5` CRITICAL SNI bypass, `GHSA-558v-64gr-wgg4` HIGH Bzip2 RLE hang, `GHSA-fccg-mwvh-qqg4` MODERATE), so `netty.version` → **4.1.137.Final** as well. **Re-scan: 0 advisories across all 111 resolved runtime coordinates.** Full suite green at 267 tests on the new client. **Method note:** `osv-scanner scan <dir>` on the project root reported **0 vulnerabilities** because it resolved only the 17 *direct* Maven coordinates and skipped transitives — the finding was only visible after resolving the real graph. Always scan the resolved set, not the POM. |
| BCrypt strength 12 | **Verified** | `SecurityConfig` → `new BCryptPasswordEncoder(12)` |
| Presigned upload URL is accepted by a real S3-compatible store (not just well-formed) | **Verified (live, 2026-09-27)** | New `MinioUploadSmokeIT` (4 tests, skips when nothing listens on 9000) speaks real HTTP to MinIO: presigned PUT **accepted 2xx**; unsigned PUT → **403**; tampered signature → **403**; **expired** presigned URL → **403 by the store itself**, so the TTL is enforced server-side and not only by our own verifier. Content read-back proven byte-for-byte out of band via `mc cat` (23 B object, `passport-scan-bytes-  ÿ`, ETag `9a5f1633…`); smoke objects deleted. `SignedUploadUrlTest` (8) still covers the string-level rules. This closes the last "signer verified, real PUT unproven" gap. |
| No card data anywhere in the system | **Verified (2026-09-27)** | Swept all `backend/src/main` Java + SQL + YAML for `cardNumber\|cvv\|cvc\|expiryMonth\|track2\|routingNumber\|accountNumber\|iban\|upiId\|cardHolder` — the only hits are the SigV4 presigned-URL *expiry* in `SignedUploadUrlService`, unrelated to cards. `Payment` has no PAN/CVV/expiry columns at all; the only external reference is `gateway_ref` (a gateway-side token). No payment gateway is integrated yet (Module 5 is unimplemented), so there is no inbound path by which card data could arrive. |
| Secrets not committed; env-driven | **Verified** | `application*.yml` use env placeholders with dev-only defaults; secrets **rotated 2026-09-11** (old defaults invalidated; 64-byte JWT + 48-byte webhook secrets stored in Windows user env only; boot cmd `app-boot3.cmd` passes them explicitly; old `app-boot2.cmd` superseded). Pre-rotation sessions invalidated by design; post-rotation login + webhook smoke verified end-to-end. |

**Open security follow-ups (not phase-1 code blockers):** NVD API key deleted
from `owasp-run8.cmd` after confirming no other references (2026-09-11); a fresh
key is optional since OSV-Scanner + `npm audit` are the primary monitoring loop.
`JWT_SECRET` and `WEBHOOK_SECRET` rotation is complete (2026-09-11). Remaining:
enable GitHub branch protection once the repo is hosted (repo admin).

**Branch protection could not be verified (2026-09-27).** `gh` is not installed
on this machine and no `GITHUB_TOKEN`/`GH_TOKEN` is present, so
`api.github.com/repos/querytamasraapple-hub/securetravels-crm/branches/main/protection`
returns **401**, and the repository itself returns **404 unauthenticated** (i.e. it
is private). A 404 is *indistinguishable* from "not protected", so the
requirement is recorded as **UNVERIFIED — not passing** rather than assumed. It
needs one authenticated run from a machine with repo admin rights; it cannot be
closed from here.

---

## Operational notes

- **Prod must override**: `JWT_SECRET`, `WEBHOOK_SECRET`, `DB_URL`/password, CORS, and
  `app.bootstrap-demo-data=false`. See `application-prod.yml`.
- Rotate secrets before any real deployment; refresh tokens are hashed but
  access tokens are signed with this secret.
- The login limiter is per-process; behind a load balancer, move to a shared store (P2).
- `openapi.json` in `frontend/` is regenerated from `GET /v3/api-docs` and drives
  the **typed** client (`src/openapi/generated.ts`) — regenerate after API changes:
  `npm run openapi:gen`.