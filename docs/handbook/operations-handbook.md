# Operations handbook: running restaurant-backend

| Version | Date | Audience | Sources |
|---|---|---|---|
| 1.0 | 2026-10-19 | Whoever runs production (today the owner, who is operations owner and on-call, R-07) | [deploy/README.md](../../deploy/README.md), [release-checklist.md](../sdlc/restaurant-ordering/release-checklist.md), [runbooks.md](../../deploy/observability/runbooks.md), architecture §8, §10, §13 |

This handbook gathers the day-to-day and emergency procedures in one place. The command-level detail stays in [deploy/README.md](../../deploy/README.md); this page says **when** to do what, **who** does it and **what good looks like**. Read §1 and §2 once, then use the rest as a reference.

## 1. The environments
| | Staging | Production |
|---|---|---|
| Purpose | System, load and security tests | Release 1, **test data only** (release-checklist §1) |
| Vercel project | `restaurant-staging`, Hobby account `liam-36c7` | `restaurant-production`, same account |
| Protected domain (use this) | `restaurant-staging-liam-36c7.vercel.app` | `restaurant-production-liam-36c7.vercel.app` |
| Public domain | `restaurant-staging-ecru.vercel.app`: **removed after every deploy** | `restaurant-production.vercel.app`: **kept public**, the address the client apps call (Q-29) |
| Access | Deployment Protection; clients send `x-vercel-protection-bypass` | The same |
| Database | Neon project `restaurant-staging`, Singapore, PostgreSQL 18 | Neon project `restaurant-production`, same settings |
| Rate-limit store | Upstash `restaurant-staging`, keys `staging:rl:…` | The same database, keys `production:rl:…` (CR-015) |
| Metrics | Grafana Cloud free stack, `deployment_environment="staging"` | Same stack, `deployment_environment="production"` |
| Settings | `deploy/staging/staging.env` (committed) + `.env.staging` (secret) | `deploy/production/production.env` + `.env.production` |
| WAF rule `auth-per-ip` | 300 per 60 s (raised for load runs) | 60 per 60 s |
| Live push | `LIVE_PUSH=off` | `LIVE_PUSH=off` |
| Current release | Redeployed from `main` | `release/1.0.1`, deployment `blmz4e4fk`; rollback target `hgpiz00l3` |

**Free-plan limits that shape every procedure:**
- An idle instance stops after 5 minutes; the next request waits 5–7 s for a cold start.
- The database compute scales to zero; the first query after a quiet spell waits for it to wake (R-11).
- Point-in-time restore reaches back **6 hours** (R-14).
- Cron jobs run **once a day** (±59 min).
- Runtime logs are kept **one hour**, with no drain (R-20).
- Vercel's own rollback isn't used: it moves only the public domain, and on staging, where that domain is removed, it failed (§5).

## 2. Who does what
The auto-mode safety check stops Claude from production deploys, production's database, writing secrets to Vercel and merging pull requests. So the owner runs those, and Claude prepares, checks and records.

| Task | Owner | Claude |
|---|---|---|
| Create accounts, projects, tokens in consoles (Vercel, Neon, Upstash, Grafana) | Yes | — |
| Generate secrets into `.env.<env>` (`generateSecrets`) | — | Yes (prints names only) |
| Send variables to Vercel (`push-env.sh`) | Yes | — |
| Migrations on production | Yes | Staging only |
| Deploy production (`deploy/release.sh production --env`) | Yes | — |
| Remove a public alias that answers `200` | Either | Yes (not blocked) |
| Smoke checks, log reviews, system suite on staging | — | Yes |
| Seed and system suite on production (`deploy/run-task.sh`) | Yes | — |
| Merge pull requests | Yes | — |
| Record decisions and incidents in STATUS.md | — | Yes |

**Rules that came from incidents:**
- **Name the project in every `vercel` command** (`--project restaurant-production`, `-p` for `logs`). The repository root is linked to one project at a time; on 2026-10-19 a bare `vercel deploy --prod` sent a staging deploy to production.
- **An owner's `!` command fits on one line of about 100 characters.** A longer line wraps when pasted and becomes two commands; on 2026-10-19 that left production's public domain up for about a minute. Longer steps are scripts (`deploy/release.sh`, `switch.sh`, `run-task.sh`).
- **After every production deploy, check each alias without the bypass header: none may answer `200`.**
- **Never print a secret.** Searches over `deploy/` exclude `.env*` (`grep --exclude='.env*'`). Secrets printed anywhere count as exposed and are rotated the same day.

