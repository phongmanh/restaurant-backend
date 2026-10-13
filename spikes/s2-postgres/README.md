# S-2 spike: PostgreSQL through the pooler from `sin1`

**Throwaway.** This code answers the S-2 questions for [ADR-0004](../../docs/sdlc/restaurant-ordering/adr/0004-database-hosting-pooling.md) (database hosting and pooling) and [ADR-0005](../../docs/sdlc/restaurant-ordering/adr/0005-tenant-isolation.md) (RLS with `SET LOCAL`), and is then deleted.

Status: **run 2 from `sin1` done, 2026-10-13. The exit criterion is met** (order transaction p95 28.7 ms over the pooler). Left: Q6 from CI (owner step 4) and the cold-start samples. 15 tests pass against an in-process PostgreSQL 18, and every `measure.mjs` mode has run against `./gradlew runLocal`. That proves the code, not the numbers: there's no pooler and no network locally.

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
   Then give Claude the deployment URL (`https://restaurant-s2-spike-….vercel.app`), and put this project's `VERCEL_AUTOMATION_BYPASS_SECRET` in `.env.spike` (`restaurant-s2-spike` → Settings → Deployment Protection → Protection Bypass for Automation). The secret is per project: S-1's gets a 302 "Protected by Vercel Authentication".
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
| Q1 ping | `SELECT 1` inside the function in `sin1`: **pooled p50 2.1 · p95 3.8 ms**, direct p50 1.7 · p95 2.9 ms. The pooler costs about 0.5–1 ms per round trip. Getting a connection from Hikari: p95 0.03 ms | Yes (no criterion of its own) |
| Q2 order transaction | Set tenant → idempotency key → order → 3 lines → event → outbox → commit, timed inside the function. **Pooled, one at a time: p50 12.7 · p95 28.7 · p99 57 ms** (max 198, one outlier). Pooled, 10 at a time on 4 instances: transaction p95 25 ms; with the wait for a connection, **p95 31.7** · p99 75.9 ms. Direct, one at a time: p50 10.0 · p95 13.1 ms | **Yes: p95 ≤ 40 ms.** The pooled tail (p99 57–76 ms) is worth watching |
| Q3 prepared statements | Through Neon's pooler, 60 orders each at `prepareThreshold` 0, 1 and 5: **0 errors**, from the laptop and from `sin1`. At 1 and 5, the server backend held 7 prepared statements, so named statements reach the server and the pooler keeps them working (Neon runs PgBouncer ≥ 1.22 with `max_prepared_statements=1000`). `prepareThreshold=0` isn't required on Neon; keeping it costs nothing and keeps the app portable to poolers without that support | Yes |
| Q4 RLS | 10/10 checks pass both through the pooler and directly, from the laptop and from `sin1`; `app` has `rolbypassrls=false`. Mixed-tenant burst from `sin1`, 500 requests at 50 concurrent over **25 instances and 10 server backends: 0 leaks, 0 errors** | Yes |
| Q5 advisory locks | 10 concurrent tries on one key across 9 instances, held 3 s: exactly 1 acquired, no overlapping holds, and the key was free right after the holder's commit | Yes |
| Q6 Flyway from CI | From the laptop: `app` created with NOBYPASSRLS; Flyway applied V1 as `neondb_owner` over the direct connection; `app` denied CREATE TABLE, ALTER TABLE and DROP POLICY (42501). Pending: the same run from GitHub Actions | Partly |
| Q7 connections | Cold instance: JVM to ready **2,718 ms**, of which opening the pooled pool took 895 ms and the direct one 144 ms (cold samples below). **Vercel scales out fast:** 10 concurrent requests used 4 instances, 50 used 9–25. 50 concurrent transactions holding 2 s: at most **52 `app` backends of `max_connections` 112**, 0 errors, but a wall time of 6.2 s instead of 2 s: requests queued for the 3 connections per instance. The spike's direct pool keeps 1 connection per instance straight to PostgreSQL, so it grows with instances; the production app uses only the pooled path, where PostgreSQL connections are capped by the pooler (`default_pool_size` = 90 % of `max_connections`) and the pooler takes up to 10,000 clients | Yes, with a sizing point for ADR-0004 (below) |
| Q8 provider | Neon via Vercel's integration, AWS `ap-southeast-1`, PostgreSQL 18.6. Pooler: PgBouncer, transaction mode, `max_client_conn` 10,000, `default_pool_size` 90 % of `max_connections`, `query_wait_timeout` 120 s, not configurable; unsupported: `SET`/`RESET`, `LISTEN`/`NOTIFY`, SQL `PREPARE`, session advisory locks. Plans (neon.com/pricing, 2026-10-13): **Free** 100 CU-hours and 1 GB storage per project (20 GB per account), PITR 6 h, scale to zero after 5 min (can't be turned off); **Launch** $0.106/CU-hour, $0.35/GB-month, PITR up to 7 days, scale to zero can be disabled; **Scale** $0.222/CU-hour, PITR up to 30 days, fixed sizes up to 56 CU. Branches: 10 per project (Free, Launch), 25 (Scale), extra $1.50/branch-month | Facts for TBD-11; PITR needs at least Launch |

**Sizing point for ADR-0004.** `maximumPoolSize = 3` held the database load safely, but Vercel sends many concurrent requests to one instance, so at 50 concurrent requests on 9 instances, requests waited for a connection (wall time 3× the hold time). Ordinary transactions take ~13 ms, so the wait is small at real traffic, but a slow transaction blocks the instance's other requests. Options for G2: raise the pool size (the pooler absorbs it: 10,000 clients) or keep 3 and set a short `connectionTimeout` so the request fails fast. Decide with the owner.

### Run 2: from `sin1` (2026-10-13)
Deployment `restaurant-s2-spike-d1oiubf9s-liam-36c7.vercel.app` (`dpl_DdYUaEbohJqaZnh2h6Z5N281oaTd`), production target on the free test account; the public alias `restaurant-s2-spike.vercel.app` was removed. Client: Vietnam (client round trip p50 ≈ 40–45 ms, which doesn't count). Commands: `info`, `rls`, `ping` (pooled, direct, n 200), `order` (pooled c 1, direct c 1, pooled c 10, n 200), `prepared` (n 60, c 3), `burst` (n 500, c 50), `lock` (c 10, 3 s), `connections` (c 50, 2 s). `max_connections` read 112, where Neon's docs list 104 for 0.25 CU; the free plan autoscales up to 2 CU, so the value depends on the compute size at the time.

### Run 1: from the laptop (2026-10-13)
The app ran on the laptop (`./gradlew run`) against Neon, so only results that don't depend on where the app runs count (Q3–Q5). Laptop latency isn't a result.
- The burst's 35 errors out of 500 were all Hikari's own: "Connection is not available, request timed out after 5000 ms (total=3, active=3, waiting=46)". Each transaction crossed from Vietnam to Singapore, so 50 concurrent requests waited for 3 connections for longer than `connectionTimeout`. No database or pooler error. Worth rechecking from `sin1`: pool size 3 with a 5 s timeout means a burst on one instance fails fast instead of queueing long.
