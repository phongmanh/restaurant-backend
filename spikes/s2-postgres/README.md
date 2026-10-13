# S-2 spike: PostgreSQL through the pooler from `sin1`

**Throwaway.** This code answers the S-2 questions for [ADR-0004](../../docs/sdlc/restaurant-ordering/adr/0004-database-hosting-pooling.md) (database hosting and pooling) and [ADR-0005](../../docs/sdlc/restaurant-ordering/adr/0005-tenant-isolation.md) (RLS with `SET LOCAL`), and is then deleted.

Status: **database ready and migrated, 2026-10-13; correctness questions answered from the laptop. Waiting for the Vercel deploy** (owner step 3) to measure latency and connections from `sin1`. 15 tests pass against an in-process PostgreSQL 18, and every `measure.mjs` mode has run against `./gradlew runLocal`. That proves the code, not the numbers: there's no pooler and no network locally.

## What ADR-0004 and ADR-0005 assume, and S-2 must prove
- The app reaches PostgreSQL through a **transaction-mode pooler**: Hikari `maximumPoolSize = 3`, short idle timeouts, pgjdbc `prepareThreshold=0`.
- Only transaction-scoped state is used: `SET LOCAL app.restaurant_id` for RLS, and `pg_try_advisory_xact_lock` for job locks.
- Migrations run from CI over a **direct (unpooled)** connection as `migrator`; the app connects as `app` (DML only).
- Exit criterion (architecture §17): **order-placement transaction p95 ≤ 40 ms from a Vercel function.**

## Questions
| # | Question | How |
|---|---|---|
| Q1 | Round-trip latency function → pooler → database, warm, from `sin1` | `GET /spike/db/ping` (`SELECT 1`), p50/p95 timed inside the function; also over the direct URL for comparison |
| Q2 | Order-placement transaction time (the exit criterion) | `POST /spike/db/order`: `SET LOCAL` + idempotency key + order + 3 lines + event + outbox in one transaction, timed inside the function |
| Q3 | Do server-side prepared statements break through the pooler, and does `prepareThreshold=0` fix it? | Run Q2 with the pgjdbc default (5) and with 0; count errors such as `prepared statement "S_1" does not exist` |
| Q4 | Does RLS isolate tenants, fail closed, and never leak `SET LOCAL` between pooled transactions? | `GET /spike/db/rls`: tenant A can't read or write B's rows; no `SET LOCAL` → 0 rows; a mixed-tenant burst of 500 requests returns only each request's own tenant |
| Q5 | Do transaction advisory locks work through the pooler? | `POST /spike/db/lock`: 10 concurrent calls on one key → exactly 1 `true` while held; released at commit |
| Q6 | Can CI run Flyway over the direct connection, and is `app` denied DDL? | GitHub Actions `workflow_dispatch` job running Flyway as `migrator`; `CREATE TABLE` as `app` must fail |
| Q7 | What does a cold start and a scale-out do to connections? | Hikari pool start time in `startupMs`; `pg_stat_activity` count during a 50-request burst vs the provider's limit |
| Q8 | Provider facts | Region, pooler mode, connection limits, PITR window, branch per PR, price for the capacity model (TBD-11) — from the console and docs |

