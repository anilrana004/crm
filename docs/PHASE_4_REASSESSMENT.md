# Phase 4 — Reassessment Gate Record (per ROADMAP.md)

> **Reassessment performed:** 2026-09-28
> **Result: NONE of Phase 4 is justified yet — the phase is closed at this gate with deferred modules and recorded re-evaluation triggers. No Phase 4 code was written.**
>
> Phase 4 in ROADMAP.md is conditional ("only if the business genuinely reaches
> that scale"). The gate below answers its three checks with first-hand
> evidence; two of the three definitive audits (team count) come from a live
> read of the running application on 2026-09-28.

---

## Finding 1 — Multi-branch: NOT justified (defer Modules 2 and 4)

**Question:** Does SecureTravels operate from more than one physical location
now, or have concrete, funded plans to within the next 1–2 quarters?

**Evidence gathered:**

- No second-office evidence exists anywhere in the repository: no lease,
  no hired regional manager, no funded expansion plan, no multi-location
  operations note. `PRODUCT_REQUIREMENTS.md` lists "multi-branch tenancy and
  enterprise IAM" only as a long-horizon enterprise requirement (the 5-year
  vision), not a stated near-term reality.
- `SECURITY.md` explicitly treats multi-branch tenancy and org tenancy as
  **out of scope for the current threat model**, and ranks ABAC
  (multi-branch regions, field-level) at priority **P4** with the note
  "RBAC + ownership covers Phase 1".
- The business operates from its Dehradun HQ as a single site (the eventual
  Module 4 backfill default itself is "Dehradun HQ").

**Decision:** Multi-branch support and ABAC (which exists specifically to
support branch-scoped permissions) are **not justified**. Modules 2 and 4 of
Phase 4 are **deferred**.

**Next-reassessment trigger:** Re-evaluate **immediately** if either of these
becomes real (not aspirational): (a) a second physical office/operations site
is opened or funded, or (b) a regional manager is hired and needs scoped
visibility. Otherwise re-run this gate no earlier than the Phase 7 (Sales CRM
Depth) checkpoint.

---

## Finding 2 — Enterprise IAM / Keycloak: NOT justified (defer Module 1)

**Question:** How many total users does the system have across all roles, and
is there any real SSO/external-IdP requirement?

**Evidence gathered (first-hand, 2026-09-28):**

- A live read of the running application's team report on 2026-09-28
  enumerates the complete set of accounts that own data: **Admin, Manager,
  and three Sales consultants (Ravi Singh, Meera Joshi, Suresh Rawat) — i.e.
  five real users** across the roles `ADMIN`, `MANAGER`, `SALES` (the role
  model also includes `OPS` and `CEO`, neither of which has an account
  yet). Customer base observed: 16 records.
- This is a low single-digit team on a single site. RBAC + ownership checks
  (already implemented) cover this size completely.
- `SECURITY.md` already ranks **Keycloak / OIDC SSO at P4** with the note
  "overkill for Phase 1; JWT suffices".
- No corporate partner, no SAML/OIDC, no Google/Microsoft SSO requirement
  exists anywhere in the docs.

**Decision:** Keycloak/enterprise IAM is **not justified** at this scale and
operating cost (a whole additional service to run, patch, and monitor for a
five-user team with no SSO requirement). Module 1 is **deferred**.

**Next-reassessment trigger:** Re-evaluate when (a) the team reaches
~25+ active users, or (b) a partner/customer requires SSO/SAML integration,
or (c) multi-branch (Finding 1) becomes real. Otherwise re-run at the
Phase 7 checkpoint.

---

## Finding 3 — Vault for secrets: NOT justified (defer Module 3)

**Question:** How many distinct secrets does the system manage, and how many
people need access to some-but-not-all of them?

**Evidence gathered (enumerated from `backend/src/main/resources/application*.yml`
and `docker-compose.yml`, 2026-09-28):**

The system manages **8 secret values** today, all injected as environment
variables with dev/test defaults that contain **no live credentials**:

| Secret | Source env var | Notes |
|---|---|---|
| JWT signing | `JWT_SECRET` | placeholder default |
| Internal webhook HMAC | `WEBHOOK_SECRET` | webhook sender |
| Interakt/WhatsApp BSP API key | `INTERAKT_API_KEY` | empty default — no live key yet |
| Interakt webhook HMAC | `INTERAKT_WEBHOOK_SECRET` | placeholder default |
| PostgreSQL password | `DB_PASSWORD` | local dev only |
| MinIO/S3 secret key | `STORAGE_SECRET_KEY` | (+ `STORAGE_ACCESS_KEY`, non-secret username) |
| RabbitMQ password | `RABBITMQ_PASSWORD` | `guest` default |
| Grafana admin password | `GRAFANA_ADMIN_PASSWORD` | local-dev only, ops stack |

Non-secret placeholders (`DB_USER`, `RABBITMQ_USER`, `STORAGE_ACCESS_KEY`,
`INTERAKT_BASE_URL`, queue/topic names, thresholds) are excluded from the count.
There is **no payment-gateway key** (payment is tracked manually, no gateway
integration exists yet) and **no NVD key**.

**Decision:** Eight secrets — several still placeholder — managed by the
single developer via env-var injection with access discipline remains the
correct approach for this team. Standing up Vault (or moving to a cloud
secrets manager yet) is **not justified**; avoiding another self-hosted
service aligns with the phase-gate intent. Module 3 is **deferred**.

**Next-reassessment trigger:** Re-evaluate Vault/a cloud secret manager when
**either** (a) the secret count exceeds ~15, **or** (b) more than ~3 people
need access to distinct subsets of secrets, **or** (c) real
payment-gateway/production BSP credentials go live and a rotation story is
needed.

---

## Deferred-module summary and interplay with the roadmap

| Phase 4 module | Finding | Status | Trigger(s) |
|---|---|---|---|
| 1. Enterprise IAM (Keycloak) | NOT justified (5 users, no SSO) | Deferred | ~25+ active users, or a real SSO/SAML requirement, or multi-branch |
| 2. ABAC layer | NOT justified (single site) | Deferred | Second real office / regional manager with scope needs |
| 3. Vault for secrets | NOT justified (8 secrets, 1 accessor) | Deferred | Secret count > 15, or >3 accessors with subsets, or live payment/BSP creds |
| 4. Multi-branch | NOT justified (single site) | Deferred | Second real office / higher scale (Phase 12 hardening otherwise) |

The 2026-09-28 re-evaluation trigger set should be revisited automatically at
the **Phase 7 checkpoint** (or earlier if any trigger fires). This gate is the
same anti-pattern guard as ADR-0002 (modular monolith over microservices) and
ADR-0003 (no message broker until Phase 6), applied one level up: refuse to
build enterprise infrastructure a single-site five-user business does not yet
need.

## Version control

Recorded in `ROADMAP.md` (Phase 4 note) and this file. No Phase 4 code was
written; the working tree contains only the reassessment record and the
ROADMAP amendment.