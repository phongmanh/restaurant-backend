# ADR-0003: Live updates through managed pub/sub, with a PostgreSQL event log for catch-up

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Proposed (provider chosen by spike S-3) | 2026-10-13 | Manh Nguyen (tech lead) | FR-005, FR-008, FR-011, NFR-PERF-03, NFR-REL-03, NFR-SCAL-01, NFR-PRIV-01 |

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

The provider is chosen in S-3 on delay, isolation, cost at 17,500 and 52,500 connections, and region.

## Consequences
- Positive: meets NFR-REL-03 independent of the transport; the vendor can be swapped behind the `RealtimePublisher` port; no personal data leaves for the vendor.
- Negative: one extra round trip per change (notification → fetch); a per-restaurant counter row serialises event writes per restaurant (≈ 1–2 writes/s per restaurant at peak, acceptable).
- Follow-ups: S-3; Phase 3 designs the catch-up endpoint, page size and polling interval; purge events after 7 days.
