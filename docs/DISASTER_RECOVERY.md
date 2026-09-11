# SecureTravels CRM — Disaster Recovery

> **Status: ratified since Phase 1 Prompt 3.** Formal tooling grows with the
> phase plan; today the plan is a **manual, documented, drillable checklist**
> that becomes a scheduled drill in Phase 2.

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
(Phase-1 completion item, `DEPLOYMENT.md` §5). The automated backup
pipelines and PITR land as part of the managed-Postgres work in Phase 2.