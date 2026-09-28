# SecureTravels CRM — Disaster Recovery

> **Status: ratified since Phase 1 Prompt 3; automation added 2026-09-26.** The
> plan below is now backed by a real, tested script (`scripts/backup-postgres.sh`)
> plus a systemd timer. It is **not yet running in production** — no VPS exists
> (see `RUNBOOK_PRODUCTION_DEPLOY.md`). PITR remains a Phase-2 item.

---

## 1. Data & state inventory

| Asset | Location | Recovery source |
|---|---|---|
| All business data (leads, bookings, payments, ops, customers, tasks, audit, webhook logs, targets) | PostgreSQL 16 | backups (below) |
| File objects (documents — Phase 2) | S3-compatible bucket | bucket versioning + cross-region copy (configure at Phase 2) |
| Rate-limit cursors / session state | in-memory (Phase 1) | none needed — lost on restart by design |
| Round-robin assignment cursor | `assignment_state` in Postgres | backups |

## 2. Backup schedule (target, ratifiable)

The long-term target is **managed Postgres with point-in-time recovery
(PITR)** — per `SECURITY.md`, listed as a Phase-2 infra task. Until then:

| Cadence | What | Retention |
|---|---|---|
| Daily | `pg_dump -Fc` full logical backup | 14 daily |
| Weekly | consolidated full | 8 weekly |
| Monthly | archive | 12 monthly |
| Continuous (P2, when managed) | WAL/PITR continuous archiving | 30 days |

Backups must be encrypted (age/PGP) and stored off-VPS (S3 bucket, separate
account) — never only on the same disk as the DB.

### 2a. Implemented automation (2026-09-26)

| Piece | Path | State |
|---|---|---|
| Backup script | `scripts/backup-postgres.sh` | ✅ **tested locally** — see drill log |
| systemd unit + timer | `scripts/systemd/securetravels-backup.{service,timer}` | written, **not installed** (no host) |
| Install instructions | `RUNBOOK_PRODUCTION_DEPLOY.md` §6 | pending provisioning |

The script takes its bucket and credentials from the **same `STORAGE_*`
environment variables the document-storage service already uses**, so there is
one set of bucket credentials to rotate rather than two. Object keys are written
under `backups/postgres/` — keep the backup bucket **separate** from the
documents bucket, in a separate account, per the rule above.

Design points that matter operationally:

- `pg_dump -Fc` is **not** gzipped again — it is already compressed.
- A dump is only uploaded if it is non-empty, above `BACKUP_MIN_BYTES`, **and**
  passes `pg_restore --list` (which reads the archive TOC and therefore detects
  a truncated or half-written file). A backup that cannot be verified is a
  failure, not a success.
- Every abnormal path exits non-zero with a named reason; a `mkdir` lock stops
  overlapping runs.
- Encryption at rest is **opt-in** via `BACKUP_AGE_PASSPHRASE` (requires `age`).
  Set it in production — the bucket's own encryption is not sufficient for a
  backup that may be restored by someone else.
- Remote retention is a **bucket lifecycle policy**, not the script. The script
  only prunes its own local staging copies (`BACKUP_RETAIN_DAYS`); it never
  issues `DeleteObject`, so a bug in it cannot destroy old backups.

Verify the signer at any time without touching the database:

```bash
./scripts/backup-postgres.sh --self-test
```

Remote retention policy to configure on the backup bucket:

| Prefix | Days |
|---|---|
| `backups/postgres/` | 14 |
| weekly consolidation | 8 weeks |
| monthly archive | 12 months |

## 3. Restore-drill process (manual, today)

**RTO target:** < 6 hours for a full recovery. **RPO target:** ≤ 24 hours
(daily backup) — improving to minutes at P2 with PITR.

Drill steps (run at least once per quarter; record the timestamp + result):

1. Provision a fresh Postgres 16 container/instance (`securetravels_restore`).
2. `pg_restore -U postgres -d securetravels_restore --clean --if-exists <latest.dump>`.
3. Start the backend against the restore DB. **Do not** run Flyway
   migrations on top of a restored dump (the dump already contains the full
   schema); the app start must pass the Flyway checksum/validation and
   `ddl-auto: validate` cleanly against it — that is the real restore test.
4. `GET /api/health` returns UP; login works with a dev/demo user.
5. Spot-check: count of `leads`, latest `webhook_logs` row, latest
   `assignment_state` row, latest payment; compare to backup manifest.
6. Teardown the restore instance; record outcome in this file's drill log.

### Restore drill log

