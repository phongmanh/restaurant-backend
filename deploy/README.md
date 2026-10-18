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
| `ABLY_API_KEY` | Ably console, the app's API keys |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_HEADERS` | Grafana Cloud, the stack's OpenTelemetry (OTLP) connection: the endpoint, and the `Authorization=Basic …` header made with a token that can write metrics (ADR-0009, TBD-12) |
| `VERCEL_AUTOMATION_BYPASS_SECRET` | Vercel project → Settings → Deployment Protection → Protection Bypass for Automation. Test clients send it as `x-vercel-protection-bypass`; never sent to the service |

The service refuses to start if a secret is missing and names every missing one in the error (core-and-auth §13).

## Staging runbook
Steps marked **you** are the owner's. The auto-mode safety check blocks Claude from production deploys and from writing secrets to Vercel, and the console steps need your accounts. Claude runs the rest and reads the results.

1. **You: create the Neon project.** At console.neon.tech, create project `restaurant-staging` in **AWS Asia Pacific (Singapore)** with PostgreSQL **18**, the version the tests run on. Create a role `migrator` and a database `restaurant` owned by `migrator`. Don't create `app` or `app_platform`: `migrate` creates them.
2. **You: send Claude the pooled host.** Open the connection details for `migrator` on `restaurant` with **connection pooling on** and copy only the host, `ep-…-pooler.ap-southeast-1.aws.neon.tech`. The host is not a secret.
3. **Claude: generate the secrets.** Run from the repository root; it prints names only and never overwrites an existing file:
   ```
   ./gradlew generateSecrets --args="deploy/staging/.env.staging --db-host <pooled host> --db-name restaurant"
   ```
4. **You: fill in the provider secrets** in `deploy/staging/.env.staging`. The file has an empty line for each:
   - `DATABASE_URL_MIGRATOR_DIRECT`: the `migrator` connection string with **pooling off** (the host has no `-pooler`).
   - Upstash: create a free Redis database `restaurant-staging` in **AWS ap-southeast-1 (Singapore)**, then copy `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN`.
   - Ably: create an app `restaurant-staging`. Add an API key with only the **publish** and **subscribe** capabilities, on the resource `*`, and copy it into `ABLY_API_KEY`. The app holds only this environment's channels, and the tokens the service mints narrow it further, to one `restaurant.<id>.staff` or `session.<id>` channel each.
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
8. **You: create the WAF rule.** Hobby allows one rate-limit rule per project (ADR-0008). Project → Firewall → New rule `auth-per-ip`: if the request path starts with `/v1/auth/`, rate-limit with a fixed window of 60 requests per 60 s per IP, action 429. That's twice the app's own sign-in limit (30 per 60 s), so the app's 429, which carries `Retry-After`, answers first. The CLI's `rules add` left no rule in S-1, so use the dashboard.
9. **You: send the environment variables** to the project's Production environment:
   ```
   deploy/push-env.sh deploy/staging/staging.env deploy/staging/.env.staging
   ```
10. **You: deploy, then remove the public alias.** Send Claude the deployment URL printed by `vercel deploy`, never the bypass secret:
    ```
    vercel deploy --prod
    vercel alias rm restaurant-staging.vercel.app --yes
    ```
11. **Claude: smoke-check the deployment.** `GET /health` with the bypass header answers 200, and the same request without it is refused by Deployment Protection. Then read the Vercel logs for the start-up line, and look for any secret or personal data in them.
12. **Claude: check the scheduled jobs** the day after the deploy. The Cron Jobs tab shows one run of each, and on Hobby each runs once a day at most. A run against a deployment without its alias is recorded, whatever the result.

### Metrics backend (ADR-0009, TBD-12)
1. **You:** at grafana.com, sign up for the free plan and create a stack, choosing the **Singapore** region if it's offered (TBD-10 may care where data lives). Otherwise pick the nearest.
2. **You:** in the stack, open **Connections → OpenTelemetry (OTLP)** and create a token. The page then shows the endpoint (`https://otlp-gateway-….grafana.net/otlp`) and the header, `Authorization=Basic …`. Paste them after `OTEL_EXPORTER_OTLP_ENDPOINT=` and `OTEL_EXPORTER_OTLP_HEADERS=` in `deploy/staging/.env.staging`, adding the two lines if the file doesn't have them. The header holds the token: never paste it into chat.
3. **You:** run steps 9 and 10 again (send the variables, deploy, remove the alias). Without both settings, a staging deployment refuses to start and names them.
4. **Claude:** after some traffic, check in Grafana's **Explore** that `ktor_http_server_requests_milliseconds_count{job="restaurant-backend"}` arrives. Micrometer sends timers in milliseconds, and Grafana adds the unit to the name. If Grafana names it differently, the alerts would find no data and never fire: fix `MetricNamesTest`'s naming to match what Explore shows, then the rules and dashboards it then rejects.

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
4. **You: point staging at the branch.** In the Vercel project's settings, edit `DATABASE_URL` and `DATABASE_URL_PLATFORM` (Production) and change only the host to the branch's. Then deploy as in step 10 and remove the alias.
5. **Claude: check it**, and note the time as the end of the restore: `/health` answers 200, then `./gradlew --no-daemon systemTest -PstagingUrl=<new deployment URL> --tests '*RestoreDrillTest' -PrestoreDrill=check -PrestorePoint=<the point, RFC 3339>`. It passes when the first mark is there and the second isn't. RTO is from step 3's start to this check. RPO is the gap between the point you chose and the newest write that came back, at most the minutes between the marks.
6. **You: switch back.** Put the two values back to `main`'s host and redeploy as in step 10. **Claude:** `-PrestoreDrill=clean` against that deployment, back on `main`, removes the marks there; the branch's copies go with the branch. **You:** delete the `restore-st08` branch in the Neon console (the free plan allows 10 branches).

### Redeploying
Repeat step 10. Migrations come first whenever the release adds one (step 5): they are expand-only within a release, so the deployment still running keeps working (architecture §8).

### Rotating a secret
Edit the line in `.env.staging`, run step 9, then step 10: a deployment reads its variables when it starts. For a signing key, keep the old public key in the set until the tokens it signed have expired (core-and-auth §8).
