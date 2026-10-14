# Test plan: Restaurant ordering service

| Version | Status | QA lead | Approvers |
|---|---|---|---|
| 0.4 | Draft master plan (Phase 2; built-in sign-in per CR-002); §1 and §5 approved at G1 | TBD | Manh Nguyen (product owner, tech lead), operations owner TBD |

Phase 1 filled §1 and §5. Phase 2 completed §2–§4 and §6–§8 as the master test plan. `ST-` IDs are assigned to the §4 rows in Phase 3.

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

## 3. Environments and load profiles
| Environment | Size vs production | Data | Purpose |
|---|---|---|---|
| CI | Containers | Generated per test | Unit, integration, contract |
| Preview (per PR) | Vercel preview + database branch | Seeded fixtures (restaurants A, B) | Review, smoke tests |
| Staging | Same Vercel plan and region; database the same tier as production; pub/sub and Redis on plans that allow the load test | Synthetic, production volume: 500 restaurants, 25 tables each, 1,000 menu items for the largest, ≈ 1 year of orders (≈ 36M orders, 110M lines) for report and index tests | System, NFR, acceptance |

| Profile | Endpoints and mix | Rate | Duration | Pass criteria |
|---|---|---|---|---|
| Expected peak | Architecture §9 mix (menu 23%, session/bill 23%, catch-up 24%, QR 5%, orders 3%, status 13%, staff lists 5%, tokens 3%, admin 1%) + 15,000 guest and 2,500 staff pub/sub subscribers | 310 req/s | 60 min (a dinner service) | NFR-PERF-01..03 met; errors < 0.1%; outbox backlog < 100 |
| Headroom | Same mix | 600 req/s (≈ 2×) | 30 min | NFR-PERF targets still met |
| Stress | Same mix | Ramp to 5× peak | Until failure | Sheds load with 429/503; recovers without manual action; no lost or duplicate orders |
| Soak | Same mix | Peak | 4 h | No growth in latency, memory, pooler wait or outbox age |
| Noisy tenant | Peak + one restaurant at 10× its share | 310 + 100 req/s | 30 min | Other restaurants within NFR-PERF; noisy one gets 429 |
| Cold burst | 0 → peak in 60 s after 30 min idle | 310 req/s | 10 min | p99 within NFR-PERF after the first minute; cold-start count recorded |

## 4. NFR tests
| NFR ID | Test | Method / tool | Target | Result |
|---|---|---|---|---|
| NFR-PERF-01 | Guest reads at peak | k6 expected-peak profile | p95 ≤ 200 ms, p99 ≤ 500 ms | |
| NFR-PERF-02 | Writes at peak | k6 expected-peak profile | p95 ≤ 300 ms, p99 ≤ 800 ms | |
| NFR-PERF-03 | Live-update delay | k6 subscribers time stamp each notification + catch-up fetch vs the write's response time | p95 ≤ 2 s, p99 ≤ 5 s | |
| NFR-PERF-04 | 31-day report | Report requests on production-sized data | p95 ≤ 3 s | |
| NFR-SCAL-01 | Throughput and connections | Expected peak + headroom profiles; capacity model check | 310 req/s + 17,500 subscribers; 2× headroom | |
| NFR-SCAL-02 | Fairness | Noisy-tenant profile | Others unaffected | |
| NFR-AVAIL-01/02 | SLO measurement works | Run the SLO queries and burn-rate alerts against the load-test traffic with injected errors | Alerts fire at the agreed burn rates | |
| NFR-REL-01 | Restore | Restore staging from PITR to a new branch/instance; time to a working deployment | RPO ≤ 5 min, RTO ≤ 1 h | |
| NFR-REL-02 | Retries and instance loss | Repeat `Idempotency-Key` under load; redeploy during load (forces instance churn) | 0 duplicates, 0 lost acknowledged orders | |
| NFR-REL-03 | Live-update gaps | Disable pub/sub credentials for 5 min under load, then restore; drop client connections at random | Every client's event sequence matches the database; no gaps or duplicates | |
| NFR-SEC-01 | ASVS L2, DAST | ASVS checklist; ZAP API scan with the contract | No high findings; checklist complete | |
| NFR-SEC-02 | Isolation | Authorisation-matrix suite on staging (AT-042) | 0 cross-tenant/session access | |
| NFR-SEC-03 | Audit trail | Each audited action produces an entry; hash chain verifies; app user can't update/delete | All pass | |
| NFR-SEC-04 | Abuse limits | Exceed each limit; admin without MFA | 429 at the limits; admin refused | |
| NFR-PRIV-01 | Retention and minimisation | Retention-job tests with a fixed clock; log sample review for PII; staff export/erasure runbook dry run | No PII in logs; data removed on schedule | |
| NFR-PRIV-02 | Residency | Configuration review of every store, log and trace destination | Regions per TBD-10 | |
| NFR-COMPAT-01 | Compatibility | Contract tests with the oldest client's DTOs; oasdiff | No breaking change | |
| NFR-OBS-01 | Observability | Trace a request ID from client to logs and traces; check dashboards and alerts | Every SLO has an alert and a runbook | |
| NFR-OPS-01 | Deploy and rollback | Deploy and instant-rollback during the expected-peak profile | No failed requests from the deploy; rollback ≤ 10 min | |
| NFR-MAINT-01 | Code quality | CI gates | ktlint/detekt clean; coverage ≥ 80% | |
| NFR-I18N-01 | Time zones and money | Report tests across the UTC day boundary; contract review of money fields | Correct per restaurant time zone | |

