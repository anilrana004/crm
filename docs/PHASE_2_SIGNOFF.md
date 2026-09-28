# Phase 2 — Sign-off Record

> **Date:** 2026-09-27 | **Author:** Senior Dev | **Status: PARTIAL — MODULES 1–4 ONLY, PHASE 2 NOT COMPLETE**

---

## 1. Executive summary

**Phase 2 as originally scoped (9 modules) is NOT complete and cannot be signed
off.** Only Modules 1–4 exist. Modules 5–9 were never started, and two
non-negotiable Phase 2 gates — vendor date-overlap conflict detection and
cache-invalidation behaviour — have no implementation to test, because nothing
checks vendor availability across overlapping dates and Redis is not a
dependency.

This record covers the scope that actually exists: **Modules 1–4 hardened,
verified, and passing a 271-test suite**, plus the security and deployment checks
the sign-off requires.

**Go/no-go recommendation: NO-GO for "Phase 2 complete". PARTIAL GO for Modules
1–4 as an internal/beta capability**, subject to the external gaps in §9, which
are credential- and infrastructure-blocked rather than code-blocked.
Two real defects were found and fixed during this hardening pass. Both would
have shipped:

- **`com.rabbitmq:amqp-client:5.25.0` — 7 advisories, 4 of them HIGH**,
  including three remotely-triggerable availability attacks (OOM, StackOverflow
  DoS) against the very component whose queue carries outbound customer messages.
  Upgraded to 5.36.0 (+ Netty 4.1.137.Final). See §7.1.
- **The presigned document-upload URL had never been exercised against a real
  object store.** It was unit-tested as a *string* only. A live MinIO round-trip
  test now proves the store accepts it. See §5.

### Scope correction (read this before trusting any earlier Phase 2 claim)

| Claim | Reality |
|---|---|
| "Phase 2 = 9 modules, all built" | Only 4 exist. Flyway stops at **V12**; migrations V1–V12 cover Phase 1 + Modules 1–4. |
| Vendor conflict detection / double-booking guard | **Does not exist.** Vendors *are* assigned (`batches.guide_id`; `operations_handoffs.guide_id/driver_id/hotel_vendor_id/transport_vendor_id`, all FK-constrained and category-validated, with `@Version` optimistic locking on the `Auditable` base). What is missing is the **availability/date-overlap check**: nothing verifies a guide is not already assigned to an overlapping batch, so the same guide can be double-booked. |
| Cache invalidation (Redis) | **Does not exist.** No Redis dependency, no `@Cacheable`, no TTL. `INTEGRATIONS.md` still lists Redis as *Planned*. |
| GSTIN validation | Length/format check only. No real GSTIN check-digit algorithm. |
| Payment gateway / refunds / EMI / webhooks | Not implemented (that is Module 5, absent). |
| Meta lead-ads reliability, automation rules, retention | Not implemented (Modules 7–9, absent). |
| Phase 1 seat-concurrency test exists | **True** — `Phase1HardeningIT.oneSeatBatchAdmitsExactlyOneOfTwoConcurrentClaims` uses a real `CountDownLatch` + `ExecutorService`. (An earlier grep in this session wrongly reported it missing; that was a PowerShell glob artefact.) |

The `finance`, `marketing`, `automation` and `customer` packages that exist in
the tree are **Phase 1** features, not Phase 2 Modules 5–9.

---

## 2. Module status

| # | Module | Status | Test evidence |
|---|---|---|---|
| 1 | Document & Compliance | **Done & hardened** | `ComplianceBoundaryTest` (10, **new**), `MinioUploadSmokeIT` (4, **new, live**), `SignedUploadUrlTest` (8), `ComplianceServiceTest` (6), `ComplianceFlowIT` (1) → **29** |
| 2 | Vendor Catalogue | **Done, but INCOMPLETE vs Phase 2 scope** | `VendorFlowIT` (4) — CRUD, GSTIN length, assignment + category validation, `@Version` optimistic locking. **No date-overlap/double-booking check, so the conflict-concurrency test is not possible.** → **4** |
| 3 | Batch Generator & Capacity | **Done & Tested** | `BatchRecurrenceTest` (19), `CapacityColorTest` (14), `CapacityAlertServiceTest` (12), `BatchCapacityIT` (8), `TripBatchIT` (6) → **59** |
| 4 | WhatsApp Business API & Timeline | **Done & hardened** | `WhatsAppCommunicationIT` (17), `WhatsAppSenderTest` (9), `InteraktWhatsAppGatewayTest` (7), `WhatsAppBrokerIT` (4, **live broker**), `HmacSignerTest` (4) → **41** |
| 5 | Payment Gateway & GST | **NOT STARTED** | none |
| 6 | Finance Visibility | **NOT STARTED** | none |
| 7 | Automation Rules Engine | **NOT STARTED** | none |
| 8 | Customer Retention | **NOT STARTED** | none |
| 9 | Meta Lead Ads Reliability | **NOT STARTED** | none |
| — | Cross-module E2E | **New** | `RevenueChainE2EIT` (1) — lead → booking → payment → compliance → documents → operations handoff → settlement, in one test |

