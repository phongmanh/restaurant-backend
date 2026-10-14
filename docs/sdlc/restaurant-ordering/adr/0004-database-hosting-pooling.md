# ADR-0004: Managed PostgreSQL in Singapore behind a transaction-mode pooler

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Accepted (S-2 met its exit criterion on Neon in Singapore); the region stays subject to TBD-10 | 2026-10-14 | Manh Nguyen (product owner, tech lead) | NFR-PERF-01/02, NFR-SCAL-01, NFR-REL-01, NFR-PRIV-02 |

## Context
PostgreSQL is the default store. On serverless, each instance has its own connection pool, so scale-out bursts can exhaust the database's connections. The database must be close to `sin1`, support PITR (RPO ≤ 5 min) and an HA standby, and ideally branch per PR for preview deployments.

## Options
1. **A serverless or managed PostgreSQL with a built-in pooler** (e.g. Neon via the Vercel Marketplace, or Supabase) in Singapore
   - Pros: Vercel integration; branches per PR (Neon); PITR.
   - Cons: transaction pooling disables session features.
2. **Cloud SQL / RDS in Singapore with PgBouncer**
   - Pros: mature HA.
   - Cons: we run the pooler ourselves; no per-PR branches.

## Decision
Option 1: **Neon** (through the Vercel Marketplace integration), AWS `ap-southeast-1`, the same AWS region as `sin1`. S-2 ran on PostgreSQL 18.6. Neon over Supabase for its database branch per PR, which the preview environments need.
- The app connects through the **transaction-mode pooler** with:
  - Hikari `maximumPoolSize = 10`, `minimumIdle = 1` (connections open on demand, so cold starts don't pay for 10) and short idle timeouts;
  - `connectionTimeout = 2 s`: a request that can't get a connection fails fast with 503 instead of queueing;
  - `statement_timeout = 5 s` on the `app` role (a role setting, because the pooler doesn't support session `SET`);
  - pgjdbc `prepareThreshold=0`. Neon's pooler (PgBouncer ≥ 1.22) keeps server-side prepared statements working (S-2 Q3: 0 errors at 0, 1 and 5), so this isn't required on Neon; it costs nothing and keeps the app portable to poolers without that support;
  - only transaction-scoped session state (`SET LOCAL`, `pg_try_advisory_xact_lock`).
- Migrations use a direct (unpooled) connection from CI, with the DDL user.
- Two database users: `app` (DML only, with INSERT/SELECT only on `audit_events`) and `migrator` (DDL; on Neon, the integration's owner role).
  - `app` is created with plain SQL by a migration, **never in the provider's console**: Neon adds console-made roles to `neon_superuser`, which has `BYPASSRLS` and would quietly switch off row-level security (ADR-0005). The migration checks `app` has no superuser, `BYPASSRLS`, `CREATEROLE` or `CREATEDB` rights.
  - On re-runs the migration only resets the password and checks those rights: since PostgreSQL 16, a non-superuser can't run `ALTER ROLE … NOSUPERUSER`.

## Consequences
- Positive: connection bursts are absorbed; preview databases per PR.
- Negative: no `LISTEN/NOTIFY`, no session-level advisory locks, no session `SET`/`RESET` and no SQL `PREPARE` through the pooler (we need none of them); each transaction's latency includes the pooler hop.
- Measured in S-2 from `sin1` ([results](../../../../spikes/s2-postgres/README.md#results)):
  - `SELECT 1` p95 3.8 ms pooled, 2.9 ms direct: the pooler costs about 0.5–1 ms per round trip.
  - Order-placement transaction p95 28.7 ms one at a time, 22.6 ms at 10 concurrent with pool 10 (exit criterion ≤ 40 ms). The tail is worth watching: p99 57–90 ms, with rare outliers of 200–800 ms.
  - Opening the first pooled connection takes about 0.9–1.7 s (TLS and sign-in), which adds to a cold start. `minimumIdle = 1` pays it once per instance.
  - Transaction advisory locks are exclusive across instances; Flyway from CI applied a migration; `app` is denied DDL.
- Pooler limits (Neon, not configurable): PgBouncer in transaction mode, up to 10,000 client connections, PostgreSQL connections capped at 90 % of `max_connections`, which the compute size sets (TBD-11).
- Plan: the free plan keeps PITR for 6 hours and always scales to zero after 5 minutes idle. PITR ≥ 7 days and an always-on compute need the Launch plan or above. On the free plans of this learning project that's a residual risk, not a to-do (R-11): S-2 didn't measure how long a suspended compute takes to wake.
- Pool size (S-2 run 3, 2026-10-14, owner's decision): 3 per instance made requests queue (50 concurrent 2 s transactions took 6.2 s), because Vercel sends many concurrent requests to one instance. With 10, warm: 2.3–2.8 s and 0 errors; order transactions at 10 concurrent p95 22.6 ms. The pooler, not the app pool, caps PostgreSQL connections, so a bigger app pool doesn't overload the database.
- Follow-ups: confirm HA (standby or restart time after a compute failure) and a cross-region backup copy on a paid plan; region per TBD-10.
