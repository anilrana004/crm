# SecureTravels CRM — Production Deploy Runbook

> ## Status: **PENDING — provisioning not performed**
>
> **This runbook has not been executed end-to-end.** As of **2026-09-26** there
> is **no droplet, no DNS record pointing at it, and no TLS certificate** for
> this project, and execution requires hosting-account credentials that are
> **not available in this environment**. Every command below is **unverified
> against a real host**; it is written from the documented target topology plus
> the things we *have* verified (see the table in §0).
>
> **Domain: `securetravels.in` is confirmed live and serving as of 2026-09-26**
> (fetched successfully). Note that a live domain is *not* the same as a
> provisioned deployment — no droplet, Nginx, or certificate for it is managed
> by this project yet, and §1–§2 below are still unchecked.
>
> Do not mark any checkbox below until a human with account access has actually
> done it.
>
> Owner: the person who provisions the VPS (see `DISASTER_RECOVERY.md` §5).

---

## 0. What is real vs. what is aspirational

| Area | Status 2026-09-26 |
|---|---|
| Application builds, tests, boots in `prod` profile | ✅ **verified** (215 tests green; prod boot + health + login exercised) |
| Flyway migration chain V1–V11 | ✅ **verified** (applied cleanly to an existing DB and to fresh DBs) |
| `scripts/backup-postgres.sh` dump + integrity + restore | ✅ **verified** locally (see `DISASTER_RECOVERY.md` drill log) |
| **SigV4 upload against a live S3-compatible store** | ✅ **verified 2026-09-26** against a **local MinIO server** (`minio/minio`, `RELEASE.2025-09-07`), *not* production S3. Both auth modes exercised end-to-end: the presigned-URL path (`SignedUploadUrlService`, used by Module 1 document uploads) and the header-signed path (backup script). Round-tripped a real `pg_dump` through the bucket and restored it with matching row counts. |
| Production S3/R2/OSS credentials and bucket | ❌ **never exercised** — no cloud credentials in this environment. The signing logic is proven; the production endpoint/region/credential values are not. |
| `securetravels.in` domain | ✅ **live 2026-09-26** (confirmed by fetch) — but see §7–§8: no droplet, Nginx, or cert is managed by this project yet |
| DigitalOcean droplet + firewall | ❌ **not created** |
| DNS record for the deployment target | ❌ **not configured** |
| Nginx + Let's Encrypt certificate | ❌ **not installed** |
| systemd timers on a real host | ❌ **not installed** |
| GitHub branch protection on `main` | ❌ **manual web-UI step, still open** |
| Module 3 (season generator, capacity colours, scarcity + min-group alerts) | ✅ **verified live 2026-09-26** against the running dev backend — see the evidence block below |

Do not report the ❌ rows as done.

> **Two real bugs were found and fixed by this live test** (2026-09-26), both of
> the same family — *every header sent must appear in `SignedHeaders`*, and the
> credential must actually be transmitted:
>
> 1. `SignedUploadUrlService` signed `host;x-amz-content-sha256`, so **every**
>    browser upload was rejected with `AccessDenied - headers present in the
>    request which were not signed`. A client can only satisfy that by manually
>    sending `x-amz-content-sha256`, which no browser `fetch` will do. Presigned
>    PUTs must sign **`host` only**; fixed, with a regression test that asserts
>    the wire format directly.
> 2. `backup-postgres.sh` computed a valid signature but **never sent the
>    `Authorization` header**, so the store saw an anonymous PUT and returned a
>    bare `AccessDenied` (not a signature error — easy to misdiagnose). Fixed.
>
> Lesson recorded deliberately: in both cases the existing test suite was
> **green**. `SignedUploadUrlTest` calls `issue()` and `verify()`, which share
> the same wrong constant, so the defect cancelled out internally and no unit
> test could see it. A signature that matches a published reference vector proves
> the *math*; only a live request proves the *request*.

### Module 3 live verification (2026-09-26)

Exercised over real HTTP against the dev backend on `:8080` (not mocks), with
PostgreSQL 16.4 and MinIO running locally. Backend suite: **215 tests green**.