---

## 3. Part A — Test coverage

**271 tests, 0 failures, 0 errors, 0 skipped** (`mvn test`, full suite).

| Group | Tests |
|---|---|
| Phase 1 regression suite | 137 |
| Module 1 — Document & Compliance | 29 |
| Module 2 — Vendor | 4 |
| Module 3 — Batch & Capacity | 59 |
| Module 4 — WhatsApp & Timeline | 41 |
| Cross-module E2E (new) | 1 |
| **Total** | **271** |

Up from the 256-test baseline: **+15** (10 boundary + 4 live-storage + 1 E2E).
**0 skipped** — the two live-dependency suites (`WhatsAppBrokerIT`,
`MinioUploadSmokeIT`) actually executed against real RabbitMQ and real MinIO.

### 3.1 Compliance boundary conditions (`ComplianceBoundaryTest`, new)

The mandated boundary case was a real gap: `ComplianceFlowIT` proved the happy
path and `ComplianceServiceTest` covered per-item status, but nothing pinned the
aggregate threshold at its edges. The new suite covers **exactly-at-threshold,
one-document-short, RED→YELLOW→GREEN transitions, `IN_PROGRESS` not counting as
complete, non-required items excluded, aggregation, integer truncation of the
percentage, and an empty batch** (10 tests).

### 3.2 Vendor concurrency — **CANNOT BE WRITTEN**

The user-specified gate is *"two concurrent bookings for the same vendor/date
must be rejected."* Vendor assignment exists and is validated
(`OperationsService.requireVendor(..., Vendor.Category.GUIDE, ...)`), and
optimistic locking is present via `@Version` on `Auditable`. But there is **no
date-overlap or availability query anywhere** — nothing checks whether a guide is
already booked across an overlapping date range. `@Version` would surface a
*concurrent write* collision; it would not prevent *semantic* double-booking
through sequential requests.

So the test cannot be written: it would have to assert behaviour that does not
exist. Recorded as **Incomplete — feature absent**, not as a passing test.

### 3.3 Cache invalidation — **CANNOT BE WRITTEN**

No Redis dependency or cache abstraction exists. Recorded as **Incomplete —
feature absent**.

### 3.4 Payment gateway HMAC / E2E payment flow / refund tiers / EMI / Meta — **N/A**

All belong to Modules 5–9, which do not exist.

### 3.5 End-to-end chain (`RevenueChainE2EIT`, new)

Closes the "no test crosses module seams" gap: one lead becomes a confirmed
booking with a partially-paid receivable, drives a document/compliance gate from
RED to GREEN, produces a departure-ready batch and an operations handoff, and
settles to a zero balance — asserting along the way that a **PENDING payment
receipt does not reduce the balance** (only COMPLETED/PARTIAL apply), that
marking COMPLETED without `paidAt` is rejected, and that settlement is only
recognised once the receipt is confirmed.

---

## 4. Part B — Security verification

| Check | Result | Evidence |
|---|---|---|
| Signed-URL expiry rejected | **PASS** | `SignedUploadUrlTest.expiredUrlIsRejected`, `urlJustAtExpiryIsRejected`, `tamperedSignaturePayloadIsRejected`; plus now proven enforced **by the live store** (`MinioUploadSmokeIT.theLiveStoreEnforcesExpiry` → 403) |
| Interakt webhook HMAC rejection | **PASS** | `WhatsAppCommunicationIT.webhookWithAnInvalidSignatureIsRejected` |
| Lead webhook HMAC rejection | **PASS** | `WebhookAutomationIT.missingOrInvalidSignatureReturnsUnauthorized`; `HmacSignerTest` (4) covers constant-time compare |
| No card data in code paths | **PASS** | Swept all `src/main` Java + SQL + YAML for PAN/CVV/expiry/track2/routing/IBAN/UPI patterns — only hits are SigV4 URL *expiry*. `Payment` has no card columns; external reference is `gateway_ref` only. No gateway integrated, so no inbound card path exists. |
| RabbitMQ payload carries no PII | **PASS** | `WhatsAppDispatchListener.onQueuedMessage(String messageId)` takes a UUID; the payload is looked up in DB by id. |
| OSV-Scanner (resolved graph) | **PASS after fix** | 7 advisories found and fixed — see §7.1 |
| Spring advisory re-check | **Not due** | Next re-check **2026-10-09**; today is 2026-09-27. |
| GitHub branch protection | **UNVERIFIED — not passing** | `gh` absent, no `GITHUB_TOKEN`; the protection endpoint returns **401** and the repo returns **404** unauthenticated (private). A 404 is indistinguishable from "not protected", so this is recorded as an open gap, not a pass. |

