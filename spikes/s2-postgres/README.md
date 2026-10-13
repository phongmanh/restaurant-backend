# S-2 spike: PostgreSQL through the pooler from `sin1`

**Throwaway.** This code answers the S-2 questions for [ADR-0004](../../docs/sdlc/restaurant-ordering/adr/0004-database-hosting-pooling.md) (database hosting and pooling) and [ADR-0005](../../docs/sdlc/restaurant-ordering/adr/0005-tenant-isolation.md) (RLS with `SET LOCAL`), and is then deleted.

Status: **planned, 2026-10-13. Waiting for the database** (owner step 1 below). Nothing is built yet.

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
- Plain JDBC + Hikari, not Exposed: the spike measures the database path, and Exposed adds little to it.
- Schema (Flyway `V1__spike.sql`): `restaurants`, `orders`, `order_lines`, `events`, `outbox`, `idempotency_keys`, each with `restaurant_id`, RLS enabled and forced, policy `restaurant_id = current_setting('app.restaurant_id')::uuid`; grants to `app` for DML only.
- `measure.mjs` from S-1, extended with `db-ping`, `db-order`, `db-rls`, `db-lock` modes (keepalive already fixed).

## Provider
ADR-0004 option 1 names **Neon** (Vercel Marketplace, a branch per PR) or **Supabase**; both have a Singapore region (AWS `ap-southeast-1`, the same AWS region as Vercel's `sin1`). Recommended: **Neon**, for per-PR database branches, which the preview environments in the test plan need.

## Owner steps (things Claude can't do)
1. Create a Neon project on the free plan in **AWS Asia Pacific (Singapore)**.
2. In the Neon console, create the roles `migrator` and `app`, and a database `spike` owned by `migrator`.
3. Put three connection strings in `spikes/s2-postgres/.env.spike` (ignored by git). Never paste them into chat.
   ```
   DATABASE_URL_MIGRATOR_DIRECT=postgresql://migrator:…@ep-….ap-southeast-1.aws.neon.tech/spike?sslmode=require
   DATABASE_URL_APP_POOLED=postgresql://app:…@ep-…-pooler.ap-southeast-1.aws.neon.tech/spike?sslmode=require
   DATABASE_URL_APP_DIRECT=postgresql://app:…@ep-….ap-southeast-1.aws.neon.tech/spike?sslmode=require
   ```
4. Later, when asked: add the same values as Vercel env vars and as GitHub Actions secrets.

## Results
| Question | Result | Meets criterion? |
|---|---|---|
| Q1 ping | | |
| Q2 order transaction | | |
| Q3 prepared statements | | |
| Q4 RLS | | |
| Q5 advisory locks | | |
| Q6 Flyway from CI | | |
| Q7 connections | | |
| Q8 provider | | |
