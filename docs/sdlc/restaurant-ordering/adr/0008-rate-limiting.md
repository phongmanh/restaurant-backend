# ADR-0008: Rate limiting at the edge (Vercel WAF) and in a shared Redis

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Proposed (Redis provider and WAF rule limits confirmed in S-1) | 2026-10-13 | Manh Nguyen (tech lead) | NFR-SEC-04, NFR-SCAL-02, FR-001, FR-003, FR-008 |

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

## Decision
Option 2. A managed serverless Redis in the same region (e.g. Upstash via the Vercel Marketplace). Limits come from requirements ASSUMPTION-4 and are configurable. If Redis is unavailable, app-level limits fail open (WAF still applies) and an alert fires.

## Consequences
- Positive: fairness between restaurants; abuse control per session.
- Negative: an extra managed service and secret.
- Follow-ups: 429 responses use problem details with `Retry-After`; load test with a noisy tenant (NFR-SCAL-02).
