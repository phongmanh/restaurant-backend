# Alert runbooks

What to do when an alert of [`rules.yaml`](rules.yaml) fires (NFR-OBS-01: every alert links to a runbook). Each alert's `runbook_url` points at one section here, and `AlertRulesTest` fails if the section is missing. The other runbooks architecture §13 lists (QR leak, restore, secret rotation, suspending a restaurant and the rest) belong to the release checklist (Phase 6).

Where to look, for every alert:
- **Dashboards** in Grafana: *Restaurant backend: service overview*, *order flow*, and *database and rate limits*.
- **Logs**: Vercel project `restaurant-staging` → Logs. Every request's log line carries its `request_id`, the same ID the client got in `X-Request-ID` and in any problem+json body. Logs hold IDs, never personal data (architecture §11).
- **Who acts:** the ops owner isn't named yet (R-07). Until then, the owner.

## SLO burn
`AvailabilityBurnFast`/`Slow`, `OrderFlowBurnFast`/`Slow`, `GuestReadLatencyBurnFast`/`Slow`.

The alert means an SLO is spending its error budget faster than it can afford. *Fast* (critical): at this rate a 28-day budget lasts about 2 days, so act now. *Slow* (warning): it lasts about 5 days, so look today. The `slo` label says which:
- `availability`: too many 5xx answers. The overview's *5xx by route* panel shows where they come from.
- `order_flow`: order placements fail, or live updates reach Ably more than 2 s after the change. The order-flow dashboard splits the two.
- `guest_read_p95` / `guest_read_p99`: guest reads (menu, session, orders, bill) are slower than 200 ms or 500 ms.

Steps:
1. **Did it start with a deploy?** Compare the start with the last `vercel deploy --prod`. If so, roll back: Vercel project → Deployments → the previous production deployment → *Instant Rollback* (owner; NFR-OPS-01, ≤ 10 min). Migrations are expand-only, so the previous build still works with the database.
2. **Is a dependency down?** Database errors in the logs, or the *database and rate limits* dashboard showing requests waiting for connections, point to [database saturation](#database-saturation). Check the providers' status pages: Neon, Ably, Upstash, Vercel.
3. **Is it one route or one restaurant?** Read the 5xx log lines for the route and look at their `restaurant_id`. One tenant sending far more than the rest is a noisy tenant: the WAF and Upstash limits should hold it (ADR-0008), and suspending the restaurant is the last resort (restaurants §6).
4. **Latency only:** many instances starting at once (cold starts) don't show in this SLI, which starts timing when Ktor gets the request. Slow guest reads with few starts point to the database: check the pool panels.
5. Write what happened in STATUS.md's Decisions table if it changed anything.

## Outbox backlog
`OutboxBacklogOld`: the latest outbox sweep left an event unsent for more than 2 minutes (realtime §11).

Live updates normally go to Ably straight after each commit. An event Ably refused waits in the outbox, and the sweep job sends it later. Clients still see every change, but late: they catch up on reconnect, or with the list endpoints.
1. Look at the order-flow dashboard's *Publishes by path and result*. Many `failed` or `rate_limited` means Ably is refusing: see [pub/sub outage](#pubsub-outage).
2. If Ably is fine, find the sweep's own log line, `outbox sweep: published=… failed=… pending=… oldest_pending_seconds=…`. A sweep that publishes nothing while events are pending points to the job or its lock.
3. To send the backlog now, run the sweep by hand with the cron secret (Claude can prepare the command; the secret stays in `.env.staging`): `GET /internal/jobs/outbox-sweep` with `Authorization: Bearer <CRON_SECRET>` and the bypass header.
4. On the free plan the sweep runs once a day (Vercel Hobby), so a backlog can wait up to a day. That's a free-plan limit, not a fault.

## Pub/sub outage
`RealtimePublishFailing`: Ably refuses more than 5% of publishes for 10 minutes.

Clients drop to polling (FR-005, FR-011 fallbacks), and the outbox keeps every event for the sweep.
1. Check Ably's status page and the app's usage in the Ably console. `rate_limited` results mean a plan limit (the free plan: 6M messages a month, test-plan §3).
2. Log lines `realtime publish failed: path=… code=…` give Ably's error code: `40101`/`40160` mean the key was revoked or lost a capability (ST-10 does this on purpose); `timeout` means Ably is slow or unreachable.
3. When Ably is back, the inline publishes recover by themselves and the sweep sends what waited. See [outbox backlog](#outbox-backlog).

## Reconcile
`ReconcileMismatch`, `ReconcileFailed`, `ReconcileLagging` (reports §9).

The nightly reconcile rebuilds each restaurant's daily sales from its closed bills.
- **Mismatch:** a day's tally didn't match its bills. The rebuild has already put the figures right, but a mismatch means a bug: some bill close didn't update the tally. Find `sales reconcile mismatch: restaurant_id=… date=…` in the logs, then the bill closes and corrections of that restaurant on that date. Raise a defect (test-plan §8).
- **Failed:** the reconcile failed for a restaurant; `sales reconcile failed: restaurant_id=… error=…` names the error's type. The next run retries it.
- **Lagging:** some restaurant's sales are reconciled more than 2 days behind. Check that the job ran (Vercel → Cron Jobs; once a day on Hobby), then look for repeated failures.

The alert stays on while the instance that saw it keeps running, and clears when that instance stops. That's how a count from one run shows up from a serverless instance.

## Rate limits off
`RateLimitFailingOpen`: Upstash doesn't answer, so the service's own rate limits are off (ADR-0008: fail open).

Every request is still served. The Vercel WAF rule still limits `/v1/auth/*` per IP, and the lock after failed sign-ins lives in PostgreSQL, so sign-in guessing stays limited. Per-session, per-user and per-restaurant limits are off.
1. Check Upstash's status page and the database's console: the free plan's command allowance used up, or a rotated token.
2. `reason="timeout"` means Upstash is slower than the 200 ms timeout; `reason="error"` means it refused or the connection failed.
3. If it lasts and abuse shows in the logs, tighten the WAF rule for the duration.

## Database saturation
`DatabasePoolWaiting`: requests have waited for a database connection for 5 minutes (architecture §9).

Each instance has its own small pool (10 connections, and a request waits at most 2 s for one; ADR-0004). Waiting means queries hold connections too long, or more instances than Neon's pooler serves well.
1. *Database and rate limits* dashboard: which pool waits, on how many instances. One instance means a slow request there; all of them means the database.
2. Neon console: CPU, connections, and whether the compute is suspended or scaling.
3. The logs show statements cut off by the 5 s `statement_timeout` as 5xx; their route says which query.
4. A deploy just before? Roll back as in [SLO burn](#slo-burn).
