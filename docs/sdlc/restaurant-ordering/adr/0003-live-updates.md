# ADR-0003: Live updates through managed pub/sub, with a PostgreSQL event log for catch-up

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Accepted (S-3 met its exit criterion on Ably); provider Ably, Pro plan at launch. **Deferred for release 1** (CR-013, 2026-10-18): release 1 has no live push and clients poll the event log; the provider is decided before the release that adds live push | 2026-10-15 (proposed 2026-10-13) | Manh Nguyen (product owner, tech lead) | FR-005, FR-008, FR-011, NFR-PERF-03, NFR-REL-03, NFR-SCAL-01, NFR-PRIV-01 |

## Context
About 17,500 clients (52,500 after 3× growth) must see order changes within 2 s at p95, without missing any. Vercel functions can't hold that many long-lived WebSocket/SSE connections (ADR-0002), and a notification transport alone can't guarantee "no gaps".

## Options
1. **Managed pub/sub (e.g. Ably, Pusher Channels, a hosted Centrifugo) for thin notifications + a PostgreSQL event log as the source of truth**
   - Pros: scales connections off-platform; the event log makes delivery gap-free and auditable; payloads carry no personal data.
   - Cons: a vendor dependency and its cost.
2. **Client polling only**
   - Pros: no vendor.
   - Cons: about 17,500 clients polling every 2 s is roughly 8,750 req/s, about 30× the planned load, for a 2 s delay.
3. **SSE served by the Ktor functions**
   - Pros: no vendor.
   - Cons: depends on Vercel's maximum duration and streaming support for the JVM runtime; every open stream holds an instance.

## Decision
Option 1:
- **Event log.** Every state change writes a `domain_events` row (restaurant, per-restaurant `seq`, session ID if any, type, aggregate ID and version) in the business transaction. `seq` comes from a per-restaurant counter, so commit order equals sequence order.
- **Outbox.** An `outbox` row is written in the same transaction. The request publishes it after commit, and a cron sweep retries.
- **Channels.** `restaurant.<id>.staff` and `session.<id>`. Notifications carry only `{seq, type, id, version}`. Clients then call the catch-up endpoint (`after=<lastSeq>`) to fetch the changes.
- **Tokens.** The API mints subscribe-only tokens for exactly one channel.
- **Fallback.** If pub/sub is unavailable, clients poll the catch-up endpoint (interval set in Phase 3).

**Provider: Ably**, chosen through S-3 (2026-10-14, `spikes/s3-pubsub/README.md`):
- Delay: publish → receive p95 47–49 ms from 50 to 190 subscribers, flat (target ≤ 1 s); 0 of 18,646 deliveries of accepted publishes lost.
- Isolation: a subscribe-only token for one channel was refused on every other channel and action (9/9).
- Region: Ably's Singapore endpoint (`ap-southeast-1`), next to `sin1`. Notifications carry no personal data, so TBD-10 affects it less than the database, but the region is still reviewed with it.
- Cost: Pro ($399 + usage, 50k connections, 1,447 REST requests/s) ≈ $970/month at launch; 3× (52,500 connections) needs Enterprise (cost-estimate.md).
- Not measured: more than 190 subscribers (free plan). Accepted as residual risk R-13, closed by a load test on a paid plan before launch.

**Release 1 (CR-013, 2026-10-18).** The owner chose to launch without live push, mainly for cost (Ably is ≈ 55–60% of the launch estimate). The event log, catch-up endpoints and gap-free numbering stand as decided; the fallback above becomes the normal mode, every 15 s for staff and 60 s for guests (design/realtime.md §7). With `LIVE_PUSH=off` no outbox row is written. The Ably adapter stays behind the `RealtimePublisher` port, so adding push later is a configuration change plus a CR that restores NFR-PERF-03's 2 s.

## Consequences
- Positive: meets NFR-REL-03 independent of the transport; the vendor can be swapped behind the `RealtimePublisher` port; no personal data leaves for the vendor.
- Negative: one extra round trip per change (notification → fetch); a per-restaurant counter row serialises event writes per restaurant (≈ 1–2 writes/s per restaurant at peak, acceptable).
- Follow-ups: Phase 3 designs the catch-up endpoint, page size and polling interval; purge events after 7 days.
- Follow-ups from S-3 (Phase 3): a refused publish (42918, rate limit) never fails the request; the request retries it once or twice with a short backoff and otherwise leaves it to the sweep; publishing happens after the response is sent or under a tight timeout, so the ≈ 45 ms publish call isn't added to the request; one batch publish per event covers both channels.
