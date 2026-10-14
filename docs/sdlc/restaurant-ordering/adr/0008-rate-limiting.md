# ADR-0008: Rate limiting at the edge (Vercel WAF) and in a shared Redis

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Accepted 2026-10-14 by the owner (S-1 Q9–Q12, `spikes/s1-vercel/README.md`) | 2026-10-13 | Manh Nguyen (tech lead) | NFR-SEC-04, NFR-SCAL-02, FR-001, FR-003, FR-008 |

## Context
Ktor's RateLimit plugin keeps counters in memory per instance. On serverless, requests spread over many short-lived instances, so per-instance counters don't limit anything. The limits needed are per IP (QR scans), per table session (orders, waiter calls), per staff user and per restaurant (fairness).

## Options
1. **Vercel WAF rate-limit rules only**
   - Pros: no code.
   - Cons: keyed by IP or headers, not by session or restaurant.
2. **WAF for IP and path limits + a Redis fixed-window/token-bucket limiter behind a `RateLimiter` port** for session, user and restaurant keys
   - Pros: covers every key.
   - Cons: an extra store.
3. **Counters in PostgreSQL**
   - Pros: no new store.
   - Cons: adds write load to the bottleneck.
4. **WAF for IP and path limits + Vercel WAF rate limiting called from code for session, user and restaurant keys** (added 2026-10-14). `@vercel/firewall`'s `checkRateLimit` is a plain HTTPS request to the deployment's own host, so the service can send it from Kotlin with any key
   - Pros: covers every key with no extra vendor, secret or cost line; the counters are Vercel's.
   - Cons: documented only through the JS package, so the contract could change (kept behind the `RateLimiter` port); each rule has one fixed limit and window, set in the firewall config, not per restaurant at runtime; Pro allows 40 rate-limit rules per project (Hobby 1); counters are per region; the answer is only allowed or limited, with no count or reset time for `Retry-After`. S-1 Q11 measures it.

## Decision
Option 2. A managed serverless Redis in the same region (Upstash for Redis via the Vercel Marketplace, `ap-southeast-1`). Limits come from requirements ASSUMPTION-4 and are configurable. If Redis is unavailable, app-level limits fail open (WAF still applies) and an alert fires.

Confirmed by S-1 on 2026-10-14 (`spikes/s1-vercel/README.md`, Results Q9–Q12):
- **Per IP at the edge (Q9):** a WAF rate-limit rule answers 429 past its limit before the request reaches the function, and lets requests through in the next window. The edge's 429 carries no `Retry-After`.
- **Per key in Upstash (Q10):** one `EVAL` over REST, check time p50 5.7 · p95 10.9 · p99 19.3 ms inside the function. The owner accepted p95 10.9 ms against the proposed 10 ms (2026-10-14). Counting is exact across up to 22 instances.
- **Option 4 rejected (Q11):** the WAF called from Kotlin counts correctly too, but its check is p95 28.8 ms (2.6× Upstash), gives no count or reset time for `Retry-After`, and allows one fixed limit per rule.
- **Fail open (Q12):** a dead store costs the timeout (200 ms), an error one round trip; every request is served.
- **Design rules from the spike:** open the Upstash connection at instance startup, before serving: a new instance's first check took longer than the 200 ms timeout and failed open, letting up to one extra request per new instance through a burst. Fixed windows allow up to 2× the limit across a window boundary; keep them unless a limit proves to need a sliding window.

## Consequences
- Positive: fairness between restaurants; abuse control per session.
- Negative: an extra managed service and secret.
- Follow-ups: 429 responses use problem details with `Retry-After`; load test with a noisy tenant (NFR-SCAL-02).
- Addition (2026-10-14, CR-002): per-IP limits in Redis and the WAF on `/v1/auth/*` (sign-in, TOTP step, refresh, accept invitation, reset). The per-account lock after failed sign-ins is kept in PostgreSQL instead (architecture §7), so it still holds when Redis is down and these limits fail open.
