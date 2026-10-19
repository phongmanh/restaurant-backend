# Deploying the service

The service runs as a container on Vercel (architecture §8, ADR-0002), built from `Dockerfile.vercel` at the repository root. `vercel.json` sets the region (`sin1`) and the scheduled jobs. `.vercelignore` uploads only what the image needs.

| Environment | Vercel project | Database | Live updates, rate limits | Runbook |
|---|---|---|---|---|
| staging (free plans, Phase 5) | `restaurant-staging` on the Hobby test account `liam-36c7`; the production deployment, public alias removed (CR-011) | Neon free plan, its own project, Singapore | Ably free app `restaurant-staging`; Upstash free database, Singapore; metrics to Grafana Cloud's free plan (TBD-12) | Below |
| production | Not set up: it needs paid plans (TBD-11) and TBD-10 | | | |

## What the service needs
The non-secret settings for staging are in [`staging/staging.env`](staging/staging.env), and that file is committed. The secrets go in `staging/.env.staging`, which git ignores (`.env*`). Never commit that file, print it, or paste it into chat.

| Secret | Comes from |
|---|---|
| `DATABASE_URL`, `DATABASE_URL_PLATFORM` | `./gradlew generateSecrets`: the `app` and `app_platform` roles through Neon's pooler. `./gradlew migrate` creates both roles with these passwords |
| `STAFF_JWT_KEYS`, `GUEST_JWT_KEYS`, `QR_SIGNING_KEYS`, `TOTP_ENCRYPTION_KEYS`, `EMAIL_HMAC_KEY`, `CRON_SECRET` | `./gradlew generateSecrets` |
| `DATABASE_URL_MIGRATOR_DIRECT` | Neon console: the `migrator` role's **direct** connection string (pooling off). Used by `./gradlew migrate` on your machine; never sent to Vercel |
| `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN` | Upstash console, the database's REST API section |
| `ABLY_API_KEY` | Ably console, the app's API keys. Only with `LIVE_PUSH=ably`; release 1 runs `LIVE_PUSH=off` (CR-013, set in `staging.env`) and leaves it out |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_HEADERS` | Grafana Cloud, the stack's OpenTelemetry (OTLP) connection: the endpoint, and the `Authorization=Basic …` header made with a token that can write metrics (ADR-0009, TBD-12) |
| `VERCEL_AUTOMATION_BYPASS_SECRET` | Vercel project → Settings → Deployment Protection → Protection Bypass for Automation. Test clients send it as `x-vercel-protection-bypass`; never sent to the service |

The service refuses to start if a secret is missing and names every missing one in the error (core-and-auth §13).

## Staging runbook
Steps marked **you** are the owner's. The auto-mode safety check blocks Claude from production deploys and from writing secrets to Vercel, and the console steps need your accounts. Claude runs the rest and reads the results.

**Name the project in every `vercel` command** that acts on one: `--project restaurant-staging` (`-p` for `vercel logs`). The repository root is linked to one project at a time, `restaurant-production` since release step 4, and a command without the flag acts on that one. On 2026-10-19 this runbook's bare `vercel deploy --prod` sent a staging deploy to production, whose public domain then stayed up for about 7 minutes. `push-env.sh` takes the project from the files' `APP_ENV`. Scripted calls read `--json`: the CLI prints JSON when its output isn't a terminal.

1. **You: create the Neon project.** At console.neon.tech, create project `restaurant-staging` in **AWS Asia Pacific (Singapore)** with PostgreSQL **18**, the version the tests run on. Create a role `migrator` and a database `restaurant` owned by `migrator`. Don't create `app` or `app_platform`: `migrate` creates them.
2. **You: send Claude the pooled host.** Open the connection details for `migrator` on `restaurant` with **connection pooling on** and copy only the host, `ep-…-pooler.ap-southeast-1.aws.neon.tech`. The host is not a secret.
3. **Claude: generate the secrets.** Run from the repository root; it prints names only and never overwrites an existing file:
   ```
   ./gradlew generateSecrets --args="deploy/staging/.env.staging --db-host <pooled host> --db-name restaurant"
   ```