## 5. Acceptance outline
Run on staging through the real client apps (customer mobile, customer web, staff app) or API scripts, with two test restaurants (A and B) so that isolation is exercised.

| AT ID | FR | Scenario (Given / When / Then) | Result |
|---|---|---|---|
| AT-001 | FR-001 | Given table A-5 has no open session, when guest 1 scans its QR on the mobile app and guest 2 scans it on the web app, then both are in the same session. When a manager rotates A-5's QR and guest 3 scans the old printout, guest 3 is refused and guests 1 and 2 can still order. | |
| AT-002 | FR-002 | Given restaurant A's menu has a hidden item and a sold-out item, when a guest at A-5 opens the menu, then the hidden item is absent, the sold-out item is marked sold out, and no item of restaurant B appears. | |
| AT-003 | FR-003 | Given a guest at A-5, when they order 2 × item X with a note and the network drops so the app retries with the same key, then exactly one order exists, priced at the menu price, and it shows on the kitchen screen. Ordering a sold-out item is refused and nothing is ordered. | |
| AT-004 | FR-004 | Given A-5 has one order, when guest 2 orders another round, then both orders are on one session and one bill. | |
| AT-005 | FR-005 | Given guests 1 and 2 at A-5 have the order screen open, when the kitchen moves the order to `preparing`, then both screens show `preparing` within 2 s without reloading. After guest 1's phone is offline for a minute and comes back, it shows every change it missed. | |
| AT-006 | FR-006 | Given an order in `placed`, when a guest cancels it, then it leaves the bill and the kitchen sees it cancelled. Given an order in `accepted`, the guest's cancel is refused with "ask staff". | |
| AT-007 | FR-007 | Given A-5 has orders and the manager then raises item X's price, when a guest opens the bill, then lines ordered before the change keep the old price and the total is correct. | |
| AT-010 | FR-010 | Given waiter, kitchen and manager accounts in restaurant A, when each signs in to the staff app, then each sees only their role's functions; the kitchen user can't open menu editing; a deactivated waiter is locked out within 15 min. | |
| AT-011 | FR-011 | Given the kitchen screen of restaurant A is open, when guests at three tables of A and one of B place orders, then A's kitchen shows exactly A's three orders within 2 s, with table numbers, items and notes. After the screen's network is cut and restored, no order is missing or doubled. | |
| AT-012 | FR-012 | Given an order, when the kitchen moves it `accepted → preparing → ready` and a waiter marks it `served`, then each step shows to guests and staff. Two waiters marking it at the same time → one succeeds, the other is told to refresh. Skipping a stage (e.g. `placed → served`) is refused. | |
| AT-013 | FR-013 | Given A-5 has served orders, when the waiter closes the session with "cash, 450,000 VND", then the bill is final, guests 1 and 2 can no longer order, and the next scan of A-5 opens a new, empty session. | |
| AT-020 | FR-020 | Given a manager, when they add a category and an item, edit its price and hide another item, then a guest's menu shows the changes on the next load. A waiter can't edit the menu. | |
| AT-021 | FR-021 | Given item X is on the menu, when the kitchen marks it sold out, then a guest's order containing X is refused; when it is marked available again, the order succeeds. | |
| AT-030 | FR-030 | Given a manager, when they create tables 1–20 and try a duplicate table 5, then the duplicate is refused; deleting a table with guests seated is refused. | |
| AT-031 | FR-031 | Given table A-7, when the manager prints its QR code and a guest scans it with the camera app on a phone with the customer app installed and on one without it, then the first opens the app and the second the web app, both in table A-7's session. | |
| AT-032 | FR-032 | Given A-5 is occupied and A-6 is free, when a waiter moves the session from A-5 to A-6, then the orders and bill are on A-6 and A-5 shows free. Moving onto an occupied table is refused. | |
| AT-040 | FR-040 | Given a platform admin, when they onboard restaurant C and invite its manager, then the manager can sign in and sees an empty restaurant; a manager of A can't reach the admin functions. | |
| AT-041 | FR-041 | Given a manager of A, when they invite a waiter, change them to kitchen and then deactivate them, then each change takes effect and appears in the audit log; the last manager can't remove themselves. | |
| AT-042 | FR-042 | Given staff and guest sessions of restaurant A, when the API scripts replay every endpoint with IDs belonging to restaurant B (orders, tables, items, staff, sessions, reports), then every call returns 404 and nothing in B changes. | |
| AT-044 | FR-044 | Given a manager, when they change the restaurant's opening hours and time zone, then the staff app shows the change; a waiter can't change them. | |
| AT-050 | FR-050 | Given a week of closed bills in restaurant A, some of them crossing midnight UTC, when the manager requests the daily report, then the totals per day (in Asia/Ho_Chi_Minh), per payment method, per item and per table match the bills, and cancelled lines are excluded. | |