---

## 5. Part C — Deployment readiness

| Item | Result | Evidence |
|---|---|---|
| Live object-store upload | **PASS** | New `MinioUploadSmokeIT` (4 tests) against real MinIO on `127.0.0.1:9000`: presigned PUT **2xx**; unsigned PUT **403**; tampered signature **403**; expired URL **403**. Byte-for-byte read-back confirmed out of band with `mc cat` (23 B, `passport-scan-bytes-  ÿ`, ETag `9a5f1633…`). Buckets: `securetravels-documents`, `securetravels-backups`. **Caveat:** the service only issues presigned PUTs, so the test cannot delete what it writes — objects land under `smoke/` (outside the app's `compliance/<bookingId>/` prefix) and need `mc rm --recursive --force local/<bucket>/smoke/`. Manual cleanup was done for this run. **The suite is therefore dev/test-bucket only and must not be pointed at a production document bucket.** |
| Restore drill at V12 | **PASS** | Full cycle, recorded in `DISASTER_RECOVERY.md` (2026-09-27). `pg_dump -Fc` → MinIO (HTTP 200) → **downloaded back** (124,349 B) → `pg_restore --clean --if-exists` into scratch DB → **all 23 data tables with identical row counts** → `flyway_schema_history` carried all **12 rows, V1→V12**, including the V8–V12 objects (`traveller_checklists`, `documents`, `vendors`, `batches`, `seat_holds`, `whatsapp_templates`, `whatsapp_messages`, `timeline_events`). |
| Boot against the restore | **PASS** | Backend started with `--spring.flyway.enabled=false` and `ddl-auto: validate` → **`Started SecureTravelsApplication in 4.932 seconds`**, `/actuator/health` UP, **no migration executed** (no Flyway output at all — correct, per the drill procedure). Confirmed via `pg_stat_activity`: **10 Hikari connections on the restore DB, 0 on the live DB**, so validation provably ran against the restored schema. |
| Restore timing | **Measured** | Dump < 1 s, download < 1 s, **restore 533–585 ms over 3 runs (median ≈ 540 ms)**. Object-store-inclusive RTO **< 2 s** vs a < 6 h target. Parity check proven non-vacuous (`DELETE FROM seat_holds` produced the expected diff). Scratch DB dropped; artifacts removed. |
| Restore-drill limitations | **Disclosed** | Local MinIO, not production S3/R2/OSS (no cloud credentials). 124 KB dataset, so the sub-second figure is **not** representative of production volume. **Real RTO is unmeasured** until run against production-sized data on the VPS. |
| Production VPS / DNS / Nginx / TLS / systemd | **PENDING** | Not provisioned. `DEPLOYMENT.md` / `RUNBOOK_PRODUCTION_DEPLOY.md` remain pre-flight. |
| RabbitMQ in production | **PENDING** | Requires **Erlang/OTP 27** with RabbitMQ 4.x. Verified locally only. |
| CI merge-blocking | **PENDING** | No CI configured; branch protection unverifiable (§4). |

### 5.1 Regression check on the dependency upgrade

The AMQP client jumped **5.25.0 → 5.36.0** (11 minor versions). The full 271-test
suite — including the 4 live-broker tests — passed **on the new client**, so the
upgrade is verified at runtime, not just at compile time.

---

## 6. Login regression

The earlier "501 on login" report was **an outage, not a bug**: the backend JVM
had been stopped to regenerate the OpenAPI spec, so the frontend proxy surfaced a
**500** (not a 501). Fixed by restarting the backend.

Currently verified: `/actuator/health` **UP**; bad credentials return a correct
**401** both through the frontend proxy (`localhost:3000/api/auth/login`) and
directly (`localhost:8080`), proving the request traverses the full stack rather
than hitting a dead port. All 5 dev accounts exist and are active.

**Not re-verified:** a 200 login. The admin password is deliberately
unrecoverable — `BOOTSTRAP_ADMIN_PASSWORD` is `openssl rand -base64 24`, stored
BCrypt-12 and never logged (`DEPLOYMENT.md` §5) — so it cannot be reproduced from
the repo or the database. A previous session did confirm a 200 with a known
password; that credential no longer exists. **Recorded as a gap, not a pass.**

---

## 7. Dependency audits

### 7.1 OSV-Scanner — 7 HIGH/MODERATE advisories found and fixed

Fresh scan, **2026-09-27**. OSV-Scanner **v2.6.0** (binary SHA-256 verified
against the published `SHA256SUMS`), run over **all 104 resolved runtime
coordinates** via `api.osv.dev/v1/querybatch`.

**All 7 findings were in `com.rabbitmq:amqp-client:5.25.0`** — the jar Module 4
introduced, pinned by the Spring Boot 3.5.16 BOM:

| Advisory | Sev | Issue |
|---|---|---|
| `GHSA-jh4v-gfqj-7rhx` | HIGH | `Math.min(maxInboundMessageBodySize, 0)` defeats frame-size enforcement → OOM |
| `GHSA-68mj-5wr7-6fgg` | HIGH | Oversized LongString length → unchecked allocation → OOM |
| `GHSA-93j5-89vc-pph4` | HIGH | Unbounded recursive table/array nesting → StackOverflow DoS |
| `GHSA-6g32-pxv4-2wfj` | HIGH | Unvalidated `Class.forName` in JSON-RPC `ProcedureDescription` |
| `GHSA-5m9f-rphj-c435` | MODERATE | `TrustEverythingTrustManager` default in `useSslProtocol()` → MITM |
| `GHSA-qx7j-jv8m-fppr` | MODERATE | Malformed body frame → raw command-assembler exception |
| `GHSA-5xwg-cfvj-gff5` | LOW | Accepts broker frames larger than negotiated `frame_max` |

Three of the HIGHs are **remotely-triggerable availability attacks** against the
component carrying outbound customer messages. This was a real production risk,
not hygiene. Fixed in ≥ 5.34.0.

**Fix:** `rabbit-amqp-client.version` → **5.36.0**. That upgrade transitively
added 7 Netty jars carrying 3 further advisories (`GHSA-c4c3-7fpv-j4q5` CRITICAL
SNI-routing bypass, `GHSA-558v-64gr-wgg4` HIGH Bzip2 RLE hang,
`GHSA-fccg-mwvh-qqg4` MODERATE), so `netty.version` → **4.1.137.Final** as well.

**Final re-scan: 0 advisories across all 111 resolved runtime coordinates.**

> **Method note worth keeping:** `osv-scanner scan <project-dir>` reported **0
> vulnerabilities**, because it resolved only the 17 *direct* Maven coordinates
> and silently skipped transitives. The finding only appeared after resolving
> the actual graph. Always scan the resolved set, not the POM.

### 7.2 OWASP Dependency-Check / Spring advisories

Unchanged from Phase 1 and **not due** — next re-check **2026-10-09**. No OSS fix
exists for the Boot 3.5 line (EOL 2026-06-30); exposure assessed LOW-to-none
(no webflux/websocket/ldap on the classpath). Accepted risk.

---

## 8. What changed in this pass

| File | Change |
|---|---|
| `backend/pom.xml` | `rabbit-amqp-client.version` 5.25.0 → **5.36.0**; `netty.version` → **4.1.137.Final** (§7.1) |
| `backend/src/test/.../document/ComplianceBoundaryTest.java` | **new** — 10 boundary tests |
| `backend/src/test/.../document/MinioUploadSmokeIT.java` | **new** — 4 live object-store tests |
| `backend/src/test/.../phase2/RevenueChainE2EIT.java` | **new** — cross-module E2E |
| `docs/SECURITY.md` | OSV finding + fix, live-storage row, card-data row, branch-protection gap |
| `docs/DISASTER_RECOVERY.md` | 2026-09-27 drill row with timings and the V8–V12 table inventory |

---

## 9. Open items before any "Phase 2 complete" claim

**Blocking a full Phase 2 GO (code, not credentials):**

1. Build Modules 5–9 (payment gateway/GST, finance visibility, automation rules,
   retention, Meta ads). None exist.
2. Implement vendor **date-overlap / availability detection** (assignment and
   optimistic locking already exist — see §3.2), then write the concurrent
   double-booking test this record cannot contain.
3. Stand up the cache layer and its invalidation test, or formally drop the
   requirement.
4. Real GSTIN check-digit validation (currently length only).

**Blocked on credentials / infrastructure (not code):**

5. Live Interakt send + webhook (Module 4 external gap).
6. Branch protection — needs one authenticated run with repo admin rights.
7. Live Meta/lead-ads verification.
8. Production VPS, DNS, TLS, systemd, CI merge-blocking.
9. Production S3/R2/OSS smoke (only local MinIO was available).
10. Restore drill against production-sized data — the current RTO is measured on
    a 124 KB dataset and is not representative.
11. A successful 200 login re-verified with a known credential.

**No Phase 3 work should begin.** Phase 2 is partial.
