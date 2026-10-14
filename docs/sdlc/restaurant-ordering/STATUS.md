# STATUS: Restaurant ordering service

> The control file for the Ktor SDLC workflow (`ktor-sdlc` skill). Claude reads it first to resume work.
>
> Keep it current. It's the single place for gate decisions, risks and open items.

| Field | Value |
|---|---|
| Initiative | Restaurant ordering service (`docs/sdlc/restaurant-ordering/`) |
| Type | New service |
| Service | restaurant-backend, repository https://github.com/phongmanh/restaurant-backend (private) |
| API consumers | Customer mobile app (Android and iOS; oldest supported 1.0.0, old versions kept 12 months) · customer web app · staff app (web) · admin console |
| Toolchain | Kotlin 2.4.x · Ktor 3.6.x · JDK 25 · Exposed latest stable · PostgreSQL 17+ (pinned at Phase 4 start) |
| Module layout | One module, packages by feature (ADR-0001) |
| Deployment platform | Vercel Functions, region `sin1` (ADR-0002, Accepted with conditions: paid-plan cold-start check before G5 (R-12), production on Pro, region subject to TBD-10; CR-001 to move off Vercel deferred); managed PostgreSQL + pooler (ADR-0004), managed pub/sub (ADR-0003), Upstash Redis (ADR-0008). Regions subject to TBD-10 |
| Environments | dev · preview (per PR) · staging · production |
| OpenAPI contract | `docs/api/openapi.yaml` (from Phase 3) |
| Security level | ASVS L2 |
| Compliance | Vietnam PDP Law (in force 2026-01-01), Decree 356/2025/ND-CP (replaced Decree 13/2023, CR-003); accounting record retention; data residency TBD-10. No PCI-DSS (no payments) |
| SLOs | Agreed at G1: 99.9% success per 28 days; order flow 99.9%; guest reads p95 ≤ 200 ms; live updates ≤ 2 s p95; RPO ≤ 5 min, RTO ≤ 1 h |
| Product owner / Tech lead / Ops owner | Manh Nguyen, product owner / Manh Nguyen, tech lead / TBD |
| Current phase | 2 Architecture |
| Tailoring | Full (service) |
| Last updated | 2026-10-15 |

