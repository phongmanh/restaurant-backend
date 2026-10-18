# Test plan: Restaurant ordering service

| Version | Status | QA lead | Approvers |
|---|---|---|---|
| 0.9 | Draft: fitted to free plans for Phase 5 (§2–§4, §6, §7, §9), for the owner's review; v0.6 applies CR-013 (release 1 without live push: ST-03, ST-05, ST-10, §3, §9); v0.7 applies CR-014 (ST-03's release-1 p95 targets); v0.8 records the acceptance run (§5 Result); v0.9 adds ST-05's paper check and the summary report (§10), for the owner's sign-off; §1 and §5 approved at G1 | TBD | Manh Nguyen (product owner, tech lead), operations owner TBD |

Phase 1 filled §1 and §5. Phase 2 completed §2–§4 and §6–§8 as the master test plan. Phase 5 (v0.5, 2026-10-17) assigned the `ST-` IDs to the §4 rows, which Phase 3 hadn't, and fitted the System and NFR levels to free plans (§9).

## 1. Scope
- **In scope:** FR-001 to FR-008, FR-010 to FR-016, FR-020 to FR-024, FR-030 to FR-032, FR-040 to FR-044, FR-050 to FR-051 (Should and Could only if built); every NFR in [requirements.md](requirements.md) §4.
- **Out of scope:** the client apps' own UI tests (each client project owns them); the identity provider's and hosting platform's internals; the Won't-have items in requirements §3.7.
- **Regression scope:** not applicable (new service).

## 2. Levels and tools
| Level | Scope | Tools | Runs on | Owner | When |
|---|---|---|---|---|---|
| Unit | Services, domain rules (order state machine, pricing, bill totals), mappers, validators, token signing/verification, password hashing and policy (length, common-password list), TOTP codes (window, replay), cursor encoding | kotlin-test, coroutines-test, fakes (`FakeOrderRepository`, `ImmediateTransactor`, `FixedClock`, `SequentialIds`, `FakeRealtimePublisher`, `FakeRateLimiter`) | CI | Developers | Every PR |
| Integration | Routes through the full pipeline (route-test matrix 401/400/404/success, 403 per role, 412/428), repositories on PostgreSQL, RLS policies, per-restaurant event `seq`, idempotency, outbox, migrations, the Koin graph, adapters (pub/sub, Redis, storage); sign-in flows: wrong password vs unknown email (same 401), account lock after 10 failures, admin TOTP step, refresh rotation and reuse revoking the family, sign-out, invitation and reset links (expiry, single use, a new link voids the old), token checks (expired, forged, wrong algorithm, wrong audience, admin token without `otp`) | `testApplication`, Testcontainers PostgreSQL (and Redis), Ktor `MockEngine` | CI with Docker | Developers | Every PR |
| Authorisation matrix | Every operation × {guest of another session, staff of another restaurant, each role, platform admin, no token} per architecture §7 | Table-driven `testApplication` tests | CI | Developers | Every PR |
| Contract | Route responses validated against `docs/api/openapi.yaml`; route listing vs contract paths; `oasdiff breaking` vs the last released contract; oldest supported mobile client's DTOs | OpenAPI validator, oasdiff | CI | Developers | Every PR |
| System | End to end on staging through the service's own sign-in, the pub/sub staging tenant and Vercel staging deployment | API test suite (Kotlin or k6 scripts); the client apps | Staging | QA | Phase 5 |
| NFR | Load, stress, soak, live-update delay; resilience (pub/sub, Redis, database failover, instance churn); security (ZAP with the contract, ASVS L2 checklist); deploy, rollback and restore | k6 (HTTP + pub/sub subscribers), provider fault injection / credential revocation, ZAP, Vercel rollback, provider PITR | Staging | QA, tech lead | Phase 5 |
| Acceptance | Must FRs (§5) | Client apps on staging | Staging | Product owner, pilot restaurant | Phase 5 |

**Phase 5 on free plans.** Every service stays on its free plan (owner, 2026-10-14), so the System and NFR levels run on the free staging in §3, at a smaller size wherever the full size needs a paid plan. §4 says, test by test, what runs and what stays a residual risk; §9 sets the order and the budget. Tools:
- **System tests:** a Kotlin suite in its own Gradle source set (`systemTest`), run on demand against a base URL and never part of `./gradlew build`. It reuses the service's DTOs, and the contract check if it can wrap a remote client (settled in step 4). No client app exists yet, so the API scripts stand in for them in the acceptance scenarios, as §5 allows.
- **Load:** k6 (Docker image), with a request budget printed and checked before each run (§9).
- **Security scan:** ZAP's API scan (Docker image) driven by `docs/api/openapi.yaml`.