## Design
- Ktor app packaged like `spikes/s1-vercel` (`Dockerfile.vercel`, `PORT=8080`, PATH fix, `sin1`), as a **new** Vercel project `restaurant-s2-spike` on the free test account.
- Plain JDBC + Hikari, not Exposed: the spike measures the database path, and Exposed adds little to it. Two pools as `app`, `pooled` and `direct`; each endpoint takes `?via=pooled|direct`. Both pools open before the server starts, so `startupMs` includes connecting.
- Schema (Flyway `V1__spike.sql`): `restaurants`, `orders`, `order_lines`, `events`, `outbox`, `idempotency_keys`, each with `restaurant_id`, RLS enabled and forced; grants to `app` for DML only, table by table.
- Decisions made while building, to check against ADR-0005 when S-2 closes:
  - The tenant is set with `SELECT set_config('app.restaurant_id', ?, true)`. It's the same as `SET LOCAL`, but it takes a bind parameter, and `SET LOCAL` can't.
  - Policy: `restaurant_id = nullif(current_setting('app.restaurant_id', true), '')::uuid`. Without `missing_ok` and `nullif`, a transaction that forgot to set the tenant gets an **error** (unknown setting, or `''` isn't a uuid) instead of **0 rows**. Either way it fails closed, but 0 rows is the behaviour the requirement describes.
  - The `app` role is created with plain SQL by `./gradlew migrate`, **not in the Neon console**. Roles made in the console join `neon_superuser`, which has `BYPASSRLS` (Neon docs, "Manage roles"), and that would quietly void every RLS result. The first RLS check reports `rolbypassrls` to prove it.
- Code: `Database.kt` (pools, the timed transaction helper), `DbQueries.kt` (the SQL for each question), `SpikeModule.kt` (endpoints), `Migrate.kt` (Q6), `scripts/measure.mjs` (measurements), `scripts/setup-env.mjs` (env file).

| Endpoint | Question |
|---|---|
| `GET /spike/info` | startup, pool open times, pool usage |
| `GET /spike/db/ping` | Q1 |
| `POST /spike/db/order?restaurant=a\|b&prepareThreshold=0&key=…` | Q2, Q3 (same `key` twice → replay) |
| `GET /spike/db/rls` | Q4: ten fixed checks |
| `GET /spike/db/tenant?restaurant=a\|b` | Q4 burst: one request's view, then the view with no tenant set |
| `POST /spike/db/lock?key=…&holdMs=…` | Q5 |
| `GET /spike/db/connections` | Q7: `pg_stat_activity` by role and state |

## Provider
ADR-0004 option 1 names **Neon** (Vercel Marketplace, a branch per PR) or **Supabase**; both have a Singapore region (AWS `ap-southeast-1`, the same AWS region as Vercel's `sin1`). Recommended: **Neon**, for per-PR database branches, which the preview environments in the test plan need.

## Owner steps (things Claude can't do)
1. Create a Neon project on the free plan in **AWS Asia Pacific (Singapore)**, PostgreSQL 17. In the console, create a role `migrator` and a database `spike` owned by `migrator`. **Don't create `app`** (see Design).
2. Copy the **direct** connection string for `migrator` (connection pooling switched **off**; the host has no `-pooler`) into `spikes/s2-postgres/.env.spike` as one line (the file is ignored by git, and you shouldn't paste the string into chat):
   ```
   DATABASE_URL_MIGRATOR_DIRECT=postgresql://migrator:…@ep-….ap-southeast-1.aws.neon.tech/spike?sslmode=require&channel_binding=require
   ```
   Pasting the whole block that Vercel's Neon integration shows (`DATABASE_URL`, `DATABASE_URL_UNPOOLED`, `PG…`) works too: its owner role, `neondb_owner`, then acts as `migrator`. The region must be Singapore; `setup-env.mjs` refuses any other.
   Then tell Claude. Claude runs `setup-env.mjs`, which generates `app`'s password and appends `DATABASE_URL_APP_DIRECT` and `DATABASE_URL_APP_POOLED` to the same file without printing them.
3. Deploy to Vercel (Claude can't: production deploys and sending secrets are yours). From `spikes/s2-postgres`; the `node … |` pipes pass each value from `.env.spike` to the CLI without showing it:
   ```
   vercel link                                   # new project "restaurant-s2-spike" in liam-36c7
   for t in production preview; do
     echo 8080 | vercel env add PORT $t
     node --env-file=.env.spike -e 'process.stdout.write(process.env.DATABASE_URL_APP_POOLED)' | vercel env add DATABASE_URL_APP_POOLED $t
     node --env-file=.env.spike -e 'process.stdout.write(process.env.DATABASE_URL_APP_DIRECT)' | vercel env add DATABASE_URL_APP_DIRECT $t
   done
   vercel deploy --prod                          # first deploy; ~3 min (image build)
   vercel alias rm restaurant-s2-spike.vercel.app  # the production alias is public
   ```
   Then give Claude the deployment URL (`https://restaurant-s2-spike-….vercel.app`), and put `VERCEL_AUTOMATION_BYPASS_SECRET` in `.env.spike` (reuse S-1's, or Project Settings → Deployment Protection → Protection Bypass for Automation).
4. Add `DATABASE_URL_MIGRATOR_DIRECT` and `DATABASE_URL_APP_DIRECT` as GitHub Actions secrets (Settings → Secrets and variables → Actions) for `.github/workflows/s2-flyway.yml`, then push the branch: the push runs the workflow (Q6 from CI).

## How to run
From `spikes/s2-postgres`:
```
./gradlew test                                                     # local, no Docker needed
node --env-file=.env.spike scripts/setup-env.mjs                   # once, after owner step 2
node --env-file=.env.spike -e 'require("child_process").execFileSync("./gradlew", ["-q", "migrate"], {stdio: "inherit"})'
                                                                   # role + Flyway + DDL probes (Q6, locally); `set -a; . .env.spike` breaks on the & in the URLs
./gradlew runLocal                                                 # dry run on localhost:8080
node scripts/measure.mjs <mode> <baseUrl> [--option value]         # modes and options: see the script's header
```
Order of the Neon run: `migrate` → deploy → `info`, `rls`, `ping --via pooled`, `ping --via direct`, `order` (c 1, then c 10), `prepared`, `burst`, `lock`, `connections`, `cold` (Neon's free compute suspends after 5 min idle, so `cold --idle 360` also measures its wake-up) → the CI run for Q6 → Q8 from the console and docs.

## Results
Database: Neon, created through Vercel's Neon integration, AWS `ap-southeast-1`, **PostgreSQL 18.6**. The integration's owner role `neondb_owner` acts as `migrator`; database `neondb`. (A first attempt landed in `us-east-1` and was recreated: `setup-env.mjs` now refuses any region but Singapore.)

| Question | Result | Meets criterion? |
|---|---|---|
| Q1 ping | Pending: needs the `sin1` deploy | |
| Q2 order transaction | Pending: needs the `sin1` deploy | |
| Q3 prepared statements | Through Neon's pooler, 60 orders each at `prepareThreshold` 0, 1 and 5: **0 errors**. At 1 and 5, the server backend held 7 prepared statements, so named statements reach the server and the pooler keeps them working (PgBouncer's protocol-level prepared-statement support). `prepareThreshold=0` isn't required on Neon; keeping it costs nothing and keeps the app portable to poolers without that support | Yes |
| Q4 RLS | 10/10 checks pass both through the pooler and directly; `app` has `rolbypassrls=false`. Mixed-tenant burst, 500 requests at 50 concurrent: **0 leaks** (0 wrong settings, 0 other-tenant rows, 0 settings left after commit) | Yes |
| Q5 advisory locks | 10 concurrent tries on one key, held 3 s: exactly 1 acquired, no overlapping holds, and the key was free right after the holder's commit | Yes |
| Q6 Flyway from CI | From the laptop: `app` created with NOBYPASSRLS; Flyway applied V1 as `neondb_owner` over the direct connection; `app` denied CREATE TABLE, ALTER TABLE and DROP POLICY (42501). Pending: the same run from GitHub Actions | Partly |
| Q7 connections | Pending: needs the `sin1` deploy | |
| Q8 provider | Region `ap-southeast-1`, PostgreSQL 18.6. Rest pending (pooler limits, PITR, branches, price) | |

### Run 1: from the laptop (2026-10-13)
The app ran on the laptop (`./gradlew run`) against Neon, so only results that don't depend on where the app runs count (Q3–Q5). Laptop latency isn't a result.
- The burst's 35 errors out of 500 were all Hikari's own: "Connection is not available, request timed out after 5000 ms (total=3, active=3, waiting=46)". Each transaction crossed from Vietnam to Singapore, so 50 concurrent requests waited for 3 connections for longer than `connectionTimeout`. No database or pooler error. Worth rechecking from `sin1`: pool size 3 with a 5 s timeout means a burst on one instance fails fast instead of queueing long.