| Behaviour | Live result |
|---|---|
| Monthly recurrence `2028-01-31 → 2028-04-30` | created 4 departures: `2028-01-31, 2028-02-29, 2028-03-31, 2028-04-30` — month-end dates stay anchored through a leap February instead of clamping |
| Re-running an identical season | `created=0 skipped=4` — idempotent, no `409` |
| Fresh batches | `fillPercent=0`, `capacityColor=GREEN` |
| 7 of 10 seats | `fill=70% GREEN available=3`, **no** alert |
| 9 of 10 seats | `fill=90% AMBER available=1`, exactly **1** OPS notification: `Scarcity: … 2028-01-31 at 90%`; **no** ADMIN copy |
| 10 of 10 seats | `fill=100% RED`, batch auto-`CLOSED`; still exactly **1** alert — the `capacity_alerted_at` latch held |
| 11th seat on a full batch | clean `400 BAD_REQUEST` "Only 0 seats are available on this batch (requested 1)" — never a `500` |
| Guides | `POST /api/vendors {category:"GUIDE"}` + `GET /api/vendors?category=GUIDE` — confirms the frontend's move off the removed `/api/guides` |
| Min-group sweep, new thin departure (0/30, 12 days out) | `CapacityAlertSweep` fired on schedule 120 s after boot, set `min_group_alerted_at`, and notified ADMIN **and** OPS exactly once: `At risk: … leaves in 12 day(s) with 0 of 30 seats booked (0%), below the viable group of 6 travellers / 50% fill` |
| Min-group sweep, second run over the same batch | no duplicate notification for either recipient — the latch is a persisted column, so it survives restarts |

Both latches were confirmed as **database columns** (`capacity_alerted_at`,
`min_group_alerted_at`), not in-process flags, so a restart or a second instance
cannot re-notify.

> Operational note found while doing this: `/actuator/health` and every other
> endpoint stopped responding on one launch. It was **not** an application fault
> — that JVM had been started through a shell pipeline whose parent was killed,
> leaving the process alive but wedged before it finished booting. Launched
> normally the same jar reports `Started SecureTravelsApplication in 4.754
> seconds`. Worth knowing so a future hung process is not mistaken for a
> regression.

---

## 1. Prerequisites (human, with account access)

- [ ] DigitalOcean account, billing enabled, and an API token with `droplet:create`.
- [ ] Registered/available domain (`securetravels.in` in the plan) and access to
      its DNS zone.
- [ ] An S3-compatible bucket for backups, in a **separate account** from the
      documents bucket (per `DISASTER_RECOVERY.md` §2). Create a dedicated
      access key scoped to `PutObject`/`GetObject`/`ListBucket` on that bucket
      **only** — not the MinIO `minioadmin` default, and not the documents key.
- [ ] A SSH keypair; add the public key to the droplet at creation.

---

## 2. Create the droplet

Target: **1 vCPU / 2 GB RAM / 25 GB SSD Ubuntu 24.04 LTS**, region closest to
the users (start with `nyc`/`ams`).

```bash
# DO NOT RUN until the token + SSH key from §1 exist.
export DO_TOKEN="<token>"

curl -sS -X POST "https://api.digitalocean.com/v2/droplets" \
  -H "Authorization: Bearer $DO_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "securetravels-crm-prod-1",
    "region": "nyc3",
    "size": "s-1vcpu-2gb",
    "image": "ubuntu-24-04-x64",
    "ssh_keys": [ "<ssh-key-id>" ],
    "backups": true,
    "tags": ["securetravels", "prod"]
  }'
```

> `backups: true` is DigitalOcean's *host-level* snapshot. It is **not** a
> substitute for the logical backups in §6 — different mechanism, different RPO.

Record the assigned IP. Everything below uses `VPS_IP`.

### Firewall

DigitalOcean firewalls **deny by default** and are attached per droplet.