## Deliverables
| Phase | Deliverable | File | Status (Not started / Draft / In review / Approved) | Version |
|---|---|---|---|---|
| 1 | Requirements | requirements.md | Approved (v1.2 through CR-003) | 1.2 |
| 1–6 | RTM | rtm.md | Draft (design column added in Phase 2; FR/NFR rows baselined at G1) | 1.1 |
| 2 | Architecture + threat model + capacity model | architecture.md | Draft, ready for G2 review (v0.3 2026-10-15: spike results in §17, risk table in §16 synced with STATUS, baseline v1.2) | 0.3 |
| 2+ | ADRs | adr/0001–0009, 0011 | 10 Accepted (0001, 0002 with conditions, 0003, 0004, 0005, 0006, 0007, 0008, 0009, 0011); 0010 reserved by CR-001 | — |
| 2 | Monthly cost estimate (TBD-11) | cost-estimate.md | Draft (Ably line confirmed by S-3; for the owner's review) | 0.2 |
| 1–5 | Test plan + summary report | test-plan.md | Draft master plan (§1 and §5 approved at G1) | 0.4 |
| 3 | Feature designs | design/<feature>.md | Not started | — |
| 6 | Release checklist | release-checklist.md | Not started | — |

The OpenAPI contract is baselined at G3 with the feature designs, but lives in the code repository (`docs/api/openapi.yaml`), where CI checks it.

## Gate log
Decision is one of: Approved / Approved with conditions / Rejected / Waived.

| Gate | Date | Decision | Approver (name, role) | Conditions or accepted risk | Tag |
|---|---|---|---|---|---|
| G1 Requirements baseline | 2026-10-13 | Approved | Manh Nguyen, product owner and tech lead | None. Open before G2: TBD-1, TBD-2, TBD-3, TBD-4; TBD-10 blocks G2 | `sdlc/restaurant-ordering/G1` on `149f7a4`. Nothing was committed at G1 time, so this commit also holds the Phase 2 drafts. The G1 baseline is requirements.md v1.0 in it, unchanged since approval; rtm.md and test-plan.md there already include Phase 2 additions |
| G2 Architecture baseline | | | | | |
| G3 Ready to build | | | | | |
| G4 Code complete | | | | | |
| G5 Release readiness | | | | | |
| G6 Launch accepted | | | | | |

## Decisions
| Date | Decision | Recorded in (ADR / minutes) |
|---|---|---|
| 2026-10-13 | New standalone service in its own repository (`restaurant-backend`), full tailoring | Scope session |
| 2026-10-13 | Multi-restaurant SaaS: every resource belongs to one restaurant (tenant) | Scope session |
| 2026-10-13 | Guests are anonymous: the table QR code gives a table session; no guest accounts | Scope session |
| 2026-10-13 | No online payment in release 1; bills are settled at the counter and recorded by staff | Scope session |
| 2026-10-13 | Release 1 scope: guest ordering, menu, tables & QR, live kitchen/staff flow, bills & daily reports | Scope session |
| 2026-10-13 | Deploy on Vercel (stakeholder has deployed Ktor there); Phase 2 ADR to confirm the runtime model | Scope session |
| 2026-10-13 | Vietnam PDP rules apply; ASVS L2 | Scope session |
| 2026-10-13 | Menu prices include VAT; no separate VAT line on the bill (TBD-6) | G1 review |
| 2026-10-13 | NFR targets and peak sizing confirmed (ASSUMPTION-1/2) | G1 review |
| 2026-10-13 | One module, packages by feature | ADR-0001 |
| 2026-10-13 | Tenant isolation: restaurant ID in services + PostgreSQL RLS | ADR-0005 |
| 2026-10-13 | Guests authenticate with signed QR tokens and table-session JWTs issued by the service | ADR-0006 |
| 2026-10-13 | Hand-written OpenAPI contract validated by contract tests (no experimental generation) | ADR-0007 |
| 2026-10-13 | Metrics and traces pushed over OTLP | ADR-0009 |
| 2026-10-13 | S-1 failed the cold-start criterion; raise CR-001 to move the backend to a container platform (ADR-0002 option 3) rather than accept the risk | CR-001 |
| 2026-10-13 | CR-001 deferred: keep Vercel. S-1 ran on a free Hobby account; Pro keeps one production instance warm ("scale to one"), which S-1 didn't test | CR-001 |
| 2026-10-13 | S-2 design: create the `app` role with SQL, not in the Neon console (console roles join `neon_superuser`, which has BYPASSRLS); RLS policies use `nullif(current_setting('app.restaurant_id', true), '')::uuid` so a missing tenant gives 0 rows; the tenant is set with `set_config(…, true)` (`SET LOCAL` with a bind parameter). Folded into ADR-0004/0005 on 2026-10-14 | `spikes/s2-postgres/README.md` |
| 2026-10-13 | S-3 runs on Ably's free plan (200 connections) and extrapolates delay to 1k–5k subscribers rather than paying for a 10k plan. The gap is a residual risk, closed by the Phase 5 load test on a paid plan | `spikes/s3-pubsub/README.md` |
| 2026-10-14 | S-3 deferred; the spike is built and waits in `spikes/s3-pubsub/`. It still blocks G2 | `spikes/s3-pubsub/README.md` |
| 2026-10-14 | Learning project: every service stays on its free plan; no paid plans for now. Checks that need a paid plan (S-1 warm production instance, S-3 beyond 200 connections, Phase 5 load test) become residual risks, not to-dos | Owner |
| 2026-10-14 | Staff and admins sign in with accounts built into the service (email, password, TOTP for admins), not an external identity provider. Raised CR-002 to change requirements v1.0 | [CR-002](cr/CR-002-built-in-staff-accounts.md), ADR-0011 |
| 2026-10-14 | CR-002 approved: requirements v1.1, ADR-0011 Accepted | [CR-002](cr/CR-002-built-in-staff-accounts.md) |
| 2026-10-14 | Database pool per instance: up to 10 connections (was 3), 2 s wait for a connection then 503, 5 s `statement_timeout` for `app`. S-2 run 3 on `sin1`: 50 concurrent 2 s transactions 2.3–2.8 s warm (6.2 s with 3), order p95 22.6 ms at 10 concurrent | ADR-0004, `spikes/s2-postgres/README.md` |
| 2026-10-14 | Argon2id library for staff passwords: Bouncy Castle (`bcprov-jdk18on`), not Password4j. S-4 on Vercel `sin1`: p95 85 vs 130 ms, less memory | `spikes/s4-auth/README.md`, architecture §7 |
| 2026-10-14 | S-2 results folded into the design: ADR-0004 names Neon in `ap-southeast-1` and records the role setup, pooler limits and measured latency; ADR-0005 records the verified RLS mechanics; architecture §3, §6, §12 updated. ADR-0004 stays Proposed until the owner accepts it (region still subject to TBD-10) | ADR-0004, ADR-0005 |
| 2026-10-14 | Rate-limit check run as part of S-1 (Q9–Q12) to settle ADR-0008 on free plans: a WAF per-IP rule, per-key limits in Upstash Redis (free, Singapore) and in Vercel's WAF called from Kotlin (new option 4, which would drop Redis), and fail-open. Exit criteria proposed in the spike README for the owner to confirm | `spikes/s1-vercel/README.md`, ADR-0008 |
| 2026-10-14 | ADR-0004 Accepted by the owner: Neon PostgreSQL in Singapore behind its transaction-mode pooler; the region stays subject to TBD-10. S-2 closed: the `cold` sample (Neon waking from scale to zero) isn't run and is accepted as a free-plan residual risk (R-11) | ADR-0004 |
| 2026-10-14 | S-1 Q10: the owner holds the Upstash Redis check time (p95 10.9 ms against the proposed 10 ms) until Q11 (Vercel WAF called from Kotlin) has run; ADR-0008's store is chosen by comparing the two | `spikes/s1-vercel/README.md` (Results, Q10) |
| 2026-10-14 | ADR-0008 store chosen by the owner: option 2, Upstash for Redis (free, Singapore) for per-key limits, the Vercel WAF for per-IP limits; Upstash's check time p95 10.9 ms accepted against the proposed 10 ms. Option 4 (WAF called from Kotlin, p95 28.8 ms) rejected. Design rule: connect to the store at startup | ADR-0008 Decision; `spikes/s1-vercel/README.md` |
| 2026-10-14 | ADR-0008 Accepted by the owner: Vercel WAF rules for per-IP limits, Upstash for Redis (Singapore) for per-session, per-user and per-restaurant limits, failing open with an alert | ADR-0008 |
| 2026-10-14 | S-4 closed by the owner: exit criterion met (warm sign-in p95 86 ms against ≤ 500 ms; 19 of 19 token cases as specified) | `spikes/s4-auth/README.md` |
| 2026-10-14 | S-1 closed by the owner on the free Hobby account's results: warm p95 ≈ 49 ms at the edge meets; cold starts of 5–7 s fail there. The checks that need a paid plan (does the production instance stay warm, how often scale-out cold-starts) aren't run and are accepted as residual risk R-12 | `spikes/s1-vercel/README.md`; R-12 |
| 2026-10-14 | ADR-0002 Accepted with conditions by the owner: the Ktor JDK 25 image on Vercel's container runtime in `sin1`. Conditions: (1) before G5, a check on the production Pro account shows NFR-PERF-01/02 p99 hold with real cold starts (R-12), otherwise CR-001 reopens; (2) production runs on Vercel Pro; (3) the region stays subject to TBD-10 | ADR-0002 |
| 2026-10-14 | S-5 closed by the owner: exit criterion met (31-day report from `daily_sales` p95 10.4 ms in PostgreSQL, 76 ms on a cold cache, against ≤ 3 s) | `spikes/s5-reports/README.md` |
| 2026-10-14 | Report design accepted by the owner (S-5): (1) keep `daily_sales`, as four tables (per day; per payment method; per table; per item), and never compute reports from the order rows; (2) update it in the bill-close transaction, upserting rows in key order so concurrent closes can't deadlock (≈ +2 ms per close); (3) rebuild each restaurant's previous day every night (delete the day, insert it again from the raw rows), which is idempotent and repairs missed updates; (4) a bill counts on the restaurant's local day on which it was closed (ASSUMPTION-11) | architecture §4, §9; `spikes/s5-reports/README.md` |
| 2026-10-14 | ASSUMPTION-8 confirmed by the owner: quality goals ranked isolation > order integrity > availability > live-update latency > operability/cost | architecture §1 |
| 2026-10-14 | TBD-2 resolved by the owner: the customer mobile app ships on Android and iOS; the staff app is web only in release 1 (refresh token in the `HttpOnly` cookie, no store app) | architecture §4, §7 |
| 2026-10-14 | TBD-3 resolved by the owner: old customer app versions keep working for 12 months (`/v1` kept alongside `/v2` with `Deprecation`/`Sunset` headers for that long) | architecture §5 |
| 2026-10-14 | TBD-4 working values set by the owner, pending the legal review: orders and bills 10 years, the last 2 in PostgreSQL and older ones archived nightly to object storage as compressed write-once files; audit log 1 year; staff anonymised 30 days after deactivation; erasure on request within 72 h | architecture §6, §11 |
| 2026-10-14 | CR-003 approved by the owner: requirements v1.2 records TBD-2, TBD-3 and ASSUMPTION-11 and cites Decree 356/2025 instead of Decree 13/2023; architecture §1 citation corrected | [CR-003](cr/CR-003-record-resolved-items.md) |
| 2026-10-14 | S-3 resumed by the owner (Ably key added) and run the same day | `spikes/s3-pubsub/README.md` |
| 2026-10-15 | S-3 closed by the owner: exit criterion met (publish → receive p95 47–49 ms against ≤ 1 s; isolation 9/9) | `spikes/s3-pubsub/README.md` |
| 2026-10-15 | R-13 accepted by the owner as a free-plan residual risk: Ably above 190 subscribers and at Pro's rate limits is not measured; a load test on a paid plan before launch closes it | Risks |
| 2026-10-15 | ADR-0003 accepted by the owner: live updates through Ably (Pro at launch) with the PostgreSQL event log for catch-up, plus the S-3 follow-ups (in-request retry of refused publishes, publishing off the request path, batch publish per event) | [ADR-0003](adr/0003-live-updates.md) |

## Risks
| ID | Risk ("If …, then …") | Likelihood (H/M/L) | Impact (H/M/L) | Owner | Mitigation | Status |
|---|---|---|---|---|---|---|
| R-01 | If Vercel's runtime limits (execution time, long-lived connections, cold starts, DB connection counts) don't fit a JVM Ktor service with live updates for ~17,500 clients, then NFR-PERF-03/SCAL-01 can't be met as designed | M | H | Manh Nguyen | Live updates moved off the functions to managed pub/sub + event-log catch-up (ADR-0003); spike S-1 measures cold start, concurrency and limits; AOT cache or native image if needed; a CR if the platform must change | Open: S-1 measured 5–7 s cold starts on a free Hobby account (production scales to zero); CR-001 deferred. S-1 closed on the free-account results; the paid-plan check (warm production instance, scale-out cold-start rate) is R-12, a condition of ADR-0002 before G5 |
| R-02 | If someone photographs a table's QR code and uses it away from the table, then they can add orders to strangers' bills or prank the kitchen | M | M | Manh Nguyen | QR rotation (FR-031), per-session rate limits (NFR-SEC-04), staff can cancel; optional waiter confirmation (FR-016, TBD-8); session closes at payment | Open |
| R-03 | If any endpoint misses a tenant or session check, then one restaurant's data leaks to another, harming every customer | M | H | Manh Nguyen | Tenant ID on every row and query (or PostgreSQL RLS, Phase 2 ADR); authorisation-matrix tests per endpoint (NFR-SEC-02); review checklist | Open |
| R-04 | If Vietnamese law requires local storage of this data, then Vercel's and the database's regions may not comply | M | H | Manh Nguyen | Legal review before G2 (TBD-10); choose regions accordingly; a CR if the platform must change | Open |
| R-05 | If the NFR numbers (ASSUMPTION-1/2) are far off, then the capacity model and the costs are wrong | M | M | Manh Nguyen | Confirmed at G1; measure the pilot and revisit in the first SLO review | Open |
| R-06 | If the functions reach the database, Redis, pub/sub and storage over the public internet, then leaked credentials are directly usable | M | H | Manh Nguyen | TLS; least-privilege users; rotation; secret scanning. Source-IP allow-lists are not possible: Vercel offers no static IPs for container images (Q-1) | Open |
| R-07 | If no operations owner and on-call are named before launch, then incidents at dinner peak go unhandled | M | H | Manh Nguyen | Name an ops owner before G5; runbooks per architecture §13 | Open |
| R-08 | If the pub/sub vendor fails or gets expensive, then live updates degrade or costs rise | M | M | Manh Nguyen | Thin events; polling fallback; `RealtimePublisher` port (ADR-0003) | Open |
| R-09 | If many serverless instances open connections at once, then the database saturates during scale-out | M | H | Manh Nguyen | Transaction pooler; pool up to 10 per instance (2 s wait, 5 s statement timeout); cold-burst load profile (ADR-0004) | Open |
| R-10 | If our own sign-in code has a flaw (weak hashing, no brute-force limit, token mishandling), then staff or admin accounts can be taken over | M | H | Manh Nguyen | CR-002 design: Argon2id, vetted Argon2 and JWT libraries, rate limits (ADR-0008), rotating refresh tokens, admin TOTP; ASVS V6/V7 checklist in Phase 3; tests in the test plan | Open |
| R-11 | If the production database compute scales to zero when idle, then the first request after a quiet spell waits for it to wake, and that wait was never measured (S-2's `cold` sample skipped; the free plan always suspends after 5 min idle) | L in production (Launch plan or above can turn scale to zero off); H on the free plan | M | Manh Nguyen | Production on Neon's Launch plan or above with scale to zero off; measure the wake time with `spikes/s2-postgres` `cold` if a paid plan is ever used | Accepted as a free-plan residual risk (owner, 2026-10-14) |
| R-12 | If, on the paid production plan, the production instance doesn't stay warm through quiet spells or scale-out at dinner peak often starts new instances (S-1 on the free account: 5–7 s per cold start; 50 concurrent requests opened 14 instances and ~10 requests waited ~7 s), then guest p99 targets (NFR-PERF-01/02: 500/800 ms) fail; this was never measured on a paid plan | M | H | Manh Nguyen | Condition 1 of ADR-0002: before G5, on the production Pro account, measure the warm instance over a quiet spell and the cold-start rate under the cold-burst load profile against p99; JDK AOT cache (≈ 1–1.4 s faster); reopen CR-001 if p99 fails | Accepted as a free-plan residual risk (owner, 2026-10-14) |
| R-13 | If Ably's delay rises with load beyond what S-3 could test on the free plan (190 subscribers, 50 REST requests/s), then live updates miss NFR-PERF-03 (≤ 2 s p95) at 17,500 connections, or publishes get refused at peak | L (delay flat at p95 47–49 ms from 50 to 190 subscribers; Pro allows 1,447 requests/s against ≈ 75 needed at peak) | M | Manh Nguyen | Outbox + sweep retries refused publishes (ADR-0003); in-request retry of 42918 with backoff; batch publish per event; event-log catch-up covers gaps; load test on a paid plan before launch | Accepted as a free-plan residual risk (owner, 2026-10-15) |

## Change requests
| CR ID | Title | Status | Affected items | Decision date |
|---|---|---|---|---|
| [CR-001](cr/CR-001-backend-container-platform.md) | Run the backend on a container platform instead of Vercel Functions (S-1: cold starts 5–7 s) | Deferred: keep Vercel; reopen if cold starts on a paid plan break p99 | requirements.md v1.0 (constraints, §6), ADR-0002 → ADR-0010, ADR-0003/0004/0008/0009, architecture.md, test-plan.md, rtm.md | |
| [CR-002](cr/CR-002-built-in-staff-accounts.md) | Staff sign in with accounts built into the service, not an external identity provider | Approved | requirements.md v1.0 (consumers, FR-010, FR-040, FR-041, data and integrations tables), ADR-0011, ADR-0006/0008, architecture.md, test-plan.md, rtm.md | 2026-10-14 |
| [CR-003](cr/CR-003-record-resolved-items.md) | Write the resolved TBD-2, TBD-3 and ASSUMPTION-11 into the requirements and correct the decree citation (Decree 13 → 356/2025) | Approved | requirements.md v1.1 (§2, FR-050, NFR-COMPAT-01, §7, §9), architecture.md §1, STATUS.md | 2026-10-14 |

## Open items
A TBD that blocks a Must requirement blocks the gate.

| ID | Type (TBD / ASSUMPTION / Question) | Description | Blocks gate? | Owner | Status |
|---|---|---|---|---|---|
| ASSUMPTION-1 | ASSUMPTION | Peak sizing: 500 restaurants × 25 tables × 60% × 2 devices ≈ 15,000 guest devices; ≈ 300 req/s; 3× in 12 months | No | Manh Nguyen | Confirmed at G1 |
| ASSUMPTION-2 | ASSUMPTION | All NFR targets in requirements §4 | No | Manh Nguyen | Confirmed at G1 |
| ASSUMPTION-3 | ASSUMPTION | One staff member works in one restaurant; one restaurant is one branch | No | Manh Nguyen | Open |
| ASSUMPTION-4 | ASSUMPTION | Size limits (order lines, quantities, notes, rates, items, tables) | No | Manh Nguyen | Open |
| ASSUMPTION-5 | ASSUMPTION | Staff access tokens live ≤ 15 min | No | Manh Nguyen | Open |
| ASSUMPTION-6 | ASSUMPTION | Holding the current QR code is the only proof of presence at a table | No (R-02) | Manh Nguyen | Open |
| ASSUMPTION-7 | ASSUMPTION | No service charge on the bill in release 1 | No | Manh Nguyen | Open |
| ASSUMPTION-8 | ASSUMPTION | Quality goals ranked: isolation > order integrity > availability > live-update latency > operability/cost (architecture §1) | G2: confirm | Manh Nguyen | Confirmed 2026-10-14 by the owner |
| ASSUMPTION-9 | ASSUMPTION | Load mix per endpoint group and data volume (≈ 100k orders/day) in architecture §9 | No (calibrated by S-1 and the pilot) | Manh Nguyen | Open |
| ASSUMPTION-10 | ASSUMPTION | About 10 staff accounts per restaurant: ≈ 5,000 staff MAU at 500 restaurants, ≈ 15,000 at 3× growth; fewer than 10 platform admins (sizes staff sign-in load, ADR-0011) | No | Manh Nguyen | Open |
| ASSUMPTION-11 | ASSUMPTION | In the FR-050 report a bill counts on the restaurant's local day on which it was closed (paid), even if the guests sat down before midnight | No | Manh Nguyen | Confirmed 2026-10-14 by the owner (S-5 design point 4). Written into FR-050 by CR-003 (requirements v1.2) |
| Q-1 | Question | How was the earlier Ktor service deployed on Vercel? | No | Manh Nguyen | Resolved 2026-10-13: Vercel container runtime (`ktor-sample`); findings in ADR-0002 |
| S-1 | Spike | Ktor on Vercel's container runtime: cold start (plain/AOT), warm latency, concurrency, duration, SSE, CPU/memory, SIGTERM, platform features | No (closed) | Manh Nguyen | **Closed 2026-10-14** by the owner on the free-account results; the paid-plan checks are residual risk R-12, and ADR-0002 is Accepted with conditions. Run 2026-10-13, results in `spikes/s1-vercel/README.md`. Warm p95 ≈ 49 ms at the edge (meets). Cold start 5–7 s (plain p50 6.4–6.8 s, AOT p50 5.4 s; ~4.7 s is the platform, so AOT/native can't fix it) and the next request often cold-starts a second instance: **fails the cold-start criterion on the free account**; CR-001 deferred, Vercel kept. Still to measure on a paid plan: whether the production instance stays warm, and how often scale-out cold-starts hit (load test). Max duration and SSE cut at 300 s on the free Hobby account used for the spike (Pro: 800 s, still < 15 min), so ADR-0003 pub/sub stands. SIGTERM grace 30 s. Production on a Pro account (commercial use; Rolling Releases, log drains). Open: WAF check (Q9). **Rate-limit check added 2026-10-14 (Q9–Q12, settles ADR-0008)**: run 2026-10-14 on Vercel: Q10 Upstash (REST) check p95 10.9 ms, just over the proposed 10 ms; counting exact across instances, except new instances failing open on their first call (fix: connect at startup) and fixed-window boundaries; Q12 fail-open met. Q11 WAF from Kotlin: check p95 28.8 ms (about 3× the target), same first-call fail-open on new instances, so it doesn't replace Redis; neither store meets the proposed 10 ms, Upstash is closest; the owner chooses for ADR-0008. Q9 met: the edge WAF answered 429 from request 21 of 20/10 s per IP without reaching the function, and let requests through in the next window (no `Retry-After` on the edge's 429). The rate-limit check is complete; the owner chose Upstash (option 2) and accepted ADR-0008 on 2026-10-14 |
| S-2 | Spike | PostgreSQL through the pooler from `sin1`: latency, `SET LOCAL` RLS, transaction advisory locks, Flyway from CI | No (closed) | Manh Nguyen | Run 2 2026-10-13 from `sin1` against Neon Singapore (PostgreSQL 18.6): **exit criterion met**, order transaction over the pooler p95 28.7 ms (31.7 ms at 10 concurrent, incl. connection wait); ping p95 3.8 ms; RLS 10/10 and 0 leaks over 25 instances; advisory locks exclusive; prepared statements work through Neon's pooler. Q6 met: CI applied V2 through Flyway, and `app` is denied DDL. Run 3 2026-10-14: pool size decided (10 per instance, 2 s wait, 5 s statement timeout; ADR-0004). Results folded into ADR-0004/0005; ADR-0004 Accepted 2026-10-14. **Closed 2026-10-14**: the `cold` sample (Neon waking from scale to zero) is not run, accepted as a free-plan residual risk (R-11) (`spikes/s2-postgres/README.md`) |
| S-3 | Spike | Pub/sub provider: delay with 1k–5k subscribers, channel isolation, cost at 17.5k/52.5k connections | No (closed) | Manh Nguyen | **Closed 2026-10-15** by the owner; R-13 accepted and ADR-0003 Accepted (Ably). Run 2026-10-14, exit criterion met (`spikes/s3-pubsub/README.md`). Ably free plan, laptop in Vietnam → Ably Singapore (`ap-southeast-1`). Q1 publish → receive p95 47 / 47 / 49 ms at 50 / 100 / 190 subscribers (target ≤ 1 s), flat, so 1k–5k is extrapolated (R-13); 0 of 18,646 deliveries of accepted publishes lost. Q2 one channel, 190 subscribers: p95 97 ms. Q3 isolation 9/9: a one-channel subscribe-only token can't reach another channel, publish, use presence or history, list channels, or widen itself on re-auth. Q4 a connection idle for 10 min stayed up (Ably heartbeats beat the ~40 s idle cut). Q5 prices unchanged: Ably Pro ≈ $970/month at launch; 3× exceeds Pro's 50k connections (Enterprise quote). Q6 REST publish p95 ≈ 45 ms. Finding: the free plan allows 50 REST requests/s and refused ≈ 14 % of publishes at 60/s (42918); Pro allows 1,447/s against ≈ 75 at peak; the outbox sweep already retries, and Phase 3 adds an in-request retry and publishes off the request path. Still to do: the owner deletes the Ably app `restaurant-s3-spike` or revokes its key |
| S-4 | Spike | Built-in sign-in (CR-002): Argon2id time and memory on a Vercel function in `sin1`; token cases (valid, expired, forged, wrong audience, admin without TOTP). Exit: sign-in p95 ≤ 500 ms warm, every case as specified | No (closed) | Manh Nguyen | **Closed 2026-10-14** by the owner. Run 2026-10-14 on Vercel `sin1` (`spikes/s4-auth/README.md`): **exit criterion met**. Warm sign-in p95 86 ms (Argon2id 19 MiB, t=2, 1 CPU); 19/19 token cases pass, incl. the admin TOTP step; Vercel's edge also blocks empty-signature tokens (403). Bouncy Castle ≈ 2× faster than Password4j (p95 85 vs 130 ms): chosen by the owner. 10 sign-ins at once: p95 713 ms on 1 CPU (capacity note). Cold first sign-in 0.4–0.6 s inside the function, 7.7–8.2 s for the client (the S-1 cold start). |
| S-5 | Spike | Report query on ≈ 1 year of synthetic data | No (closed) | Manh Nguyen | **Closed 2026-10-14** by the owner, with the four design points accepted (Decisions). Run 2026-10-14, exit criterion met (`spikes/s5-reports/README.md`). S-2's Neon database (Singapore, free), schema `s5`: 1 year for a 301-bills/day restaurant (110k bills, 661k lines). 31-day report from `daily_sales` p95 10.4 ms in PostgreSQL, 65 ms on the client from the Mac, 76 ms on a cold cache (target 3 s); 93 days p95 131 ms. The same report from the raw rows: 31 days p95 0.58 s warm and 1.2–2.4 s cold, only because the tables hold 2 restaurants (estimated 25–50 s cold at 500), so `daily_sales` stays. Report from `daily_sales` = raw report for 38/38 ranges, incl. 3,075 bills closing after local midnight. Upsert at bill close + ≈ 2 ms (p95 22.5 ms with 10 closing at once); nightly one-day rebuild ≈ 50–60 ms. Storage 738 bytes/order → ≈ 25 GB/year at 100k orders/day. Neon wake after idle 1.1–1.8 s (first number for R-11). A bill counts on the local day it closes (ASSUMPTION-11) |
| TBD-1 | TBD | Staff identity provider (OIDC) | No (G2: needed for the auth model) | Manh Nguyen | Resolved 2026-10-14 by [CR-002](cr/CR-002-built-in-staff-accounts.md): built-in staff accounts, no external IdP ([ADR-0011](adr/0011-staff-identity-provider.md)) |
| TBD-2 | TBD | Customer mobile platforms (Android, iOS, both); staff app as a store app? | No (G2) | Manh Nguyen | Resolved 2026-10-14 by the owner: customer app on Android and iOS; staff app web only in release 1 (no store app). Written into requirements v1.2 by CR-003 |
| TBD-3 | TBD | Support window for old mobile app versions (proposed 12 months) | No (G2) | Manh Nguyen | Resolved 2026-10-14 by the owner: 12 months. It applies to the customer mobile app only, since the staff app is web only. Written into requirements v1.2 by CR-003 |
| TBD-4 | TBD | Retention periods (accounting records, audit, staff data) and erasure deadline | No (G2: needed for the data design) | Manh Nguyen + legal | Open, **working values set 2026-10-14** by the owner until the legal review confirms them: orders and bills 10 years (the last 2 in PostgreSQL, older ones archived to object storage); audit log 1 year; staff anonymised 30 days after deactivation; staff data erased within 72 h of a request (stricter than the legal deadline: Decree 356/2025, which replaced Decree 13 on 2026-01-01, allows 20 days, 30 with a processor, per EY's summary). Questions for the lawyer are in the legal-review brief (see TBD-10). They are settings of the retention jobs (architecture §6), so the review changes numbers, not the design |
| TBD-5 | TBD | FR-022 menu images: Must or Should | No | Manh Nguyen | Open. Cost input: ≈ $470/month at launch on Vercel Blob (cost-estimate.md §4) |
| TBD-6 | TBD | VAT and service charge on the bill (FR-007) | No | Manh Nguyen | Resolved at G1: prices include VAT |
| TBD-7 | TBD | Correcting a closed bill (FR-013) | No | Manh Nguyen | Open |
| TBD-8 | TBD | FR-016 waiter confirmation in release 1? | No | Manh Nguyen | Open |
| TBD-9 | TBD | Team, budget, target launch date | No | Manh Nguyen | Open |
| TBD-10 | TBD | Data residency and legal basis per data type (legal review) | No for G1; **blocks G2** (platform regions) | Manh Nguyen + legal | Open. Legal-review brief written 2026-10-14 for the lawyer: [Legal review brief](https://claude.ai/code/artifact/db7a1c5a-4884-4064-9779-17b6453fde46) (covers TBD-10 and TBD-4). The owner fills in the operating company before sending. Found while writing it: Decree 13/2023 was replaced by Decree 356/2025 on 2026-01-01; transfers abroad now need A05's approval beforehand (15-day review); the small-business exemption likely doesn't apply to a processing service; Decree 53 may require 12 months of system logs (ours: 30 days). Requirements §7 cites Decree 356/2025 since v1.2 (CR-003) |
| TBD-11 | TBD | Monthly cost estimate (Vercel, database, pub/sub, Redis, observability) after S-1 to S-3 | Yes (G2 capacity model) | Manh Nguyen | Draft v0.2 2026-10-14 in [cost-estimate.md](cost-estimate.md), for the owner's review: ≈ $1,540–1,740/month at launch without menu images, ≈ $3,700–4,350 at 3×. S-3 confirmed the pub/sub line (Ably Pro ≈ $970 at launch, ≈ 55–60 % of the total; prices rechecked, unchanged), so no placeholder remains. Menu images would add ≈ $470 (TBD-5). Today $0 on free plans. Closes when the owner accepts the estimate |
| TBD-12 | TBD | Observability backend vendor (OTLP) and its region | No | Manh Nguyen | Open |