## 3. Routine checks
No alert rule is loaded in Grafana yet (R-21), so nothing warns of trouble. Until the alerts are loaded (§9), these checks are the monitoring.

| When | Check | Where | Good looks like |
|---|---|---|---|
| Daily, if production is in use | Requests and errors | Grafana → *service overview*, environment production | No `5xx` other than an occasional cold-start timeout |
| Daily | Logs of the last hour | `vercel logs -p restaurant-production --json` (or `python3 -I deploy/staging/log-review.py --project restaurant-production --accounts deploy/production/.accounts.production --since 1h`) | No `ERROR` lines; the review reports no personal data or secret |
| The day after any deploy | Cron runs | Vercel → `restaurant-production` → Cron Jobs; Grafana job gauges `…_timestamp_seconds` | One run of each of the 5 jobs in the last day, each successful (R-23: never checked on production yet) |
| After any deploy | Public domain | `curl -s -o /dev/null -w '%{http_code}' https://restaurant-production.vercel.app/health` | Anything but `200` (it's public since Q-29; staging's must not answer `200`) |
| Weekly | Dependabot PRs | GitHub | Merged or triaged; High/Critical with a fix within 30 days, Critical within 7 |
| Weekly | Free-plan usage | Vercel usage, Neon usage, Upstash commands, Grafana active series | Below 80 % of each free allowance (Grafana: 10k active series, R-17) |
| Monthly | Secret ages | Key policy table (§7) | Nothing older than its lifetime |
| Quarterly | Restore drill | §6 | RTO ≤ 1 h, RPO ≤ 5 min |

## 4. Releasing
A release is the same build that passed staging, promoted to production. Migrations always come first and are expand-only, so the deployment still running keeps working.

1. **Release PR** (Claude), **merge** (owner): version in `build.gradle.kts`, CI green (`build`, `contract`, `image`).
2. **Staging** (Claude, with the owner's deploy): migrations on staging if the release adds one; `deploy/release.sh staging`; smoke check; acceptance subset (`./gradlew systemTest -PstagingUrl=…`).
3. **Tag** `release/<version>`. A published tag is never moved: a fix is a new version.
4. **Production migrations** (owner), only if the release adds one:
   ```
   (while IFS= read -r line; do case "$line" in ''|'#'*) ;; *) export "$line" ;; esac; done < deploy/production/.env.production
    ./gradlew --no-daemon migrate)
   ```
   Don't `source` the file: the shell would mangle the JSON values and the `&` in URLs.
5. **Deploy** (owner): `sh deploy/release.sh production --env`. It sends the variables, deploys, points the protected domain at the new deployment and checks that the public domain answers `200`. Note the deployment URL it prints.
6. **Smoke check** (Claude): `/health` answers `200` with the bypass header and is refused without it; `vercel alias ls` shows no alias answering `200` without it; the start-up lines are clean; the log review within the hour is clean.
7. **System suite** (owner): `sh deploy/run-task.sh production system`. On a fresh database, `sh deploy/run-task.sh production seed` first.
8. **Watch** for a day: §3's daily checks, plus one run of each cron job.
9. **Record** the release in STATUS.md (Claude).

**Zero-downtime:** Vercel switches traffic to the new deployment only when it's ready; requests in flight finish on the old one. On Hobby a release switches all at once (no rolling releases).

## 5. Rolling back
**When to roll back:**
- `/health` fails for 5 minutes;
- any `5xx` on order placement that isn't a cold start;
- a contract mismatch in the smoke run;
- a secret or personal data in the logs (then also rotate it, §7).

**How** (owner, ≤ 10 min target; the 2026-10-19 rehearsal switched in 1 s and `/health` answered in 2 s):
1. Find the previous good deployment ID: `vercel ls restaurant-production` (the ID is in `restaurant-production-<ID>-liam-36c7.vercel.app`).
2. `sh deploy/switch.sh production <deployment ID>`. It points the protected domain at that deployment and times it until `/health` answers `200`.
3. Claude confirms with `vercel alias ls` that the domain moved.

**Don't** use `vercel rollback` or the dashboard's Instant Rollback: they move only the public domain, not the protected one, and on staging, where the public domain is removed, they answered `500` or changed nothing (ST-19). `switch.sh` moves both on production. **Don't** restore the database for a code problem: a restore loses every write since the restore point. Database restores are for data problems only (§6). The alert runbooks in `deploy/observability/runbooks.md` still say "Instant Rollback"; use this procedure instead.

After a manual alias switch the protected domain no longer follows new deploys automatically; the next `release.sh` run moves it again, and `vercel alias ls` shows where it points.

## 6. Backup and restore
- **What exists:** Neon's point-in-time restore over the last 6 hours (free plan). No off-site copy (architecture §6's daily snapshot needs a paid plan and TBD-10). Rate-limit counters need no backup.
- **Targets:** RPO ≤ 5 min, RTO ≤ 1 h (NFR-REL-01). The drill (ST-08) measured RTO ≤ 13 min after D-3's fix.