## 3. Environments and load profiles
| Environment | Size vs production | Data | Purpose |
|---|---|---|---|
| CI | Containers | Generated per test | Unit, integration, contract |
| Preview (per PR) | Vercel preview + database branch | Seeded fixtures (restaurants A, B) | Review, smoke tests |
| Staging | Same Vercel plan and region; database the same tier as production; pub/sub and Redis on plans that allow the load test | Synthetic, production volume: 500 restaurants, 25 tables each, 1,000 menu items for the largest, ≈ 1 year of orders (≈ 36M orders, 110M lines) for report and index tests | System, NFR, acceptance (before a paid launch) |
| Staging on free plans (Phase 5) | Vercel Hobby (test account `liam-36c7`, region `sin1`), its own project; Neon free plan (Singapore), its own project; Ably free plan (200 connections, 50 REST requests/s), its own app, unused since CR-013 (staging runs `LIVE_PUSH=off`); Upstash free plan; Grafana Cloud free plan for metrics and alerts (10k active series, 14 days' retention, so no 28-day SLO figure). Production on Hobby scales to zero, so cold starts show (S-1: 5–7 s) | Seeded fixtures and a scaled synthetic history (§6) | System, scaled NFR, acceptance |

| Profile | Endpoints and mix | Rate | Duration | Pass criteria |
|---|---|---|---|---|
| Expected peak | Architecture §9 mix (menu 23%, session/bill 23%, catch-up 24%, QR 5%, orders 3%, status 13%, staff lists 5%, tokens 3%, admin 1%) + 15,000 guest and 2,500 staff pub/sub subscribers | 310 req/s | 60 min (a dinner service) | NFR-PERF-01..03 met; errors < 0.1%; outbox backlog < 100 |
| Headroom | Same mix | 600 req/s (≈ 2×) | 30 min | NFR-PERF targets still met |
| Stress | Same mix | Ramp to 5× peak | Until failure | Sheds load with 429/503; recovers without manual action; no lost or duplicate orders |
| Soak | Same mix | Peak | 4 h | No growth in latency, memory, pooler wait or outbox age |
| Noisy tenant | Peak + one restaurant at 10× its share | 310 + 100 req/s | 30 min | Other restaurants within NFR-PERF; noisy one gets 429 |
| Cold burst | 0 → peak in 60 s after 30 min idle | 310 req/s | 10 min | p99 within NFR-PERF after the first minute; cold-start count recorded |
| Smoke load (free) | Same mix | 10 req/s | 10 min (6,000 requests) | A baseline, not a peak result: p95 and p99 of warm requests, with cold-start requests counted apart; errors < 0.1% |
| Cold burst (free) | 0 → 10 req/s after 30 min idle | 10 req/s | 10 min | Cold-start count and wait recorded; compared with S-1 |

On free plans only the two `(free)` profiles run. The six full-size profiles need paid plans: an hour at 310 req/s is about 1.1M requests, more than Hobby's monthly function allowance, and 17,500 subscribers is far over Ably's 200 free connections. §4 tags the tests that need them `Residual`.

## 4. NFR tests
The **Phase 5 (free plans)** column says how each test runs on the free staging (§3):
- **Free:** runs in full.
- **Scaled:** runs at a smaller size; the full-size target stays unmeasured and becomes a residual risk at G5.
- **Residual:** needs a paid plan, so it doesn't run (owner, 2026-10-14); a residual risk at G5.
- **Blocked:** waits for an open item.
- **CI:** already proved by `./gradlew build` on every PR; Phase 5 records the evidence.

| ST ID | NFR ID | Test | Method / tool | Target | Phase 5 (free plans) | Result |
|---|---|---|---|---|---|---|
| ST-01 | NFR-PERF-01 | Guest reads at peak | k6 expected-peak profile | p95 ≤ 200 ms, p99 ≤ 500 ms | **Scaled:** smoke-load and cold-burst profiles (§3). At peak: residual (R-12, R-16) | **Passed, scaled** (`loadTest`, 2026-10-18; a baseline at 10 requests a second, not a peak result). **Smoke** (6,201 requests): warm guest and staff reads p95 92 · p99 169 ms (target 200 / 500), 0 requests over 2 s, 0 unexpected answers. **Cold burst** (5,892 requests, 0 → 10 a second after 30 min idle): warm reads p95 97 · p99 180 ms, 0 unexpected answers; one instance started during the ramp ("Application started in 2.7 s" in Vercel's logs), and one request waited for it: 9.6 s, consistent with S-1's 5–7 s. The first request after the idle wait (1,367 ms) doesn't measure a cold start: three requests through the protected domain, not from the run (`GET /`, `/heath`, `/health`), had started two instances 12–17 s before it. The cold starts at peak and on the paid plan: residual (R-12, R-16) |
| ST-02 | NFR-PERF-02 | Writes at peak | k6 expected-peak profile | p95 ≤ 300 ms, p99 ≤ 800 ms | **Scaled:** as ST-01 | **Passed, scaled** (the same runs as ST-01): warm writes (QR scans, orders, status changes, live-update tokens) p95 132 · p99 217 ms in the smoke run and p95 135 · p99 183 ms in the cold burst (target 300 / 800); in the cold burst 184 orders acknowledged, the same 184 in the database, 0 duplicates (the smoke run's order check was a tool defect, fixed: see STATUS 2026-10-18) |
| ST-03 | NFR-PERF-03 | Live-update delay | Release 1 (CR-013): polling screens time when each change first shows in their catch-up read vs the write's response time. With live push: subscribers time stamp each notification + catch-up fetch | Release 1 (CR-014): staff p95 ≤ 16 s, p99 ≤ 20 s; guests p95 ≤ 62 s, p99 ≤ 75 s. With live push: p95 ≤ 2 s, p99 ≤ 5 s | **Scaled:** 55 polling screens on restaurant L (25 tables × 2 phones, 5 staff screens) under the smoke load (IT-RT-11). The 2 s run waits for live push (deferred with ADR-0003; R-13) | **Passed, scaled** against CR-014's targets (`loadTest --profile live`, run `tn3k8b`, 2026-10-18; 55 polling screens on L under the smoke load, 14 min). Staff screens: 4,132 samples, p50 7.6 · p95 **15.4** · p99 17.1 · max 26.1 s (target 16 / 20 s). Guest phones: 1,670 samples, p50 29.3 · p95 **60.6** · p99 67.6 · max 71.0 s (target 62 / 75 s). Every write was shown by a regular poll (0 unseen); 624 samples left out because the screen was paused. Against CR-013's first targets (15 s, 60 s) the p95s missed by under 1 s, which they always would: with a timer of T ± 20 %, the wait for the next poll has p95 = T exactly (p99 1.11 T, max 1.2 T), and the measured values are that plus ≈ 0.5 s for the request (Q-22, CR-014). The staff maximum (26 s) is one poll that met the 09:50 scale-out (ST-01). k6's clock was 4 ms from the driver's (median) |
| ST-04 | NFR-PERF-04 | 31-day report | Report requests on production-sized data | p95 ≤ 3 s | **Scaled:** restaurant L's full year on the §6 history. The report reads one restaurant's `daily_sales` rows through an index, so its size matters most; the index for 500 restaurants is deeper by about one level: residual (R-16) (IT-REP-12) | **Passed** (`loadTest --profile report`, 2026-10-18, 62 requests): 60 requests for restaurant L's 31-day report (31 days, 14,898 orders), one every 2.5 s: p50 119 · p95 181 · p99 314 · max 314 ms against p95 ≤ 3 s; the first request 134 ms (the instance was warm) |
| ST-05 | NFR-SCAL-01 | Throughput and connections | Expected peak + headroom profiles; capacity model check | Release 1 (CR-013): the polling peak ≈ 640 req/s, 2× headroom ≈ 1,280 (architecture §9). With live push: 310 req/s + 17,500 subscribers; 2× headroom | **Residual** (R-09, R-16). The capacity model is re-checked on paper against the ST-01 to ST-03 numbers | **Paper check: fits; unmeasured at size** (2026-10-18, against architecture §9). S-1's empty request from Vietnam took p50 37 ms, so the medians of the smoke and cold runs (reads 59–60 ms, writes 94–95 ms) leave ≈ 23 ms of service work per read and ≈ 57 ms per write. Weighted by release 1's mix (≈ 65 of the ≈ 640 req/s are writes: scans, orders, status changes), the average is ≈ 27 ms, under the model's 50 ms. By Little's law (requests in flight = rate × time) that is ≈ 17 requests in flight at the release-1 peak and ≈ 34 at 2× headroom; counting the whole client-side time as service time, ≈ 41 and ≈ 81. A request holds a database connection only for its transaction, a part of that time, so the pooler's ≈ 40 server connections hold on paper. Measured at 10 req/s only: queueing, instance count and the pooler at 640–1,280 req/s stay residual (R-09, R-16) |
| ST-06 | NFR-SCAL-02 | Fairness | Noisy-tenant profile | Others unaffected | **Scaled:** one restaurant goes over its per-restaurant limit and gets 429 while another's requests keep succeeding (real WAF and Upstash). Latency for the others under load: residual (R-16) | **Passed** (`loadTest --profile noisy`, 2026-10-18, 483 requests): in one minute's window A sent 340 orders (25 guests × 10, manager and two waiters × 30) against its limit of 300: 300 accepted, 40 `429 rate_limited` (`Retry-After` 55 s), no other answer, all inside the window. B's guest placed 5 of 5 over the same 30 s, 4 of them after A's first refusal |
| ST-07 | NFR-AVAIL-01/02 | SLO measurement works | Run the SLO queries and burn-rate alerts against the load-test traffic with injected errors | Alerts fire at the agreed burn rates | **Free, after §9 step 4:** needs an OTLP backend with a free tier (TBD-12) and the metrics export (ADR-0009), which Phase 4 didn't build; the owner chose to build it in Phase 5 (Q-17, option a). Two halves: offline, `AlertRulesTest` runs promtool on synthetic series with injected error rates and delays for every alert, in every build (step 4c); live, `injectErrors` pushes 5% synthetic 5xx to Grafana Cloud and the alert must fire there (`deploy/README.md`). The service can't fail on purpose, so the live errors are synthetic series | Offline half passed (2026-10-17, 9 promtool tests, 39 rules); **live half not run: the owner dropped it** (2026-10-18, "grafana not friendly to use, I dont like. ignore it"), so no alert has fired in Grafana Cloud itself; the rules have only been checked offline |
| ST-08 | NFR-REL-01 | Restore | Restore staging from PITR to a new branch/instance; time to a working deployment | RPO ≤ 5 min, RTO ≤ 1 h | **Free:** restore to a point 1 h back, inside the free plan's 6 h window; the 7-day window: residual (R-14) || **Passed on the rerun** (2026-10-18, D-3 fixed, Q-23 option a): RPO within 5 min, **RTO ≤ 13 min** against ≤ 1 h. The first run's RTO failed (1 h 06 min), see below. **Rerun:** Neon branch `restore-st08b` with **Past data** at 10:32:00 UTC (host `ep-twilight-lake-…`), made by the owner after 12:52 UTC (the exact start wasn't noted; 12:52 is when the steps were sent, so the RTO is at most this); `swap-db-host.sh`; deployment `hff2wlcoc` created 13:00:44; alias removed (`404`); `/health` 200; at 13:05:22 the check found only `st08-261018102922` (`RestoreDrillTest`, run `tn3toy`, 2 requests). Switched back with `swap-db-host.sh main` and `4usu5mw6b` (public alias answered for at most 3 min, removed by 13:11:18 UTC, `404`); `clean` there (run `tn3tzf`, 2 requests). **First run:** (2026-10-18, deploy/README.md "Restore drill", 10 requests). Marks `st08-261018102922` (10:29:32 UTC) and `st08-261018103459` (10:35:09) on `main`; Neon branch `restore-st08` made with **Past data** at 10:32:00 UTC, 59 min back when the owner started it at 11:31 UTC (a first attempt at 11:06, 34 min back, held both marks: its host never reached Vercel); `scripts/swap-db-host.sh` pointed both URLs at it; deployment `6xeuhcdvr`: `/health` 200, and at 12:37:24 UTC the check found only the first mark (`RestoreDrillTest`, run `tn3se9`). **RPO:** everything to the point came back and nothing after it, within 5 min. **RTO:** 11:31 UTC (owner starts the second branch) → 12:32:26 (deployment `6xeuhcdvr` created) → 12:37:24 (check passed) = **1 h 06 min**. The hour went on the manual host edit, which never reached Vercel, and on Neon's copied URL saying `sslmode=require`, which the service refuses; `scripts/swap-db-host.sh` now does that step. The deploy and check took 5 min. Switched back (`9ho6kmh76`, both marks seen on `main`, then cleaned: none left) |
| ST-09 | NFR-REL-02 | Retries and instance loss | Repeat `Idempotency-Key` under load; redeploy during load (forces instance churn) | 0 duplicates, 0 lost acknowledged orders | **Scaled:** under the smoke-load profile, with one redeploy during the run | **Passed** (`loadTest --profile deploy`, 2026-10-18, 7,358 requests): 12 minutes at 10 requests a second; the owner's `vercel deploy --prod` switched the domain to `restaurant-staging-1mummuts0` at minute 4.4. **0 unexpected answers** in 7,203; **224 orders acknowledged, the same 224 in the database: 0 lost, 0 duplicates**; every replay (one order in five sent twice with its key) answered the same order. The new deployment started cold under load: 89 requests over 2 s in minutes 4–5 (max 15.7 s) and 39 iterations k6 couldn't start, which took warm reads' p99 to 776 ms for the run (over NFR-PERF-01's 500 ms; outside those two minutes as in ST-01). Each later steady run (2 × 6 min) also found 0 lost, 0 duplicates (94 and 104 orders) |
| ST-10 | NFR-REL-03 | Live-update gaps | Release 1 (CR-013): screens pause at random (a phone asleep, a closed tab), then catch up. With live push also: disable pub/sub credentials for 5 min under load, then restore; drop client connections at random | Every client's event sequence matches the database; no gaps or duplicates | **Scaled:** the same run as ST-03, 55 polling screens under the smoke load (IT-RT-10). The credentials-off half waits for live push | **Passed, scaled** (the same run as ST-03, `tn3k8b`, 2026-10-18). 25 of the 55 screens paused once for 1–3 minutes and caught up on waking; at the end all 55 held exactly the database's events for their view (`domain_events`, seq 5786 to 6703, 918 events): no gap, no duplicate, the same order and type. With `LIVE_PUSH=off` the run added 0 outbox rows. The credentials-off half waits for live push (CR-013) |
| ST-11 | NFR-SEC-01 | ASVS L2, DAST | ASVS checklist; ZAP API scan with the contract | No high findings; checklist complete | **Free:** ZAP against staging; the checklist builds on [security-review.md](security-review.md) | **Passed** (2026-10-18): D-2 (S1), found by the checklist, was fixed by step 6a's follow-up (`JdbcTarget` requires `sslmode=verify-full`) and runs on staging since deployment `restaurant-staging-k08sb0cgr`, where every database scenario of the system suite passed over `verify-full`. The scan and checklist ran on deployment `restaurant-staging-74er7may0` ([asvs-checklist.md](asvs-checklist.md)). ZAP: 13,411 requests in two runs, five profiles with their own tokens; no High stands (one false positive, path traversal on a table number), one Low defect fixed (headers sent twice on problem responses). Checklist: complete, all 253 L1 and L2 requirements of ASVS 5.0.0 have a verdict (Met 140, Partly 17, Gap 11, Deviation 3, Platform 8, Apps 8, N/A 66). It found D-2 (the database connection doesn't verify Neon's certificate) and gaps F-2 to F-7; Q-21 decided: F-2, F-3 and F-6 fixed in the follow-up, F-4 and F-5 accepted for release 1, F-7 is R-19 |
| ST-12 | NFR-SEC-02 | Isolation | Authorisation-matrix suite on staging (AT-042) | 0 cross-tenant/session access | **Free:** AT-042 on staging, the only matrix: the owner chose not to add one over every operation (Q-16) | **Passed** (AT-042 in the acceptance run, deployment `restaurant-staging-4usu5mw6b`, 2026-10-18): 101 calls with restaurant B's IDs in the path, every one `404`; B's IDs in a body `404` or `400`; nothing in B changed |
| ST-13 | NFR-SEC-03 | Audit trail | Each audited action produces an entry; hash chain verifies; app user can't update/delete | All pass | **Free** (CI already covers the rules: IT-CORE-05) | **Passed** (deployment `restaurant-staging-74er7may0` (step 3's build), 2026-10-18): a member's invitation, failed and successful sign-ins and deactivation each audited, the failed sign-in's entry carrying the request's ID; all 55 chains in Neon (1,502 entries) verify up to their heads; `app` and `app_platform` refused `UPDATE` and `DELETE` (42501) |
| ST-14 | NFR-SEC-04 | Abuse limits | Exceed each limit; admin without MFA | 429 at the limits; admin refused | **Free:** each limit on staging with the real WAF rule and Upstash | **Passed** (deployment `restaurant-staging-74er7may0` (step 3's build), 2026-10-18): sign-ins from one IP: 30 answered `401`, the 31st the app's `429 rate_limited` with `Retry-After`, the WAF's `429` after 56 requests (staging's rule is 60 a minute: Q-20); report reads: the 31st in a minute `429`, another staff member still served; an admin's password gives a TOTP challenge only, which isn't an access token, and a wrong code uses it up |
| ST-15 | NFR-PRIV-01 | Retention and minimisation | Retention-job tests with a fixed clock; log sample review for PII; staff export/erasure runbook dry run | No PII in logs; data removed on schedule | **Free:** retention runs in CI (IT-CORE-09 and others); on staging, a review of a Vercel log sample after the system suite, and the runbook dry run | **Passed** (deployment `restaurant-staging-74er7may0` (step 3's build), 2026-10-18): the log review (`deploy/staging/log-review.py`) of 139 lines after the suite found no email, token, IP, phone or seeded secret; the export/erasure runbook (`deploy/README.md`) dry run: the export holds the personal fields and no secret, erasure takes the member out of sign-in and the member list at once, both audited |
| ST-16 | NFR-PRIV-02 | Residency | Configuration review of every store, log and trace destination | Regions per TBD-10 | **Blocked:** the review can list every region now (`sin1`, Singapore); pass or fail waits for TBD-10, which blocks G5 | |
| ST-17 | NFR-COMPAT-01 | Compatibility | Contract tests with the oldest client's DTOs; oasdiff | No breaking change | **CI:** `oasdiff breaking` against the G3 tag and the contract check (IT-CORE-08, IT-CORE-10). No client is released yet, so there are no older DTOs to test | **Passed in CI** (2026-10-18, the `contract` and `build` jobs on `ad70689`, the last commit with CI, CR-014): `scripts/contract-breaking.sh` reports no breaking change against the G3 tag; IT-CORE-08 and IT-CORE-10 pass, and every response a test gets is checked against the contract |
| ST-18 | NFR-OBS-01 | Observability | Trace a request ID from client to logs and traces; check dashboards and alerts | Every SLO has an alert and a runbook | **Free** for the request ID (client → `X-Request-ID` → Vercel logs). Dashboards and alerts: with ST-07, after §9 step 4; runbooks: release checklist (Phase 6) | **Request ID passed** (deployment `restaurant-staging-74er7may0` (step 3's build), 2026-10-18): the service's own UUID v7 in the header and the problem body, the client's beside it as `client_request_id`, both found in Vercel's logs. Dashboards and alerts: with ST-07 |
| ST-19 | NFR-OPS-01 | Deploy and rollback | Deploy and instant-rollback during the expected-peak profile | No failed requests from the deploy; rollback ≤ 10 min | **Scaled:** under the smoke-load profile | **Passed, with a switch back instead of Vercel's rollback** (owner's option (a), 2026-10-18, 3,747 requests): under 6 minutes at 10 requests a second the owner pointed the protected domain back at the earlier deployment (`vercel alias set`): the command took 2 s (06:33:08 → 06:33:10) and the domain served it by the next poll (06:33:17); 0 unexpected answers in 3,592; 104 orders, 0 lost, 0 duplicates; the earlier deployment started cold (47 requests over 2 s in the first minute, 30 iterations not started). Vercel's own rollback couldn't run on this staging: it moves the production domain, which staging removes (`vercel rollback` answered `500` twice; the dashboard's Instant Rollback changed nothing). The deploy itself: ST-09. Instant rollback with the production domain in place: residual (R-16) |
| ST-20 | NFR-MAINT-01 | Code quality | CI gates | ktlint/detekt clean; coverage ≥ 80% | **CI** | **Passed in CI**: `./gradlew build` (ktlint, detekt, tests, the Kover gate, the route-test matrix) green on every PR, last on `ad70689` (2026-10-18) |
| ST-21 | NFR-I18N-01 | Time zones and money | Report tests across the UTC day boundary; contract review of money fields | Correct per restaurant time zone | **Free:** AT-050 on staging (CI: IT-ORD-16, IT-REP-02) | **Passed** (AT-050 in the acceptance run, deployment `restaurant-staging-4usu5mw6b`, 2026-10-18): the daily report for 2026-10-12 to 2026-10-18 matches A's 27 closed bills per day in Asia/Ho_Chi_Minh, per payment method, item and table |

## 5. Acceptance outline
Run on staging through the real client apps (customer mobile, customer web, staff app) or API scripts, with two test restaurants (A and B) so that isolation is exercised.

| AT ID | FR | Scenario (Given / When / Then) | Result |
|---|---|---|---|
| AT-001 | FR-001 | Given table A-5 has no open session, when guest 1 scans its QR on the mobile app and guest 2 scans it on the web app, then both are in the same session. When a manager rotates A-5's QR and guest 3 scans the old printout, guest 3 is refused and guests 1 and 2 can still order. | **Passed** |
| AT-002 | FR-002 | Given restaurant A's menu has a hidden item and a sold-out item, when a guest at A-5 opens the menu, then the hidden item is absent, the sold-out item is marked sold out, and no item of restaurant B appears. | **Passed** |
| AT-003 | FR-003 | Given a guest at A-5, when they order 2 × item X with a note and the network drops so the app retries with the same key, then exactly one order exists, priced at the menu price, and it shows on the kitchen screen. Ordering a sold-out item is refused and nothing is ordered. | **Passed** |
| AT-004 | FR-004 | Given A-5 has one order, when guest 2 orders another round, then both orders are on one session and one bill. | **Passed** |
| AT-005 | FR-005 | Given guests 1 and 2 at A-5 have the order screen open, when the kitchen moves the order to `preparing`, then both screens show `preparing` within 2 s without reloading. After guest 1's phone is offline for a minute and comes back, it shows every change it missed. | **Passed**: `preparing` reached the two guests in 22.2 s and 57.0 s (run 1: 38.9 s and 56.1 s), by their 60 s poll, within release 1's NFR-PERF-03 (CR-013, CR-014; the scenario's 2 s applies once live push is added); the phone offline for a minute caught up on every change |
| AT-006 | FR-006 | Given an order in `placed`, when a guest cancels it, then it leaves the bill and the kitchen sees it cancelled. Given an order in `accepted`, the guest's cancel is refused with "ask staff". | **Passed** |
| AT-007 | FR-007 | Given A-5 has orders and the manager then raises item X's price, when a guest opens the bill, then lines ordered before the change keep the old price and the total is correct. | **Passed** |
| AT-010 | FR-010 | Given waiter, kitchen and manager accounts in restaurant A, when each signs in to the staff app, then each sees only their role's functions; the kitchen user can't open menu editing; a deactivated waiter is locked out within 15 min. | **Passed**; the deactivated waiter is locked out at once, not only within 15 min |
| AT-011 | FR-011 | Given the kitchen screen of restaurant A is open, when guests at three tables of A and one of B place orders, then A's kitchen shows exactly A's three orders within 2 s, with table numbers, items and notes. After the screen's network is cut and restored, no order is missing or doubled. | **Passed**: A's kitchen got exactly A's three orders in 3.1–3.6 s (run 1: 0.2–0.8 s), by its 15 s poll (release 1's NFR-PERF-03; the 2 s applies once live push is added); none missing or doubled after the network cut |
| AT-012 | FR-012 | Given an order, when the kitchen moves it `accepted → preparing → ready` and a waiter marks it `served`, then each step shows to guests and staff. Two waiters marking it at the same time → one succeeds, the other is told to refresh. Skipping a stage (e.g. `placed → served`) is refused. | **Passed** |
| AT-013 | FR-013 | Given A-5 has served orders, when the waiter closes the session with "cash, 450,000 VND", then the bill is final, guests 1 and 2 can no longer order, and the next scan of A-5 opens a new, empty session. | **Passed** |
| AT-020 | FR-020 | Given a manager, when they add a category and an item, edit its price and hide another item, then a guest's menu shows the changes on the next load. A waiter can't edit the menu. | **Passed** |
| AT-021 | FR-021 | Given item X is on the menu, when the kitchen marks it sold out, then a guest's order containing X is refused; when it is marked available again, the order succeeds. | **Passed** |
| AT-030 | FR-030 | Given a manager, when they create tables 1–20 and try a duplicate table 5, then the duplicate is refused; deleting a table with guests seated is refused. | **Passed** |
| AT-031 | FR-031 | Given table A-7, when the manager prints its QR code and a guest scans it with the camera app on a phone with the customer app installed and on one without it, then the first opens the app and the second the web app, both in table A-7's session. | **Passed**, through the API: the printed code's link and the scan into A-7's session. The camera hand-off to the app or the web app waits for the client apps |
| AT-032 | FR-032 | Given A-5 is occupied and A-6 is free, when a waiter moves the session from A-5 to A-6, then the orders and bill are on A-6 and A-5 shows free. Moving onto an occupied table is refused. | **Passed** |
| AT-040 | FR-040 | Given a platform admin, when they onboard restaurant C and invite its manager, then the manager can sign in and sees an empty restaurant; a manager of A can't reach the admin functions. | **Passed** |
| AT-041 | FR-041 | Given a manager of A, when they invite a waiter, change them to kitchen and then deactivate them, then each change takes effect and appears in the audit log; the last manager can't remove themselves. | **Passed** |
| AT-042 | FR-042 | Given staff and guest sessions of restaurant A, when the API scripts replay every endpoint with IDs belonging to restaurant B (orders, tables, items, staff, sessions, reports), then every call returns 404 and nothing in B changes. | **Passed**: 101 calls with B's IDs in the path, every one `404`; B's IDs in a body `404` or `400`; nothing in B changed |
| AT-044 | FR-044 | Given a manager, when they change the restaurant's opening hours and time zone, then the staff app shows the change; a waiter can't change them. | **Passed** |
| AT-050 | FR-050 | Given a week of closed bills in restaurant A, some of them crossing midnight UTC, when the manager requests the daily report, then the totals per day (in Asia/Ho_Chi_Minh), per payment method, per item and per table match the bills, and cancelled lines are excluded. | **Passed**: 27 closed bills of 2026-10-12 to 2026-10-18 (run 1: 21) match the report |

**Results** (Phase 5 step 7, 2026-10-18): the owner's acceptance run, the system suite (`./gradlew systemTest`) through the API, as no client app exists yet, on deployment `restaurant-staging-4usu5mw6b` (the release candidate, `LIVE_PUSH=off`). The owner ran it twice; in both, 29 tests passed (the 21 ATs and 8 tests of the `Free` rows ST-13, ST-14, ST-15 and ST-18), none failed and 3 were skipped (ST-08's drill, run only on request): run `tn3ujb` (13:23–13:29 UTC, 957 requests) and a second at 14:02–14:08 UTC (ST-18's IDs `01a14f57-27ed-…` and `01a14f57-2821-…`), whose measurements the Result column gives, with run 1's as "run 1: …". Every response was checked against the contract (`ContractCheck`). The log review after it (`log-review.py --since 1h`, 2,004 lines) found both ST-18 IDs and no personal data, secret or token (ST-15).

## 6. Test data and accounts
- **Restaurants:** A and B with full menus (including hidden, sold-out and option items) and 25 tables each; C created by AT-040.
- **Staff accounts (created by a seed script in the service database, CR-002):** per restaurant, one manager, two waiters and one kitchen user; one deactivated waiter; one locked account; a platform admin with TOTP enrolled and one invited but not yet enrolled. Test TOTP secrets and passwords exist only in staging; codes are generated by the test suite.
- **Synthetic data generator** (a Kotlin task in the repo) for production-volume staging data: restaurants, menus and ≈ 1 year of orders with realistic dinner peaks. Never production personal data.
- **Phase 5 volume (free plans).** Neon's free plan stores 0.5 GB (confirmed against the plan page in step 2), and a year for 500 restaurants is ≈ 25 GB (S-2 Q7: 738 bytes per order with indexes), so the generator writes a scaled history:
  - **restaurant L**, the largest: 1,000 menu items, 25 tables, 300 bills a day, a full year of `daily_sales` rows (the rows the report reads, ≈ 19.5 KB a day) and its last 30 days of bills and orders;
  - **49 smaller restaurants** with 100 items each and a year of `daily_sales` rows, so the report's indexes hold many restaurants, not one;
  - the days that keep their bills get ledger rows that match them, so `sales-reconcile` finds nothing to fix. The generator prints its size estimate and refuses to write more than 300 MB.
- **Sandboxes:** pub/sub staging app, Redis staging database, object storage staging bucket, database branch per PR.

## 7. Entry and exit criteria
| Level | Entry | Exit |
|---|---|---|
| Unit, integration, contract | Code in a PR | All pass; coverage ≥ 80% in services and domain; no new detekt/ktlint findings |
| System and NFR | G4 approved; the release-candidate build deployed to staging; staging data loaded | All `ST-` tests run; NFR targets met or gaps accepted in STATUS; no open S1/S2 defects |
| Acceptance | System exit criteria met | Every Must AT passed; product owner signs off |

On free plans, "gaps accepted in STATUS" means the owner accepts, at G5, each §4 row tagged `Scaled` or `Residual` for the part it leaves unmeasured, as a residual risk (R-16 and the risks each row names).

## 8. Defect severity
| Severity | Meaning |
|---|---|
| S1 | Data loss or corruption, a security issue (including any cross-restaurant access), an outage, lost orders |
| S2 | A Must FR is broken, or an NFR target is missed, with no workaround |
| S3 | A workaround exists |
| S4 | Cosmetic |

Each defect records the request ID and the image or deployment ID.

## 9. Phase 5 plan (free plans)
Seven steps, one PR each, as in Phase 4. Results go in §4's Result column, §5's Result column and the RTM in the same PR as the run.

| Step | Work | Done when |
|---|---|---|
| 1 | This plan (v0.5) | The owner has reviewed it |
| 2 | Staging: `Dockerfile.vercel` for the service, a Vercel project, a Neon project with the three roles, an Ably app, an Upstash database; migrations run on Neon (this also checks R-15) | The release-candidate build answers on staging; the free plans' limits are written into §3 from their pages |
| 3 | Seed script (§6 accounts and restaurants A, B) and the scaled generator (restaurant L and 49 others) | Staging data loaded and its size recorded |
| 4 | Observability (Q-17, option a): the owner picks an OTLP backend with a free tier (TBD-12); Claude builds the metrics export (ADR-0009, architecture §13), the SLO queries, burn-rate alerts and dashboards | Staging's metrics arrive and an injected error rate fires an alert (ST-07) |
| 5 | System suite (`systemTest`): every AT scenario in §5 through the API, then the `Free` rows of §4 | Every AT and `Free` row has a result |
| 6 | Scaled runs: smoke load, cold burst, live delay (polling screens, CR-013), restore, retries during a redeploy, rollback, ZAP | Every `Scaled` row has a result |
| 7 | The owner's acceptance run; the summary report | Every Must AT passed and signed off; G5 also needs TBD-10 closed and an ops owner named (R-07) |

**Budget guard.** The free plans' monthly allowances are shared by every run, and going over can pause the account until the month ends. Each run prints the requests and messages it will send before it starts, as S-3's `delay` did, and refuses to start above these caps unless `--force` is given:
- 10,000 HTTP requests per run and 100,000 in Phase 5 in total (about a tenth of Hobby's monthly function allowance);
- 1M Ably messages per run (the free plan allows 6M a month), once live push is on again (not in release 1, CR-013).

Every run records its totals with its result.

**Steps the owner runs.** The auto-mode safety check blocks Claude from these, so they're the owner's:
- production deploys to the staging project (`vercel deploy --prod`), including the redeploy in ST-09 and the rollback in ST-19;
- entering secrets (`vercel env add`);
- setting `LIVE_PUSH=off` and removing `ABLY_API_KEY` from the staging project (CR-013).

Claude prepares each command and reads the results.

## 10. Test summary report (Phase 5)
**Status:** for the owner's sign-off at G5 (written 2026-10-18). It sums up §4, §5 and the RTM; the detail and the evidence stay in their Result columns.

**In plain words:** a test summary report answers one question for the release gate: did the service do what the requirements ask, on something close to its real platform, and what wasn't measured? Here: every Must acceptance test passed on staging, twice. Every NFR test the free plans allow ran and passed, after three defects were found and fixed, except the live alert check, which the owner dropped. What's left was measured at a small size only, or waits for decisions that aren't tests (the legal review, an operations owner).

### 10.1 What was tested
- **Build:** the release candidate, staging deployment `restaurant-staging-4usu5mw6b` (`dpl_BMKRPcfVQ2scsFVKmfFLihucNdkv`), with `LIVE_PUSH=off` (release 1 polls, CR-013), on Neon's `main` branch, region `sin1`. CI (`./gradlew build`, the contract check) green on `ad70689`.
- **Where:** the free staging (§3): Vercel Hobby, Neon free, Upstash free, Grafana Cloud free; the client in Vietnam. All ATs ran through the API (the system suite), as no client app exists yet.
- **When:** 2026-10-17 to 2026-10-18, §9 steps 1–7.
- **Requests:** ≈ 57,000 in the runs recorded (load runs 41,510, ZAP 13,411, the two acceptance runs ≈ 1,900, the rest ≈ 600), under the 100,000 cap; the seed, step 5's suite runs and tool trials aren't in that sum. Vercel's usage page has the exact figure.

### 10.2 Results
| Group | Count | Tests |
|---|---|---|
| Acceptance (§5): every Must FR | **21 of 21 passed** | AT-001 to AT-050, in both of the owner's runs; every response checked against the contract |
| NFR passed in full | 7 | ST-08, ST-11, ST-12, ST-13, ST-14, ST-15, ST-21 |
| NFR passed at a smaller size than production | 8 | ST-01, ST-02, ST-03 (CR-014's targets), ST-04, ST-06, ST-09, ST-10, ST-19 (with a switch back instead of Vercel's rollback) |
| NFR passed in CI | 2 | ST-17, ST-20 |
| NFR partly run | 2 | ST-07 (offline half passed; the owner dropped the live half), ST-18 (request ID passed; traces and dashboards wait for Q-18 and ST-07) |
| NFR checked on paper | 1 | ST-05 (the capacity model fits the measured times) |
| NFR blocked | 1 | ST-16 (residency: waits for TBD-10) |

### 10.3 Defects found in Phase 5
| ID | Severity | Defect | Status |
|---|---|---|---|
| D-1 | S2 | Vercel's edge answers its own `412` to every `PATCH` with `If-Match`, after the change is saved | Fixed: CR-012, updates as `POST …/update` (the `PATCH` kept, deprecated); the system suite checks it on staging |
| D-2 | S1 | The database URLs' `sslmode=require` didn't check Neon's certificate or name | Fixed: `sslmode=verify-full`, and `JdbcTarget` refuses anything weaker; ST-11 passed after it |
| D-3 | S3 | The restore drill's RTO was 1 h 06 min against ≤ 1 h | Fixed in the runbook (`swap-db-host.sh`); the rerun's RTO ≤ 13 min |

**No S1 or S2 defect is open.** Two defects in the test tools, not the service, were fixed in the tools: the first smoke run's order check read only open orders, and step 6c's first run gave its screens the driver's request budget. The ASVS checklist's gaps F-4 and F-5 (Low) are accepted for release 1 (Q-21); F-7 (logs kept an hour, no drain) is R-20.

### 10.4 Exit criteria (§7)
| Level | Criterion | Met? |
|---|---|---|
| Unit, integration, contract | All pass; coverage ≥ 80%; no new findings | Yes (CI on `ad70689`, ST-20) |
| System and NFR | All `ST-` tests run | All but two: ST-16 waits for TBD-10, and ST-07's live half was dropped by the owner (2026-10-18). ST-05 is a paper check by plan |
| System and NFR | NFR targets met, or gaps accepted in STATUS | Met wherever measured; the gaps in §10.5 are for the owner to accept at G5 |
| System and NFR | No open S1/S2 defects | Yes |
| Acceptance | Every Must AT passed | Yes, 21 of 21 |
| Acceptance | The product owner signs off | **Waiting for the owner** |

### 10.5 Gaps to accept at G5
Accepting a gap at G5 means launching with that part unmeasured; each one names the risk that tracks it.

| Gap | Tests | Risk |
|---|---|---|
| Full-size load: measured at 10 req/s, against ≈ 640 at release 1's peak and ≈ 1,280 with 2× headroom; database connections during scale-out; latency of other restaurants under a noisy one | ST-01, ST-02, ST-05, ST-06, ST-09, ST-19 | R-09, R-16 |
| Paid-plan behaviour: warm instances and cold starts on Vercel Pro (ADR-0002's condition before G5) | ST-01 | R-12 |
| Polling screens: 55 measured, not ≈ 17,500; whether 15 s and 60 s feel fast enough is for the pilot | ST-03, ST-10 | R-16, R-19 |
| Live push's 2 s target: deferred with ADR-0003 for release 1 | ST-03, ST-10 | R-13 |
| Reports with 500 restaurants' rows in the index | ST-04 | R-16 |
| Point-in-time restore beyond the free plan's 6 h window | ST-08 | R-14 |
| No alert has fired in Grafana Cloud itself: the rules are checked only offline (promtool), and no dashboard was checked live | ST-07, ST-18 | No risk row yet |
| Traces, the per-restaurant dashboard, deploy markers and 28-day SLO figures (Q-18, open) | ST-18 | Q-18 |
| Vercel's own rollback not proven on staging (a switch back by alias was used) | ST-19 | — |
| Runtime logs kept an hour, with no drain to a separate system | ST-15 | R-20 |
| ASVS F-4 (no rule on parallel sessions) and F-5 (password context words), accepted for release 1 | ST-11 | Q-21 |
| AT-031's camera hand-off to the app, and every AT through a real client app: no client app exists yet | AT-031, §5 | — |

The G4 waiver carries on: FR-022 (Should) stays unbuilt until TBD-13.

### 10.6 What G5 needs besides this report
- The owner's sign-off on the acceptance result (§10.4).
- TBD-10 closed (the legal review on data residency; G2's condition), after which ST-16 passes or fails.
- An operations owner and on-call named (R-07).
- Q-18 decided, and a decision on the gaps in §10.5.
- The open PRs #28 to #33 and this step's branch merged to `main`, so the release candidate is on `main`.