| Direction | Port | Source | Why |
|---|---|---|---|
| in | 22 | your IP only | key-only SSH; never open 22 to `0.0.0.0/0` |
| in | 80 | `0.0.0.0/0` | ACME HTTP-01 + redirect to HTTPS |
| in | 443 | `0.0.0.0/0` | TLS |
| in | 5432 | — | **never add.** Postgres is not exposed; see §4 |
| out | all | — | apt, image pulls, bucket uploads |

---

## 3. Base host setup

```bash
ssh root@$VPS_IP
adduser --disabled-password --gecos "" securetravels
usermod -aG sudo securetravels
install -d -m 700 -o securetravels -g securetravels /home/securetravels/.ssh
cp ~/.ssh/authorized_keys /home/securetravels/.ssh/
chown -R securetravels:securetravels /home/securetravels/.ssh
chmod 600 /home/securetravels/.ssh/authorized_keys

apt update && apt -y upgrade
apt -y install unattended-upgrades postgresql-client-16 nginx certbot \
                python3-certbot-nginx rsync curl ca-certificates
timedatectl set-timezone UTC
```

> `postgresql-client-16` supplies `pg_dump`/`pg_restore`. The DB itself runs in
> the compose stack; this package is only the client.

---

## 4. Deploy the application

```bash
sudo -u securetravels git clone <repo-url> /opt/securetravels
cd /opt/securetravels
cp .env.example .env   # then fill in — see §5
docker compose build --pull
docker compose up -d
docker compose ps
```

Postgres is published to `127.0.0.1:5432` in `docker-compose.yml` and is
**not** reachable from outside the host. Confirm:

```bash
ss -lntp | grep 5432     # expect 127.0.0.1:5432, NOT 0.0.0.0:5432
```

If it shows `0.0.0.0:5432`, **stop and fix the compose port mapping before
going further** — that is an internet-exposed database.

---

## 5. Secrets

`.env` on the VPS, `chmod 600`, owned by `securetravels`, **never committed**.

```bash
sudo install -m 600 -o securetravels -g securetravels /dev/null /opt/securetravels/.env
sudo -u securetravels editor /opt/securetravels/.env
```

| Variable | Notes |
|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | mandatory; disables demo data + SQL logging |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | app DB connection |
| `JWT_SECRET` | ≥ 32 random bytes: `openssl rand -base64 48` |
| `WEBHOOK_SECRET` | ≥ 32 random bytes, generated independently |
| `CORS_ALLOWED_ORIGINS` | `https://securetravels.in` — no trailing slash, no `*` |
| `STORAGE_ENDPOINT` / `STORAGE_REGION` / `STORAGE_BUCKET` | documents bucket |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | documents bucket credentials |
| `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` | **first boot only**, then `unset`/remove — see `DEPLOYMENT.md` §5 |

Confirm demo data is off and nothing leaks:

```bash
docker compose logs backend | grep -i '\[demo-data\]'   # must be empty
docker compose logs backend | grep -i 'bootstrap admin'  # one line on first boot only
```

---

## 6. Backups (the automation that exists today)

`scripts/backup-postgres.sh` is committed and tested. Install the timer:

```bash
sudo install -m 644 -o root -g root /opt/securetravels/scripts/systemd/securetravels-backup.service  /etc/systemd/system/
sudo install -m 644 -o root -g root /opt/securetravels/scripts/systemd/securetravels-backup.timer   /etc/systemd/system/

sudo install -d -m 700 /etc/securetravels
sudo install -m 600 /dev/null /etc/securetravels/backup.env
sudo -u securetravels editor /etc/securetravels/backup.env   # STORAGE_* + PG* values

sudo systemctl daemon-reload
sudo systemctl enable --now securetravels-backup.timer
systemctl list-timers securetravels-backup.timer             # expect the next 02:17 run
```

First run — **do this by hand and read the output**:

```bash
sudo systemctl start securetravels-backup.service
sudo journalctl -u securetravels-backup -n 50 --no-pager
```

Success looks like: `dump size: … bytes` → `integrity check OK (N archive
entries)` → `upload OK (HTTP 200)`.

**Reading a failure.** Both of these were real bugs found on 2026-09-26, so
know the difference between them:

