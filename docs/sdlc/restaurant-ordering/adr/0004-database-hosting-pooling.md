# ADR-0004: Managed PostgreSQL in Singapore behind a transaction-mode pooler

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Proposed (provider chosen by spike S-2 and TBD-10) | 2026-10-13 | Manh Nguyen (tech lead) | NFR-PERF-01/02, NFR-SCAL-01, NFR-REL-01, NFR-PRIV-02 |

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
Option 1, provider confirmed by S-2.
- The app connects through the **transaction-mode pooler** with:
  - Hikari `maximumPoolSize = 10`, `minimumIdle = 1` (connections open on demand, so cold starts don't pay for 10) and short idle timeouts;
  - `connectionTimeout = 2 s`: a request that can't get a connection fails fast with 503 instead of queueing;
  - `statement_timeout = 5 s` on the `app` role (a role setting, because the pooler doesn't support session `SET`);
  - pgjdbc `prepareThreshold=0`, because server-side prepared statements don't survive transaction pooling;
  - only transaction-scoped session state (`SET LOCAL`, `pg_try_advisory_xact_lock`).
- Migrations use a direct (unpooled) connection from CI, with the DDL user.
- Two database users: `app` (DML only, with INSERT/SELECT only on `audit_events`) and `migrator` (DDL).

## Consequences
- Positive: connection bursts are absorbed; preview databases per PR.
- Negative: no `LISTEN/NOTIFY` and no session-level advisory locks (we don't need them); each transaction's latency includes the pooler hop.
- Pool size (S-2 run 3, 2026-10-14, owner's decision): 3 per instance made requests queue (50 concurrent 2 s transactions took 6.2 s), because Vercel sends many concurrent requests to one instance. With 10, warm: 2.3–2.8 s and 0 errors; order transactions at 10 concurrent p95 22.6 ms. The pooler, not the app pool, caps PostgreSQL connections, so a bigger app pool doesn't overload the database.
- Follow-ups: S-2 measures order-transaction latency from `sin1` (done: p95 28.7 ms); confirm the PITR window, HA and a cross-region backup copy; region per TBD-10.