## 6. Test data and accounts
- **Restaurants:** A and B with full menus (including hidden, sold-out and option items) and 25 tables each; C created by AT-040.
- **Staff accounts (created by a seed script in the service database, CR-002):** per restaurant, one manager, two waiters and one kitchen user; one deactivated waiter; one locked account; a platform admin with TOTP enrolled and one invited but not yet enrolled. Test TOTP secrets and passwords exist only in staging; codes are generated by the test suite.
- **Synthetic data generator** (a Kotlin task in the repo) for production-volume staging data: restaurants, menus and ≈ 1 year of orders with realistic dinner peaks. Never production personal data.
- **Sandboxes:** pub/sub staging app, Redis staging database, object storage staging bucket, database branch per PR.

## 7. Entry and exit criteria
| Level | Entry | Exit |
|---|---|---|
| Unit, integration, contract | Code in a PR | All pass; coverage ≥ 80% in services and domain; no new detekt/ktlint findings |
| System and NFR | G4 approved; the release-candidate build deployed to staging; staging data loaded | All `ST-` tests run; NFR targets met or gaps accepted in STATUS; no open S1/S2 defects |
| Acceptance | System exit criteria met | Every Must AT passed; product owner signs off |

## 8. Defect severity
| Severity | Meaning |
|---|---|
| S1 | Data loss or corruption, a security issue (including any cross-restaurant access), an outage, lost orders |
| S2 | A Must FR is broken, or an NFR target is missed, with no workaround |
| S3 | A workaround exists |
| S4 | Cosmetic |

Each defect records the request ID and the image or deployment ID.