4. **You: fill in the provider secrets** in `deploy/staging/.env.staging`. The file has an empty line for each:
   - `DATABASE_URL_MIGRATOR_DIRECT`: the `migrator` connection string with **pooling off** (the host has no `-pooler`).
   - Upstash: create a free Redis database `restaurant-staging` in **AWS ap-southeast-1 (Singapore)**, then copy `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN`.
   - Ably, only with `LIVE_PUSH=ably` (release 1 runs `off`, CR-013, so skip this): create an app `restaurant-staging`. Add an API key with only the **publish** and **subscribe** capabilities, on the resource `*`, and copy it into `ABLY_API_KEY`. The app holds only this environment's channels, and the tokens the service mints narrow it further, to one `restaurant.<id>.staff` or `session.<id>` channel each.
   - Leave `VERCEL_AUTOMATION_BYPASS_SECRET` for step 7.
5. **Claude: run the migrations** against Neon. This creates the roles, applies V1 onwards, and is the first check of R-15 (whether Neon's owner role can grant `BYPASSRLS`):
   ```
   (while IFS= read -r line; do case "$line" in ''|'#'*) ;; *) export "$line" ;; esac; done < deploy/staging/.env.staging
    ./gradlew --no-daemon migrate)
   ```
   Don't load the file with `.` or `source`: the shell would strip the JSON's quotes and treat the `&` in the URLs as a command separator. `export "$line"` takes each value as it is. `--no-daemon` makes sure the Gradle process sees these variables.
6. **You: create and link the Vercel project** from the repository root, then disconnect it from GitHub at once. Otherwise every push builds it and a merge to `main` deploys it (CLAUDE.md):
   ```
   vercel link                    # new project "restaurant-staging" in liam-36c7
   vercel git disconnect --yes
   ```
7. **You: create the bypass secret.** Project → Settings → Deployment Protection: keep Vercel Authentication on (Standard Protection), create a **Protection Bypass for Automation** secret and paste it into `VERCEL_AUTOMATION_BYPASS_SECRET`.
8. **You: create the WAF rule.** Hobby allows one rate-limit rule per project (ADR-0008). Project → Firewall → New rule `auth-per-ip`: if the request path starts with `/v1/auth/`, rate-limit with a fixed window of 300 requests per 60 s per IP, action 429. That's core-and-auth §11's backstop (Q-20 raised staging's from 60), above every app limit (sign-in 30, refresh 120), so the app's 429, which carries `Retry-After`, answers first. The CLI's `rules add` left no rule in S-1, so create it in the dashboard. To change it later, a dashboard edit to 300 on 2026-10-18 never went live, and `vercel firewall rules edit auth-per-ip --rate-limit-requests 300` answered "No changes detected"; what worked is the whole rule as JSON, `vercel firewall rules edit auth-per-ip --json '<rule>' --yes`, then `vercel firewall diff` and `vercel firewall publish --yes`. Check the result with `vercel firewall rules inspect auth-per-ip`.
9. **You: send the environment variables** to the project's Production environment:
   ```
   deploy/push-env.sh deploy/staging/staging.env deploy/staging/.env.staging
   ```
10. **You: deploy, then remove the public alias.** `deploy/release.sh` does both in one run and checks that the public domain no longer answers 200 (add `--env` to send the variables first, step 9). Send Claude the deployment URL it prints, never the bypass secret:
    ```
    deploy/release.sh staging
    ```
    Production is the same with `production`. Don't paste the steps as one long line: it wraps, and on 2026-10-19 the break cut off the alias removal after production's deploy (CLAUDE.md).
    The project's production domain is `restaurant-staging-ecru.vercel.app`, not `restaurant-staging.vercel.app`: Vercel adds a suffix when the plain name is taken, so removing the plain name removes nothing. Standard Protection doesn't cover the production domain, and it stayed public from 2026-10-17 to 2026-10-18 for that reason. `vercel alias ls` shows each deploy's aliases; `restaurant-staging-liam-36c7.vercel.app` is protected and can stay. Check that it points at the new deployment: once it has been set by hand (`vercel alias set`, as ST-19's switch-back did), it stops following new deploys, and on 2026-10-18 it was still on the day-old build after a redeploy. Move it with `vercel alias set <new deployment URL> restaurant-staging-liam-36c7.vercel.app`.
11. **Claude: smoke-check the deployment.** `GET /health` with the bypass header answers 200, and the same request without it is refused by Deployment Protection. Then the same request without the header to each alias `vercel alias ls` lists for the deployment, and to `restaurant-staging-ecru.vercel.app`: none answers 200. Then read the Vercel logs for the start-up line, and look for any secret or personal data in them.
12. **Claude: check the scheduled jobs** the day after the deploy. The Cron Jobs tab shows one run of each, and on Hobby each runs once a day at most. A run against a deployment without its alias is recorded, whatever the result.

### Metrics backend (ADR-0009, TBD-12)
1. **You:** at grafana.com, sign up for the free plan and create a stack, choosing the **Singapore** region if it's offered (TBD-10 may care where data lives). Otherwise pick the nearest.
2. **You:** in the stack, open **Connections → OpenTelemetry (OTLP)** and create a token. The page then shows the endpoint (`https://otlp-gateway-….grafana.net/otlp`) and the header, `Authorization=Basic …`. Paste them after `OTEL_EXPORTER_OTLP_ENDPOINT=` and `OTEL_EXPORTER_OTLP_HEADERS=` in `deploy/staging/.env.staging`, adding the two lines if the file doesn't have them. The header holds the token: never paste it into chat.
3. **You:** run steps 9 and 10 again (send the variables, deploy, remove the alias). Without both settings, a staging deployment refuses to start and names them.
4. **Claude:** after some traffic, check in Grafana's **Explore** that `ktor_http_server_requests_milliseconds_count{job="restaurant-backend", deployment_environment="staging"}` arrives (Grafana promotes the resource attribute `deployment.environment` to that label; the alerts and dashboards read `production` only, release-checklist P-3). Micrometer sends timers in milliseconds, and Grafana adds the unit to the name. If Grafana names it differently, the alerts would find no data and never fire: fix `MetricNamesTest`'s naming to match what Explore shows, then the rules and dashboards it then rejects.

### Alerts and dashboards (architecture §13, ST-07)
The SLOs, burn-rate alerts and dashboards are code in [`observability/`](observability): `rules.yaml` (Prometheus rule format), `rules-test.yaml` (promtool's tests, run by `AlertRulesTest` in the build), `dashboards/*.json` and [`runbooks.md`](observability/runbooks.md), which each alert links to. `./gradlew build` also checks that every series they read is one the service pushes (`MetricNamesTest`).

The free plan keeps metrics for 14 days and up to 10k active series. The alerts look back 6 hours at most, so they work on it, but the SLOs' 28-day figures can't be computed: the dashboards show each SLO over the range you pick.

1. **You:** create two tokens and put them, with three addresses, in `deploy/observability/.env.grafana` (git-ignored, like the other env files; never paste a token into chat):
   - `GRAFANA_URL`: the stack's address, `https://<stack>.grafana.net`.
   - `GRAFANA_SA_TOKEN`: in the stack, **Administration → Users and access → Service accounts**, add `restaurant-backend-load` with the **Editor** role, then add a token.
   - `GRAFANA_PROM_URL` and `GRAFANA_PROM_USER`: at grafana.com, **My Account → your stack → Prometheus → Details**: the URL without any path (`https://prometheus-….grafana.net`) and the user (a number).
   - `GRAFANA_RULES_TOKEN`: on the same page's **Access Policies**, create a policy for the stack with the scopes `rules:read` and `rules:write`, then a token.
2. **You:** with Docker running, from the repository root:
   ```
   deploy/observability/load.sh deploy/observability/.env.grafana
   ```
   It prints `rules: synced namespace restaurant-backend` and `HTTP 200` for each of the three dashboards. Run it again after any change to `rules.yaml` or `dashboards/`; it updates in place.
3. **You:** so firing alerts reach you, open **Alerting → Contact points**, choose the Grafana Cloud Alertmanager (not the Grafana one) in the selector at the top, and set its default contact point to your email. Without this, alerts still show as firing on the **Alert rules** page, which is enough for ST-07.
4. **Claude: ST-07's live run.** With the environment exported from `.env.staging` as in step 5:
   ```
   ./gradlew --no-daemon injectErrors --args="--minutes 20 --error-ratio 0.05"
   ```
   It pushes 20 minutes of guest-read counts, 5% of them 5xx, through the service's own OTLP registry, tagged `client="st-07"`. It sends no request to the service. `AvailabilityBurnFast` should fire within about 5 minutes and `AvailabilityBurnSlow` after about 17. Once the run ends, the fast alert clears within about 5 minutes (its short window empties) and the slow one within about 30. A second run with `--error-ratio 0.0005` should fire nothing. The injected series spend staging's error budget, which is what they're for.

### Staging data (test-plan §6)
Claude runs both, with the environment exported as in step 5 from **both** files (the seed needs the service's settings too):
```
(for f in deploy/staging/staging.env deploy/staging/.env.staging; do
   while IFS= read -r line; do case "$line" in ''|'#'*) ;; *) export "$line" ;; esac; done < "$f"; done
 ./gradlew --no-daemon seedStaging --args="<deployment URL>")
```
For production the owner runs it (the auto-mode check blocks Claude from production's database): `sh deploy/run-task.sh production seed`, and `… production system` for the system suite. The script exports both files and uses the protected domain.
- **`seedStaging`** (about 160 requests) makes two admins: the first, with TOTP enrolled, comes from the operator's service call against the database; the second is invited and not enrolled. It then makes restaurants A and B through the API. Each has a manager, two waiters, a kitchen user, a deactivated waiter, a waiter locked by ten failed sign-ins (the lock lifts after 15 minutes), a menu with hidden, sold-out and option items, and 25 tables. The accounts go to `deploy/staging/.accounts.staging`, which is git-ignored and owner-only, like the env file. A second run skips what that file already lists.
- **`generateHistory`** (about 1,100 requests), the same way, after the seed. Restaurant L (1,000 items, 25 tables) and 49 smaller restaurants are onboarded through the API. The history rows are then written through the service's own transactions: L's last 30 days as real bills, with their daily rows rebuilt by the reconcile's own code, and a year of daily rows for every restaurant. The run prints its size estimate and stops above 300 MB.

### System tests (test-plan §5, §9 step 5)
Claude runs them after the seed, with the environment exported from both files as for the seed:
```
./gradlew --no-daemon systemTest -PstagingUrl=<deployment URL>
```
- Every AT scenario of test-plan §5 runs through the API (no client app exists yet), in restaurants A and B, as the seeded accounts. Each response is checked against the contract with CI's own `ContractCheck`, so a response that breaks it fails the scenario that got it. Live updates go through real Ably subscriptions on the channels the service's tokens grant.
- A run sends about 650 requests and stops at 2,000 (test-plan §9's budget guard); it prints its total at the end. The first run sent 535 in about 2 minutes, a minute of it AT-005 keeping a phone offline.
- Each scenario seats its guests at tables free when it starts and closes their sessions when it ends. A run leaves closed bills in A, accounts named `….<run>@staging.example` (deactivated) and one restaurant `C <run>` from AT-040. Run one class with `--tests '*IsolationTest'`.
- The results are JUnit's, in `build/test-results/systemTest/`; lines starting `[AT-…]` or `[ST-…]` in the output are the measurements (live-update delays, call counts).
- Beside the scenarios, test-plan §4's `Free` rows: `AbuseLimitsTest` (ST-14) spends one minute's sign-in allowance and the WAF's, so for about two minutes the other tests' sign-ins wait (the client waits out a `429`). `AuditTrailTest` (ST-13) also connects to Neon with the env file's settings, only to read the chains and to try statements that match no row in a transaction it rolls back. `RequestIdTest` (ST-18) writes the request IDs it got to `build/system-test/st18-request-ids.txt`.
- **Claude: review the logs within the hour** (Hobby keeps them an hour), a few minutes after the run: Vercel's logs take a minute or two to arrive, and a review run straight away found neither request ID. Run `python3 -I deploy/staging/log-review.py --since 1h`. It finds ST-18's request IDs in Vercel's logs and looks through every line the service logged for email addresses, tokens, IPs, phone numbers and the seeded accounts' emails, passwords and secrets (ST-15). It prints counts and the logger of each hit, never the text, and exits 0 when the IDs are found and nothing is hit.

### Staff data requests (restaurants §10, NFR-PRIV-01)
A staff member asks the operator, not their restaurant, for a copy of their personal data or for its erasure. A platform admin handles it through the API (no admin screen exists yet):
1. **Check who's asking**: the request comes from the email on the account, or the restaurant's manager confirms it. Find the account's ID with `GET /v1/admin/restaurants/{restaurantId}/members`.
2. **Export**: `GET /v1/admin/staff-accounts/{staffId}/export` answers the account's personal fields, its role history and the audit entries where it's the actor; never a hash, secret or token. Send that JSON to the person by a channel they confirmed. The export itself is audited (`staff.data_exported`).
3. **Erase**, if asked: `POST /v1/admin/staff-accounts/{staffId}/erase` deactivates the account, ends its sessions and anonymises it at once; the audit log keeps only the pseudonymous ID. If the answer says `"last_manager_removed": true`, the restaurant has no active manager left: invite one (`POST /v1/admin/restaurants/{restaurantId}/members`).
4. **Record** the request and the date it was answered. The legal deadline waits for TBD-4 and the legal review.

`StaffDataRequestTest` (ST-15) does steps 2 and 3 on staging for a member it invites.

### Restore drill (ST-08, NFR-REL-01)
Restores staging's database to a point about an hour back, inside the free plan's 6-hour window, and times how long until a deployment answers on it. The target is RPO ≤ 5 minutes and RTO ≤ 1 hour. With the environment exported as for the system tests:
1. **Claude: mark the database** twice, five minutes apart, with `./gradlew --no-daemon systemTest -PstagingUrl=<deployment URL> --tests '*RestoreDrillTest' -PrestoreDrill=mark`. Each run adds a table to A named `st08-<yyMMddHHmmss>` (UTC) and prints it. The **restore point** is a time between the two marks, so the first should come back and the second shouldn't.
2. **Wait an hour**, so the point is an hour back, as the test plan asks.
3. **You: note the start time, then make the branch.** Neon console → the `restaurant-staging` project → **Branches → New branch**: parent `main`, **from a point in time**, the restore point, name `restore-st08`. A branch keeps the roles and their passwords, so only the host changes: copy the new branch's **pooled** host (`ep-…-pooler.ap-southeast-1.aws.neon.tech`). It isn't a secret; send it to Claude.
4. **You: point staging at the branch.** `scripts/swap-db-host.sh <branch's pooled host>` changes only the host of `DATABASE_URL` and `DATABASE_URL_PLATFORM` (Production), taking the rest from `.env.staging`, so `sslmode=verify-full` stays; Neon's copied string says `sslmode=require`, which the service refuses. Then deploy as in step 10 and remove the alias.
5. **Claude: check it**, and note the time as the end of the restore: `/health` answers 200, then `./gradlew --no-daemon systemTest -PstagingUrl=<new deployment URL> --tests '*RestoreDrillTest' -PrestoreDrill=check -PrestorePoint=<the point, RFC 3339>`. It passes when the first mark is there and the second isn't. RTO is from step 3's start to this check. RPO is the gap between the point you chose and the newest write that came back, at most the minutes between the marks.
6. **You: switch back.** `scripts/swap-db-host.sh main` puts the two values back to `main`'s host; redeploy as in step 10. **Claude:** `-PrestoreDrill=clean` against that deployment, back on `main`, removes the marks there; the branch's copies go with the branch. **You:** delete the `restore-st08` branch in the Neon console (the free plan allows 10 branches).

### ZAP scan (ST-11, NFR-SEC-01)
ZAP, a free scanner, attacks the deployment the way an attacker's tools would, using the contract as its map. Claude runs it in Docker (the pinned image `ghcr.io/zaproxy/zaproxy:2.17.0`), with the environment exported as for the system tests, in two runs, because the five profiles together are above a run's 10,000 requests (test-plan §9):
```
./gradlew --no-daemon zapScan --args="<deployment URL> --profiles guest,staff,floor"
./gradlew --no-daemon zapScan --args="<deployment URL> --profiles admin,anonymous"
```
- Each run onboards its own restaurant, `Nhà hàng Z <run> (ZAP)`, with a waiter, a menu item, a table, a guest, an order and a waiter call (about 15 requests), so the scan writes there, never in A, B or L. Their IDs become the contract's path examples, so the attacks reach real rows.
- **Profiles**, each with a fresh token and its own cap: `guest` (`/v1/guest`, at most 1,200 requests), `staff` (restaurant, members, audit, menu: 4,300), `floor` (tables, sessions, orders, bills, events, reports: 4,300), `admin` (`/v1/admin`: 4,500), `anonymous` (every operation once without a token, then attacks on the 8 that take none: 1,500). A token profile stops after 12 minutes, inside its access token's 15.
- **Budget guard:** the run prints its caps and refuses above 10,000; inside ZAP, `deploy/zap/budget.js` counts every request and stops the scan at the profile's cap. The first two runs (2026-10-18) sent 13,411 requests in about 21 minutes; the staff and admin profiles reached their caps.
- **Clean-up:** the admin profile's writes can create admins and restaurants whose invitation only ZAP saw, so after it the run deactivates every admin and suspends every restaurant created since the run started (Z included; the scan itself often suspends Z first). The first run's Z stays, like AT-040's restaurant C.
- **Secrets** go to the container as environment variables and are never written to a file. The reports are in `build/zap/<run>/` (git-ignored): `summary.md`, and per profile `zap-<profile>.html`. They can quote response bodies, so they stay there.
- **Reading the result:** the run exits 1 when any alert is High. Each alert is triaged in [asvs-checklist.md](../docs/sdlc/restaurant-ordering/asvs-checklist.md) §2.

### Load runs (test-plan §3, §9 step 6b)
The scaled runs of ST-01, ST-02, ST-04, ST-06, ST-09 and ST-19. Claude runs them with the environment exported as for the system tests, one profile a run, against the project's domain `https://restaurant-staging-liam-36c7.vercel.app` (protected; it follows each production deploy and rollback):
```
./gradlew --no-daemon loadTest --args="<URL> --profile smoke|cold|deploy|live|report|noisy [--plan-only]"
```
- **`smoke`, `cold`, `deploy`** run architecture §9's request mix in k6's container (the pinned image `grafana/k6:1.3.0`, script `deploy/load/mix.js`) as restaurant L's manager and one guest per table (25). `smoke` is 10 requests a second for 10 minutes (about 6,300 requests with the setup). `cold` seats the guests, waits 30 minutes without a request, times the first sign-in, then ramps 0 → 10 a second in a minute and holds 9 minutes (about 6,000). `deploy` is the smoke load for 12 minutes (about 7,500) while you deploy and roll back.
- **What a mix run checks:** the warm requests' p95 and p99 for reads and writes against NFR-PERF-01/02, requests over 2 s counted apart by minute (a request that waited for an instance to start; S-1 measured 5–7 s), unexpected answers (target < 0.1%), and the orders: one in five is sent twice with its `Idempotency-Key` and must answer the same order, and afterwards every order k6 saw acknowledged must be in the database and every order in the sessions one k6 saw acknowledged (ST-09: 0 lost, 0 duplicates). Then the run closes the 25 sessions, which cancels what wasn't served.
- **`deploy`, with you (ST-09, ST-19).** When the run prints `k6 started`, run `! vercel deploy --prod --project restaurant-staging` in Claude Code; it builds for about 4 minutes before the switch. The run watches which deployment the domain serves (`vercel inspect`, every 10 s) and prints each switch. A promotion gives `restaurant-staging-ecru.vercel.app` back to the new deployment (both times on 2026-10-18), so after each switch the run checks it without the bypass header and removes its alias if it answers anything but `404` (step 10).
- **Switching back (ST-19)** is a second run with `--minutes 6`, during which you point the protected domain at the earlier deployment, timed: `! date -u +%T; vercel alias set <earlier deployment URL> restaurant-staging-liam-36c7.vercel.app; date -u +%T`. Vercel's own rollback doesn't work here: it moves the production domain, which staging removes. On 2026-10-18 `vercel rollback` answered `500 An unexpected internal error occurred` twice, and the dashboard's Instant Rollback changed nothing (`vercel rollback status`: none in progress). The dashboard names deployments by ID (`5qcWW3X2W…`), not by URL. After a re-point, the project's newest production deployment no longer carries the protected domain until the next deploy.
- **`live` (ST-03, ST-10; step 6c, CR-013):** the mix at 9 a second for 14 minutes while 55 screens on L poll the event log as release 1's apps do: two phones a table every 60 s, five staff screens every 15 s, each ±20 %. With the screens' 1.2 a second, the service gets the smoke load's 10. It runs 14 minutes because k6's staff token lasts 15 and k6 can't renew it; the screens sign in again themselves, and poll 90 s more after the load. Half the screens pause once for 1–3 minutes, then catch up. It reports each order write's delay to every screen that sees it against NFR-PERF-03's release-1 target (k6 prints `WROTE` lines with its clock; the run prints how far that clock is from the driver's), then reads `domain_events` as `app` in a read-only transaction: every screen must hold exactly the database's events for its view, and with `LIVE_PUSH=off` the run must add no outbox row. About 9,100 requests; each screen's events are in `build/load/<run>/screens.txt`.
- **`report` (ST-04):** 60 requests for L's 31-day report, one every 2.5 s (the limit is 30 a minute per staff member); the first is timed apart. Target p95 ≤ 3 s.
- **`noisy` (ST-06):** from the start of one minute, A's 25 guests place 10 orders each and its manager and two waiters 30 each: 340 against A's limit of 300 a minute, so A gets `429 rate_limited`. B's guest places 5 orders over the same 30 s, and each must succeed. About 500 requests; the sessions close afterwards.
- **Secrets** reach k6 only as environment variables of the `docker` process; k6 prints order IDs, statuses and request IDs, never a token or a body. The summary is in `build/load/<run>/summary.json` (git-ignored).

### Live push off (CR-013)
Release 1 has no live push: `staging.env` says `LIVE_PUSH=off`, the service writes no outbox rows, and the two `realtime-token` routes answer `503`. To move a staging that ran with Ably to `off`, **you**:
1. delete the `ABLY_API_KEY` line from `.env.staging` (otherwise step 9 sends it again), then remove it from Vercel: `vercel env rm ABLY_API_KEY production --project restaurant-staging --yes`;
2. run step 9 (it sends `LIVE_PUSH=off` from `staging.env`), then step 10;
3. delete the Ably app `restaurant-staging` in the Ably console, or revoke its key (the S-3 spike's app `restaurant-s3-spike` is still open too).

The system suite reads `LIVE_PUSH` from `staging.env`: with `off`, AT-005 and AT-011 poll the catch-up endpoints on release 1's timer (staff 15 s, guests 60 s) and allow NFR-PERF-03's release-1 p99 (20 s, 75 s), so those two take a minute or two longer.

### Redeploying
Repeat step 10. Migrations come first whenever the release adds one (step 5): they are expand-only within a release, so the deployment still running keeps working (architecture §8).

### Rotating a secret
Edit the line in `.env.staging`, run step 9, then step 10: a deployment reads its variables when it starts. For a signing key, keep the old public key in the set until the tokens it signed have expired (core-and-auth §8).

### Key policy (ASVS V11.1.1, F-6)
Every key and secret is listed in [asvs-checklist.md §5.1](../docs/sdlc/restaurant-ordering/asvs-checklist.md). The rules for all of them:

- **Who holds them:** the owner only. They live in the Vercel project's environment variables and in each environment's `.env.<environment>` on the owner's machine (mode `600`, ignored by git). Never in git, CI, chat, tickets or logs. Staging and production never share a key or a password, with one exception on the free plans: the Upstash database and its token (`UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`), since the free plan allows one database per account. Each environment's keys carry its name (CR-015, R-22). Rotating that token means sending the new one to **both** projects and deploying both before revoking the old one.
- **Exposure means rotate now.** A secret is exposed when it has been committed, pasted or printed anywhere another person or tool can read it (a chat, a shared terminal, a screenshot), or when someone who could read it loses their access. Rotate it the same day, with "Rotating a secret" above.
- **How long each lives:**

| Secret | Lifetime | Note |
|---|---|---|
| `STAFF_JWT_KEYS`, `GUEST_JWT_KEYS` | 1 year | A new `kid` signs; keep the old public key in the set for one token lifetime |
| `TOTP_ENCRYPTION_KEYS` | 1 year | A new key ID encrypts new secrets; keep the old key in the set while any stored secret uses it |
| `QR_SIGNING_KEYS` | Until exposed | A new key means reprinting every table's code (`rotate-all`, tables §2) |
| `EMAIL_HMAC_KEY` | Until exposed | A new key stops linking new sign-in failures to older ones |
| `CRON_SECRET`, database passwords | 1 year | `app` and `app_platform`: new URLs from `generateSecrets`, then `migrate` (it resets both passwords), then step 9 and step 10. `migrator`: reset in the Neon console |
| `ABLY_API_KEY`, `UPSTASH_REDIS_REST_TOKEN`, `OTEL_EXPORTER_OTLP_HEADERS`, `VERCEL_AUTOMATION_BYPASS_SECRET` | 1 year | Issue a new one in the provider's console, deploy, then revoke the old one |

### Vulnerable dependencies (ASVS V15.1.1, V15.2.1, F-3)
CI lists what ships as two CycloneDX SBOMs, each kept 90 days as an artifact: `sbom-libraries`, the runtime classpath from Gradle (`./gradlew cyclonedxBom`), and `sbom-image`, the image's JRE and Ubuntu packages (Syft). Two, because a fat jar keeps no trace of the Gradle-published libraries (Ktor, Exposed, Kotlin), so an image scan alone misses them. Grype then fails the build on a **High or Critical vulnerability that has a fixed version**. One without a fix doesn't fail the build: it is tracked until a fix ships. A library's fix usually comes through a BOM or a version in `gradle/libs.versions.toml` (Jackson, step 6a). Dependabot opens weekly update PRs for the Gradle libraries and the workflow's actions (`.github/dependabot.yml`).

Time frames, from the day a fix is available:

| Severity | Fix within |
|---|---|
| Critical | 7 days, with an out-of-cycle release |
| High | 30 days |
| Medium, Low | The next release |

To check locally, run the same commands as CI's `build` and `image` jobs (`.github/workflows/ci.yml`): `./gradlew cyclonedxBom` and its Grype line, then `docker build --file Dockerfile.vercel --tag restaurant-backend:ci .` and the image job's two `docker run` lines.