| Symptom | Meaning |
|---|---|
| `403` + `SignatureDoesNotMatch` | the secret/region is wrong, or a header was altered after signing |
| `403` + bare `Access Denied` (no "Signature") | the request reached the store **unsigned** — i.e. the `Authorization` header was missing. Not a credential problem. |
| `400` + `headers present in the request which were not signed` | a header was sent that is not in `SignedHeaders` (e.g. curl's automatic `Content-Type` from `--data-binary`) |

**Cron fallback** (if systemd timers are unavailable):

```cron
# /etc/cron.d/securetravels-backup — note the 6th field (user)
SHELL=/bin/bash
17 2 * * * securetravels BACKUP_SINK=s3 /opt/securetravels/scripts/backup-postgres.sh >> /var/log/securetravels-backup.log 2>&1
```

Confirm the bucket object actually exists and is restorable — see
`DISASTER_RECOVERY.md` §3 for the drill, and **run the drill once on the real
host before trusting the timer.**

---

## 7. Nginx + TLS

```bash
sudo tee /etc/nginx/sites-available/securetravels >/dev/null <<'EOF'
server {
    listen 80;
    server_name securetravels.in;
    location /.well-known/acme-challenge/ { root /var/www/html; }
    location / { return 301 https://$host$request_uri; }
}
EOF
sudo ln -s /etc/nginx/sites-available/securetravels /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx

sudo certbot --nginx -d securetravels.in --redirect --agree-tos -m ops@securetravels.in
sudo systemctl status certbot.timer     # renewal timer must be active
```

Then add the TLS server block proxying `/api` → `backend:8080` and `/` →
`frontend:3000`, and re-run the smoke test in §9.

> This is the step most likely to need adjustment: the `http` block above only
> handles ACME + redirect. The `https` proxy block is intentionally **not**
> pre-written here so that it is authored and reviewed against the real
> `docker-compose.yml` service names on the real host.

---

## 8. DNS

Add an `A` record for the apex and `www` pointing at `$VPS_IP`, TTL 300.
Propagation is not instant — `dig +short securetravels.in` in a loop until it
returns the IP. **Then run certbot** (§7); a certificate cannot be issued
before DNS resolves.

---

## 9. Smoke test after deploy

```bash
curl -fsS https://securetravels.in/api/health
# expect {"status":"UP",...}

# login, then prove the token works end to end
curl -fsS -X POST https://securetravels.in/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"<bootstrap admin>","password":"<password>"}'
curl -fsS https://securetravels.in/api/auth/me -H "Authorization: Bearer $TOKEN"
```

Then, in the browser: sign in, load the dashboard, and confirm no 401s in the
devtools network tab. (Recall `DEPLOYMENT.md` §8.2 — an `/api/api/...` double
prefix is exactly what a blanket 401 looks like.)

Also verify from outside: `curl -sS https://securetravels.in/api/health` from
your laptop, not the server.

---

## 10. Rollback

```bash
cd /opt/securetravels
git log --oneline -5
docker compose build --pull      # after: git checkout <previous-good-sha>
docker compose up -d
```

**Migrations are the irreversible part.** Flyway runs on start; there is no
`down`. If a migration shipped in the release you are rolling back, restore the
pre-deploy backup instead of rolling the code back
(`DISASTER_RECOVERY.md` §3). Always take a backup immediately before a deploy
that includes migrations:

```bash
sudo systemctl start securetravels-backup.service
```

---

## 11. Definition of done for provisioning

- [ ] Droplet created; firewall allows only 22 (key-only), 80, 443.
- [ ] `ss -lntp` shows Postgres bound to `127.0.0.1` only.
- [ ] `https://securetravels.in/api/health` returns UP from an external client.
- [ ] Login + one authenticated `GET` succeed through Nginx.
- [ ] `securetravels-backup.timer` active; one manual run logged `upload OK`.
- [ ] **A real restore drill performed on the VPS** and recorded in `DISASTER_RECOVERY.md`.
- [ ] `BOOTSTRAP_ADMIN_*` removed from `.env` after the first login.
- [ ] Branch protection enabled on `main` in the GitHub web UI (`PHASE_1_SIGNOFF.md` §5.1).
