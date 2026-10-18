# CR-015: Production shares staging's Upstash database on the free plan

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-015 | Approved | 2026-10-18 | Manh Nguyen (product owner), choosing "share with staging" at release step 4 | Manh Nguyen (product owner) | architecture.md v1.7 (§7 service-to-service credentials, §8 environments) |

## Summary
Release 1's free production uses staging's Upstash Redis database for its rate-limit counters, with its own key prefix. The service puts its environment in front of every key it writes there (`production:rl:…`, `staging:rl:…`), so the two environments' counters never meet. Both environments use the one Upstash token, an exception to the key policy for this database only.

## Why
- **Upstash's free plan allows one database per account**, and staging already holds it. A second account needs a second email address. The owner's sign-ins all use one address, and they chose to share (2026-10-18).
- **The keys hold no environment today.** They are `rl:<policy>:<subject>:<window>`, and some subjects are IP addresses (`qr.present_ip`, the sign-in limits). Shared as they are, a load run on staging would spend production's per-IP allowance for the same IP, and the other way round.
- Architecture §7 says the API reaches Redis with **per-environment credentials**, and §8 gives production its own Redis. Both are baselined, so the change needs this CR.

## Proposed change
1. **Code:** `UpstashRateLimiter` takes the environment (`APP_ENV`) and prefixes every key with it: `<environment>:rl:<policy>:<subject>:<window>`. `LocalRateLimiter` (dev and test, in memory) needs no prefix. A test checks the key Upstash receives. On the next deploy staging's counters start from zero once, which changes nothing a user sees: windows last at most an hour.
2. **Architecture (v1.7 → v1.8):**
   - §7: "per-environment credentials" gains an exception: "on this learning project's free plans, production and staging share one Upstash database and token (CR-015); each environment's keys carry its name".
   - §8: the free-plans paragraph says the same for production.
3. **deploy/README.md:** the key policy names this exception. Rotating `UPSTASH_REDIS_REST_TOKEN` now means sending the new token to **both** projects, then deploying both, before revoking the old one.
4. **release-checklist.md §4:** production's Upstash row says "staging's database, prefix `production:`".
5. **STATUS:** a new risk R-22 (below).

## Impact
| Item | Change | When |
|---|---|---|
| `RateLimitStores.kt`, `AuthModule.kt` | The prefix, with a test | On approval, in the production-setup PR |
| architecture.md | v1.8: §7, §8 | On approval |
| deploy/README.md | Key policy exception; rotation of the shared token | On approval |
| release-checklist.md | §4 Upstash row | On approval |
| Contract, requirements, designs, cost | None | — |

## What stays the same
- The limits themselves, fail-open behaviour and the WAF backstop (ADR-0008, core-and-auth §11).
- Every other secret: production keeps its own database passwords, signing keys, cron secret and bypass secret.
- A launch with real customers gets its own Redis on a paid or separate plan: this CR covers the free release only.

## Risks
**R-22 (new):** if staging's Upstash token leaks, then whoever holds it can also read and reset production's rate-limit counters, which turns production's app-level limits off. They could also spend the free plan's monthly command allowance, after which every limit fails open in both environments.
- Likelihood M: staging's env file was already printed into a chat once (2026-10-18), and its passwords were rotated.
- Impact L: production holds test data only, the WAF rule still limits `/v1/auth`, and failed sign-ins still lock the account in PostgreSQL.
- Mitigation: rotate the token in both projects on any exposure. `RateLimitFailingOpen` alerts when the limits fail open.

The free plan's allowance is also shared, so a long staging load run takes from production's budget. Run load on staging only between releases, with production's watch period over.

## Owner decisions needed
1. Approve, reject or defer this CR.
2. Accept R-22 for release 1.

## Cost and schedule
No cost. A small code change with a test, plus the document edits, in the production-setup PR before production's first deploy (release step 8).

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-18 | Approved | Manh Nguyen (product owner) | "approve", which also accepts R-22. Applied the same day: the key prefix in `UpstashRateLimiter`, architecture v1.8, the key policy, release-checklist §4 |