**Restoring after a data problem** (bad migration, corrupted rows), adapted from the restore drill in deploy/README.md:
1. **Pick the restore point**: a time just before the damage, inside the last 6 hours. Note the time you start (for the RTO).
2. **Owner: create a branch** in the Neon console: project → Branches → New branch, parent `main`, *from a point in time* = the restore point. A branch keeps the roles and passwords; only the host changes. Copy the branch's **pooled** host (`ep-…-pooler.ap-southeast-1.aws.neon.tech`; not a secret).
3. **Owner: point the service at it**: `scripts/swap-db-host.sh <pooled host>` changes only the host in `DATABASE_URL` and `DATABASE_URL_PLATFORM`, keeping `sslmode=verify-full` (Neon's copied string says `sslmode=require`, which the service refuses). Then deploy (`sh deploy/release.sh production --env`).
4. **Check**: `/health` answers `200`; spot-check the damaged rows are back; run the system suite.
5. **Decide what happens to `main`**: the branch now holds the good data. Either keep running on the branch (and make it the project's default in Neon), or repair `main` and switch back with `scripts/swap-db-host.sh main` and a redeploy. Delete branches you no longer need (the free plan allows 10).
6. **Record** what was lost (writes between the restore point and the switch) in STATUS.md.

Damage noticed later than 6 hours can't be undone on the free plan (R-14).

## 7. Secrets
Every key and secret, where it comes from and how long it lives (deploy/README.md "Key policy"):

| Secret | Comes from | Lifetime | Rotation note |
|---|---|---|---|
| `DATABASE_URL`, `DATABASE_URL_PLATFORM` | `generateSecrets`; `migrate` sets the role passwords | 1 year | New URLs from `generateSecrets`, then `migrate` (resets both passwords), then deploy |
| `DATABASE_URL_MIGRATOR_DIRECT` | Neon console | 1 year | Reset in Neon; never sent to Vercel |
| `STAFF_JWT_KEYS`, `GUEST_JWT_KEYS` | `generateSecrets` | 1 year | Add a new `kid`; keep the old public key for one token lifetime (15 min staff, 6 h guests) |
| `TOTP_ENCRYPTION_KEYS` | `generateSecrets` | 1 year | New key ID encrypts new secrets; keep old keys while stored secrets use them |
| `QR_SIGNING_KEYS` | `generateSecrets` | Until exposed | A new key means reprinting every code (`rotate-all`) |
| `EMAIL_HMAC_KEY` | `generateSecrets` | Until exposed | A new key unlinks new failures from older ones |
| `CRON_SECRET` | `generateSecrets` | 1 year | Edit, push, deploy |
| `UPSTASH_REDIS_REST_TOKEN` (+ URL) | Upstash console | 1 year | Shared by both environments: send the new token to **both** projects and deploy both before revoking the old |
| `OTEL_EXPORTER_OTLP_HEADERS` (+ endpoint) | Grafana Cloud, one token per environment | 1 year | Header format `Authorization=Basic base64(<instance ID>:<token>)` |
| `VERCEL_AUTOMATION_BYPASS_SECRET` | Vercel → Deployment Protection | 1 year | Used by test clients only; never sent to the service |
| `ABLY_API_KEY` | Ably console | 1 year | Only with `LIVE_PUSH=ably` |

**To rotate:** edit the line in `deploy/production/.env.production` (mode `600`, git-ignored), then `sh deploy/release.sh production --env` (a deployment reads its variables when it starts). **Exposure means rotate today:** committed, pasted, printed, screenshotted, or held by someone who lost access.

## 8. Incident response
### 8.1 Triage
1. **Is it real?** Reproduce with `/health` and one API call through the protected domain with the bypass header. Note the `X-Request-ID`.
2. **Did it start with a deploy?** Compare the start with `vercel ls restaurant-production`. If yes, roll back first (§5), investigate after.
3. **Is a provider down?** Status pages: Vercel, Neon, Upstash, Grafana (and Ably if `LIVE_PUSH=ably`).
4. **Logs**, within the hour: `vercel logs -p restaurant-production --json`, matched by the line's own `request_id`. Logs hold IDs, never personal data.
5. **Severity** (test-plan §8): S1 = data leak, lost or wrong orders, the service down; S2 = a Must feature broken for everyone; S3 = degraded or a workaround exists; S4 = cosmetic.
6. **Record** the incident and every change made in STATUS.md's Decisions table, and write a short blameless review for S1 and S2.

### 8.2 Runbooks
| Situation | Signal | First steps |
|---|---|---|
| **SLO burn** (availability, order flow, guest-read latency) | 5xx rate, failed placements, slow guest reads | Deploy? Roll back. Database? See saturation. One route or one restaurant? See noisy tenant. ([runbooks.md#slo-burn](../../deploy/observability/runbooks.md#slo-burn)) |
| **Database saturation** | `DatabasePoolWaiting`; 5xx from the 5 s `statement_timeout`; `503 service_unavailable` | Grafana *database* dashboard: one instance or all? Neon console: CPU, connections, suspended compute. A deploy just before? Roll back |
| **Rate limits off** | `RateLimitFailingOpen`; Upstash errors in logs | Upstash status and command allowance (free plan); a rotated token? The WAF and the PostgreSQL sign-in lock still protect sign-in; tighten the WAF if abuse shows |
| **Noisy tenant or abuse** | One `restaurant_id` dominating requests; many `429`s | App limits (per session, staff, restaurant) and the WAF should hold it. Tighten the WAF rule for the duration; suspending the restaurant is the last resort (§8.3) |
| **QR leak** (codes photographed and abused) | Orders placed from outside the restaurant; complaints | Manager rotates the table's code (`POST /v1/staff/tables/{id}/qr-code/rotate`) or all codes (`…/qr-codes/rotate-all`) and reprints; staff cancel the bad orders |
| **QR signing key leaked** | The key appeared anywhere | Add a new key to `QR_SIGNING_KEYS`, deploy, then each manager runs `rotate-all` and reprints every code |
| **Secret exposed** | A secret printed, committed or pasted | Rotate it today (§7); for signing keys, keep the old public key only as long as its tokens live |
| **Reconcile mismatch / failed / lagging** | Daily sales differ from bills; the job failed or didn't run | The rebuild already fixed the figures; find `sales reconcile mismatch` in the logs and raise a defect. Lagging: check the cron ran |
| **Bad data written** | Wrong rows found | Fix with a forward change if small; restore (§6) if large and inside 6 hours |
| **Staff account compromised** | Unusual sign-ins in the audit log | Manager or admin deactivates the account (ends its sessions and voids its links), then reactivates and issues a reset link once the person is reached |
| **Admin lost their authenticator** | Admin can't finish sign-in | Another admin: `POST /v1/admin/admins/{id}/totp-reset`. The last admin: the operator command, §10 |
| **Outbox backlog / pub/sub outage** | Only with `LIVE_PUSH=ably` | [runbooks.md](../../deploy/observability/runbooks.md#outbox-backlog); clients fall back to polling |

### 8.3 Suspending a restaurant
For non-payment or abuse (FR-043). An admin calls `POST /v1/admin/restaurants/{restaurantId}/suspend` with `{"reason": "…"}`. Guests can no longer open sessions, order or call a waiter; staff can sign in, read the profile, and managers can read reports and the audit log; no data is deleted and no token is revoked. `…/reactivate` restores everything. Both are idempotent and audited (the reason stays out of the audit log, which managers read).

## 9. Before real customers
G6's conditions (STATUS gate log). Each is a project of its own, not a routine task:

| # | Condition | Done when |
|---|---|---|
| 1 | Legal review of residency and retention (TBD-10, TBD-4) | Regions confirmed or changed; retention values confirmed in the requirements by CR |
| 2 | Paid plans: Vercel Pro, Neon Launch or above, Grafana with log drain | ADR-0002's cold-start check passed on Pro (R-12); PITR ≥ 7 days (R-14); logs drained to Grafana Loki (R-20) |
| 3 | Full-size load at 2× peak (≈ 1,280 req/s) | ST-01, ST-02, ST-05, ST-06, ST-09 rerun at full size (R-16, R-09) |
| 4 | Alerts loaded and fired once | `deploy/observability/load.sh` run with `.env.grafana`; contact point set; ST-07's live half (`injectErrors`) fires and clears the burn alerts (R-21) |
| 5 | Cron runs checked | Each job's run seen on production (R-23) |
| 6 | Designed jobs built | The purge job, the audit export, staff anonymisation (technical reference §11; Q-28) |
| 7 | Client apps and real domains | `STAFF_APP_URL`, `ADMIN_APP_URL`, `CUSTOMER_APP_URL`, `ALLOWED_ORIGINS` set to the real apps (production's `AUTH_COOKIE_PATH` is `/api/v1/auth` since Q-29); the contract's `servers` filled in |
| 8 | A second person on call | Someone besides the owner can deploy, roll back and restore |

## 10. Operator tasks
### The first platform admin
Nobody can invite the first admin through the API. With the environment's variables exported (as for `migrate`, §4 step 4, from both the committed `.env` file and the secret one):
```
./gradlew --no-daemon operator --args="invite-admin lan@example.com Lan"
```
It prints a one-time invitation link once (never logged or stored). Opening it in the admin console sets a password and enrols TOTP. The same command with `reset-admin-totp <email>` resets the only active admin's authenticator, or issues a fresh link for an invited admin whose link expired. Both are audited with the system as the actor. On production the owner runs it (the safety check blocks Claude from production's database).

### Onboarding a restaurant
An admin calls `POST /v1/admin/restaurants` with an `Idempotency-Key`, the profile and the first manager's email. The answer holds a one-time invitation link (72 h) to pass on to the manager. A retry with the same key returns the same restaurant and a **fresh** link that replaces the first. See [user-guides.md §2](user-guides.md#2-platform-admin).

### Staff privacy requests (NFR-PRIV-01)
A staff member asks the operator (not their restaurant) for a copy or erasure of their data:
1. **Check who's asking**: the request comes from the account's email, or the restaurant's manager confirms it. Find the ID with `GET /v1/admin/restaurants/{restaurantId}/members`.
2. **Export**: `GET /v1/admin/staff-accounts/{staffId}/export` gives the personal fields, role history and audit entries where they are the actor; never a hash, secret or token. Send it through a channel the person confirmed. The export is audited.
3. **Erase**, if asked: `POST /v1/admin/staff-accounts/{staffId}/erase` deactivates, ends sessions and anonymises at once. If the answer says `"last_manager_removed": true`, invite a new manager.
4. **Record** the request and the answer date. The legal deadline waits for TBD-4 (working value: 72 h).

### Running a job by hand
`GET https://restaurant-production-liam-36c7.vercel.app/internal/jobs/<name>` with `Authorization: Bearer <CRON_SECRET>` and the bypass header. Jobs are idempotent and locked, so a manual run next to a scheduled one does no harm.

## 11. Setting up an environment from nothing
For a new environment (or rebuilding one), follow deploy/README.md's staging runbook steps 1–12; production differs as in release-checklist §4. In short:

1. **Owner**: Neon project in Singapore, PostgreSQL 18, role `migrator`, database `restaurant` owned by it.
2. **Claude**: `./gradlew generateSecrets --args="deploy/<env>/.env.<env> --db-host <pooled host> --db-name restaurant"`.
3. **Owner**: fill in the provider secrets: the migrator's direct URL, Upstash, Grafana OTLP, the bypass secret.
4. **Owner (production) / Claude (staging)**: `./gradlew migrate` with the file exported; it creates `app` and `app_platform` and applies V1 onwards.
5. **Owner**: `vercel link` (new project), then **at once** `vercel git disconnect --yes`, or every push builds it and a merge deploys it.
6. **Owner**: Deployment Protection on, a bypass secret, the WAF rule `auth-per-ip` (as JSON with `vercel firewall rules edit --json`, then `vercel firewall publish`; read it back with `rules inspect` and probe it).
7. **Owner**: `deploy/push-env.sh deploy/<env>/<env>.env deploy/<env>/.env.<env>`, then `sh deploy/release.sh <env>`.
8. **Claude**: smoke check; aliases; logs; the next day, the cron runs.
9. **Owner or Claude**: the first admin (§10), then the seed if it's a test environment (`seedStaging`).