| Date | Backup used | Outcome | Notes |
|---|---|---|---|
| 2026-09-27 | `scripts/backup-postgres.sh` → live MinIO (local) → `securetravels-backups/backups/postgres/securetravels_crm-20260927T180629Z.dump` (124,349 B, 185 archive entries) | **Restore OK at V12, boot-verified** — signer `--self-test` matched the AWS reference vector; upload HTTP 200; object **downloaded back** (124,349 B, ETag `48df9880…`); `pg_restore --list` readable (185 entries); `pg_restore --clean --if-exists` into scratch `securetravels_restore_drill`; **all 23 data tables restored with identical row counts** (leads 30, bookings 16, payments, travellers 59, vendors 12, batches 43, whatsapp_templates 9, …); `flyway_schema_history` carried across **all 12 rows, V1→V12**, including the V8–V12 objects `traveller_checklists`, `documents`, `vendors`, `batches`, `seat_holds`, `whatsapp_templates`, `whatsapp_messages`, `timeline_events`. **Backend booted against the restored dump with `--spring.flyway.enabled=false` and `ddl-auto: validate`** → `Started SecureTravelsApplication in 4.932 seconds`, `/actuator/health` **UP**, and `pg_stat_activity` confirmed **10 Hikari connections on `securetravels_restore_drill` and 0 on the live DB** (so the validation provably ran against the restore). **Timings: dump < 1 s, download < 1 s, restore 533–585 ms over 3 runs (median ≈ 540 ms)** — i.e. object-store-inclusive RTO **< 2 s** against the < 6 h target. Parity check proven **non-vacuous**: `DELETE FROM seat_holds` in the restored copy produced the expected diff. Scratch DB dropped; drill artifacts removed. | Closes the two V8–V12 gaps the earlier drills predate. **Bonus finding:** a tampered-`leads` DELETE was rejected by `tasks_lead_id_fkey`, i.e. referential integrity survived the dump/restore. Two drill-harness defects found and fixed, not product bugs: `bc` absent on Git Bash (timings switched to `date +%s%3N`), and Windows `psql` CRLF corrupting a `read` loop (piped through `tr -d '\r'`). **Not production S3 and not production volume** — local MinIO, 124 KB dataset. |
| 2026-09-26 | `backup-postgres.sh` → live MinIO (`minio/minio` RELEASE.2025-09-07, local) → `securetravels-backups/backups/postgres/securetravels_crm-20260926T161823Z.dump` | **End-to-end VERIFIED through a real S3-compatible store** — upload HTTP 200; object downloaded back (82,341 B); `pg_restore --list` readable (161 entries); `pg_restore --clean --if-exists` into scratch `minio_restore` succeeded; row counts **identical to live** (leads 7, users 5, audit_log 12, flyway 10). | Closes the "signer verified, live PUT unproven" gap. **Found and fixed 2 real bugs** — see `RUNBOOK_PRODUCTION_DEPLOY.md` §0. Not production S3; no cloud credentials were available. |
| 2026-09-26 | `scripts/backup-postgres.sh` → `pg_dump -Fc` of live `securetravels_crm` (80,958 bytes, 161 archive entries) | **Restore OK** — `pg_restore --clean --if-exists` into scratch `bk_restore_check`; **all 21 public tables restored with identical row counts** (leads 7, users 5, audit_log 11, batches 1, flyway_schema_history 10, …); `diff` of source vs restored counts **identical**; `sha256sum -c` **passed**. Parity check proven non-vacuous by tampering a restored table and confirming the mismatch is detected. Failure paths verified: unreachable port → exit 1 "pg_dump FAILED"; nonexistent DB → exit 1; missing `STORAGE_*` → exit 1 naming the variable; concurrent run → refused by lock; `--dry-run` → writes nothing. | Closeout drill; first use of the automated script |
| 2026-09-11 | `pg_dump -Fc` of live `securetravels_crm` (90 KB, 20 tables) | **Restore OK** — `pg_restore --clean --if-exists` into scratch `securetravels_drill`; Flyway checksum validation passed on the restored schema (no migrations re-run); counts matched source (leads 15, 20 tables). Dump **0.4 s**, restore **0.7 s** → **measured RTO ≈ 1.1 s** (target < 6 h). Scratch DB dropped after. | Prompt-4 drill |
| 2026-09-09 | `pg_dump -Fc` of live `securetravels_crm` (86 KB, 19 tables) | **Restore OK** — `pg_restore` into scratch `securetravels_restorecheck`; exact `COUNT(*)` matched source on all 19 tables (leads 15, bookings 6, payments 4, webhook_logs 36, audit 93, …). Dump **0.2 s**, restore **0.4 s** on local Postgres. Scratch DB dropped after. Boot-against-restore check: see Prompt-4 signoff verification (prod-profile boot). | prior drill |

## 4. Failure scenarios

| Scenario | Response |
|---|---|
| VPS lost (full) | Provision new VPS → pull compose stack → restore latest backup → re-point DNS → rotate/verify secrets |
| DB corrupt / data loss | Restore latest dump (see drill); replay 24h of manual re-entry only if necessary |
| Backend crash-loop | Check `backend8.log`/container logs; rollback image if migration introduced the fault; limit Blast radius — do NOT retry `mvn` over an altered schema |
| Secrets leak | Rotate `JWT_SECRET` **and** `WEBHOOK_SECRET` immediately (refresh tokens are hashed but access tokens rely on the signing secret) |
| `dl`/disk full on VPS | Monitor disk; backups must be streamed/S3, not stacked on local disk |

## 5. Ownership

Backup cron + quarterly drill owner: **the person who provisions the VPS**
(Phase-1 completion item — `RUNBOOK_PRODUCTION_DEPLOY.md` §6, and the ops
checklist at `DEPLOYMENT.md` §6). The backup script and systemd timer now
exist and are tested; installing and running them is part of provisioning.
WAL/PITR archiving remains Phase-2 managed-Postgres work.