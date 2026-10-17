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
4. **Claude:** after some traffic, check in Grafana's **Explore** that `ktor_http_server_requests_seconds_count` arrives with `service_name="restaurant-backend"`.

### Staging data (test-plan §6)
Claude runs both, with the environment exported as in step 5 from **both** files (the seed needs the service's settings too):
```
(for f in deploy/staging/staging.env deploy/staging/.env.staging; do
   while IFS= read -r line; do case "$line" in ''|'#'*) ;; *) export "$line" ;; esac; done < "$f"; done
 ./gradlew --no-daemon seedStaging --args="<deployment URL>")
```
- **`seedStaging`** (about 160 requests) makes two admins: the first, with TOTP enrolled, comes from the operator's service call against the database; the second is invited and not enrolled. It then makes restaurants A and B through the API. Each has a manager, two waiters, a kitchen user, a deactivated waiter, a waiter locked by ten failed sign-ins (the lock lifts after 15 minutes), a menu with hidden, sold-out and option items, and 25 tables. The accounts go to `deploy/staging/.accounts.staging`, which is git-ignored and owner-only, like the env file. A second run skips what that file already lists.
- **`generateHistory`** (about 1,100 requests), the same way, after the seed. Restaurant L (1,000 items, 25 tables) and 49 smaller restaurants are onboarded through the API. The history rows are then written through the service's own transactions: L's last 30 days as real bills, with their daily rows rebuilt by the reconcile's own code, and a year of daily rows for every restaurant. The run prints its size estimate and stops above 300 MB.

### Redeploying
Repeat step 10. Migrations come first whenever the release adds one (step 5): they are expand-only within a release, so the deployment still running keeps working (architecture §8).

### Rotating a secret
Edit the line in `.env.staging`, run step 9, then step 10: a deployment reads its variables when it starts. For a signing key, keep the old public key in the set until the tokens it signed have expired (core-and-auth §8).
