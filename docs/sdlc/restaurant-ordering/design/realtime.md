# Feature design: realtime

| Field | Value |
|---|---|
| Version | 1.2 (CR-013, approved 2026-10-18: release 1 has no live push; clients poll, `LIVE_PUSH` setting). 1.1 (CR-009, approved 2026-10-17: aligned with the Phase 4 build). 1.0 Approved at G3 on 2026-10-15 (baseline; changes need a CR). Last draft 0.2: the four §14 questions decided by the owner on 2026-10-15 |
| Owner decisions | §14, all four accepted 2026-10-15 |
| Date | 2026-10-15 |
| Package | `realtime` (architecture §4) |
| Requirements | FR-005 guest live status, FR-008 waiter calls (delivery), FR-011 staff live feed; NFR-PERF-03 (≤ 2 s p95), NFR-REL-03 (no gaps), NFR-SCAL-01 (connections), NFR-AVAIL-02, NFR-SEC-02 (channel isolation), NFR-PRIV-01, NFR-OBS-01 (feed delay) |
| Builds on | architecture.md v1.3 §4, §6, §9, §10; ADR-0003 (Ably, event log, outbox); S-3 (delay, isolation, findings 1–3); [core-and-auth](core-and-auth.md) 0.6; [restaurants](restaurants.md) 0.2 §2 (the event counter moves here); [tables](tables.md) 0.3; [orders](orders.md) 0.2 §9 |
| Platform facts checked 2026-10-15 | Vercel Cron on Hobby runs a job at most once a day, ±59 min, and a more frequent schedule fails the deployment; Pro and Enterprise run every minute ([cron usage and pricing](https://vercel.com/docs/cron-jobs/usage-and-pricing)) |

## 1. Scope
**In:**
- The event log: one row per change, numbered per restaurant, written in the change's own transaction.
- The outbox and publishing to Ably: inline after commit, by later requests, and by the sweep job.
- The catch-up endpoints that clients read after a notification, after a reconnect, or when polling.
- Subscription tokens for Ably.
- What clients do when live delivery fails (the fallback), and the event types of every feature.

**Out:**
- What each change is: the features that write events (`tables`, `menu`, `orders`) define their own types (§5).
- The client apps' code. §7 is the contract they follow.

## 2. How it works
**Release 1 has no live push (CR-013, 2026-10-18).** Staging and production run with `LIVE_PUSH=off`: every event is still written to the log, but no outbox row is written and nothing goes to Ably. Screens read `GET …/events?after=<cursor>` on a timer (§7 "Release 1"), every 15 s for staff and 60 s for guests. The rest of this design describes `LIVE_PUSH=ably`, which the code keeps for the release that adds push (ADR-0003, deferred).

**In plain words:** when something changes, the database writes the change and a numbered note about it ("event 4,812: order X is now version 3"), together, so neither can exist without the other. After the save, the server sends a tiny ping through Ably to the screens that care: "something changed, you're now at 4,812". Each screen then asks the API "what happened after 4,811?" and gets the changes. If a ping gets lost, nothing is lost: the next ping, a reconnect or a poll asks the same question and gets everything since the last number the screen saw.

```mermaid
sequenceDiagram
  participant C as Client (guest or staff)
  participant API as Ktor function
  participant DB as PostgreSQL
  participant A as Ably
  API->>DB: change + domain_events row + outbox row (one transaction)
  API->>A: publish {seq, type, id, version} (after commit, §4.2)
  API->>DB: mark outbox row sent
  A-->>C: notification on session.<id> / restaurant.<id>.staff
  C->>API: GET /v1/.../events?after=<last seq>
  API->>DB: events after seq + current state of the changed orders and calls
  API-->>C: events and snapshots; client applies versions newer than its own
```

**Why the numbers are gap-free.** Each event takes its number from the restaurant's counter row (`UPDATE ... SET last_seq = last_seq + 1 RETURNING last_seq`), and that row stays locked until the transaction commits. So event 4,812 can't become visible before 4,811 is committed or rolled back. A client that has seen 4,812 has seen everything before it (architecture §10).

## 3. Data model
Tenant tables, forced RLS (ADR-0005).

```sql
-- The per-restaurant counter (restaurants §2 moved it here). Created on the first event
CREATE TABLE event_counters (
  restaurant_id  uuid PRIMARY KEY,
  last_seq       bigint NOT NULL
);

CREATE TABLE domain_events (
  restaurant_id      uuid NOT NULL,
  seq                bigint NOT NULL,
  id                 uuid NOT NULL UNIQUE,                    -- UUID v7; Ably message ID (idempotent publish)
  occurred_at        timestamptz NOT NULL,
  type               text NOT NULL,                           -- §5
  aggregate_type     text NOT NULL CHECK (aggregate_type IN ('order', 'bill', 'table_session', 'waiter_call', 'menu')),
  aggregate_id       uuid NOT NULL,
  aggregate_version  integer,
  session_id         uuid,                                    -- set when guests of that session see it
  audience           text NOT NULL CHECK (audience IN ('staff', 'staff_and_session')),
  PRIMARY KEY (restaurant_id, seq),
  CHECK (audience = 'staff' OR session_id IS NOT NULL)
);
CREATE INDEX domain_events_session_ix ON domain_events (restaurant_id, session_id, seq) WHERE session_id IS NOT NULL;
CREATE INDEX domain_events_age_ix ON domain_events (occurred_at);

CREATE TABLE outbox (
  event_id         uuid PRIMARY KEY REFERENCES domain_events (id) ON DELETE CASCADE,
  restaurant_id    uuid NOT NULL,
  created_at       timestamptz NOT NULL,
  published_at     timestamptz,
  attempts         smallint NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL
);
CREATE INDEX outbox_pending_ix ON outbox (next_attempt_at) WHERE published_at IS NULL;
CREATE INDEX outbox_pending_restaurant_ix ON outbox (restaurant_id, next_attempt_at) WHERE published_at IS NULL;
```

- **The counter is created lazily:** `INSERT INTO event_counters VALUES (:rid, 1) ON CONFLICT (restaurant_id) DO UPDATE SET last_seq = event_counters.last_seq + 1 RETURNING last_seq`. One statement, no onboarding step.
- **Nothing personal is stored or sent.** An event names a type, an ID and a version. The change itself stays in its feature's tables, behind the API's checks.
- **Retention:** events and sent outbox rows are deleted after 7 days by the purge job (architecture §6). Deleting an event deletes its outbox row (`ON DELETE CASCADE`).
- **Volume:** ≈ 500k events a day at 500 restaurants (architecture §6), so ≈ 3.5 M rows in the 7-day window.

## 4. Writing and publishing
### 4.1 `EventLog.append` (the port every feature calls)
The `OrderEvents`, `SessionEvents` and `MenuEvents` ports from the other designs are one interface here: `EventLog.append(restaurantId, type, aggregate, sessionId?, audience)`. It runs inside the caller's transaction and:
1. takes the next `seq` from the counter (the lock is held until commit);
2. inserts the `domain_events` row and its `outbox` row (`next_attempt_at` = now);
3. remembers the event in the request context, so the request can publish it after commit (§4.2).

With `LIVE_PUSH=off` (release 1, CR-013) it does step 1 and inserts only the `domain_events` row: no outbox row, nothing to publish. So the backlog claim (§4.3), the sweep and the circuit breaker find no work, and no publish failure is ever counted.

The counter comes after the business rows and before the audit chain head in the lock order (CLAUDE.md; orders §3.1). A busy restaurant writes about 1–2 events a second at peak, and each holds the counter for the few milliseconds until its commit, so they queue briefly and never wait on each other in a circle.

### 4.2 Publishing after commit
- **One Ably batch request per event** covers both channels: `restaurant.<rid>.staff` always, and `session.<sid>` when `audience` = `staff_and_session` (S-3 finding 1: this halves the request count).
- **Message:** name = `type`, `id` = the event ID, data = `{"seq", "type", "id", "version", "session_id"?}`. Because Ably treats a repeated message ID as the same message, a retry never shows twice (Ably's idempotent publishing).
- **Inline, after commit and before the response** (§14 Q2): one try with a 300 ms timeout; on Ably's `42918` (rate limit), one more try after 50 ms. On success, `UPDATE outbox SET published_at = now()` in its own short statement. On failure the row stays for the sweep, and the request still succeeds (S-3 finding 2).
  - From `sin1` to Ably's Singapore endpoint this should add ≈ 5–15 ms (S-3 measured 35 ms p50 from Vietnam, which includes the longer leg). Phase 4 measures it.
  - A per-instance circuit breaker (architecture §10) skips the inline try for 30 s after 5 failures in a row; the rows wait for the next publisher.
  - A request publishes its events in order and stops at the first failure: when Ably is down, it isn't sent the rest, each of which could wait 300 ms. Those rows wait for the next publisher (CR-009, Q-14).
- **No remote call inside a transaction** (architecture §9): publishing always happens after commit.
- **The `LIVE_PUSH` setting** (`off` | `ably`, CR-013) has no default outside dev and test: staging and production must name it, so a missing key can never switch push off quietly. `ably` requires `ABLY_API_KEY`, as before; `off` ignores it. In dev and test it defaults to `ably` when a key is set and `off` otherwise.

### 4.3 Catching up on missed publishes
Rows that weren't published (Ably down, a refused request, an instance that died after commit) are picked up three ways:
1. **By later requests of the same restaurant.** After its own inline publish, a request claims up to 20 of the restaurant's rows that are at least 10 s overdue and publishes them too. A busy restaurant clears its backlog within seconds once Ably is back. Skipped while the circuit breaker is open, like the inline try (CR-009, Q-14).
2. **By the sweep job** `/internal/jobs/outbox-sweep` (architecture §10): every minute on Pro, for restaurants that went quiet. A run stops at its first failure. It ignores the circuit breaker: it is the probe that finds Ably back (CR-009, Q-14).
3. **By the clients themselves** (§7): a reconnect, the safety poll and the fallback poll all read the event log, which never depends on Ably.

**Claiming without holding a transaction open during the call:**
```sql
UPDATE outbox SET attempts = attempts + 1,
                  next_attempt_at = now() + least(interval '5 minutes', interval '10 seconds' * 2 ^ attempts)
WHERE event_id IN (SELECT event_id FROM outbox
                   WHERE published_at IS NULL AND next_attempt_at <= now()
                   ORDER BY next_attempt_at LIMIT 100 FOR UPDATE SKIP LOCKED)
RETURNING event_id
```
The claim commits at once, then the events are published, then marked sent. Two sweepers can't claim the same row (`SKIP LOCKED`), and a sweeper that dies leaves its rows to come back after the backoff. The job uses `platformTransaction` (it spans restaurants, core-and-auth §7) and `pg_try_advisory_xact_lock` so one run works at a time (architecture §10). Rows are published in `seq` order within a restaurant, but clients don't depend on order: they read the log.

**On the free Hobby account** Vercel runs a cron job at most once a day (§14 Q4). There, way 1 and the clients' polls carry the load, and the daily sweep only tidies up. This is a free-plan limit, not a production finding (CLAUDE.md).

## 5. Event types
| Event | Written by | Aggregate | Audience |
|---|---|---|---|
| `TableSessionOpened` | tables §3.1, §3.4 | table_session | staff |
| `TableSessionMoved` | tables §3.4 | table_session | staff and session |
| `TableSessionClosed` | tables §3.5, §7 | table_session | staff and session |
| `WaiterCalled` | tables §4 | waiter_call | staff |
| `WaiterCallAcknowledged` | tables §4 | waiter_call | staff and session |
| `MenuChanged` | menu (every write) | menu (`menu_state.version`) | staff |
| `OrderPlaced` | orders §3 | order | staff and session |
| `OrderStatusChanged` | orders §5 | order | staff and session |
| `OrderLineRejected` | orders §5.3 | order | staff and session |
| `BillClosed` | orders §7.1 | bill | staff |
| `BillAdjusted` | orders §7.2 | bill | staff |

- **Guests aren't sent `MenuChanged`.** It would mean one publish per open session of the restaurant. Guests see sold-out items on their next menu load (an `ETag` check) or when an order is refused with `item_unavailable` (orders §3.1).
- **New types are additive.** Clients ignore types they don't know, apart from moving their cursor (§7). A new type is not a breaking change within `/v1`.

## 6. Reading: the catch-up endpoints
### 6.1 `GET /v1/guest/events?after=<seq>&limit=100`
The session's events after `seq`, oldest first, plus the current state of each order and waiter call they name:

```json
{
  "events": [
    { "seq": 4811, "type": "OrderPlaced", "aggregate": { "type": "order", "id": "0192...", "version": 0 }, "occurred_at": "2026-10-15T12:05:00.120Z" },
    { "seq": 4812, "type": "OrderStatusChanged", "aggregate": { "type": "order", "id": "0192...", "version": 1 }, "occurred_at": "2026-10-15T12:05:41.003Z" }
  ],
  "orders": [ { "id": "0192...", "status": "accepted", "version": 1, "...": "the guest view from orders §4" } ],
  "waiter_calls": [],
  "cursor": 4812,
  "has_more": false
}
```

- **Snapshots, not history.** `orders` holds each named order once, in its current state (here version 1, not 0), so the client applies one state per order and keeps whichever version is higher. A burst of 10 events on one order costs one order in the answer.
- **`cursor`** is the restaurant's latest `seq` read when there is no next page (the page was read up to it, so a quiet client's cursor still moves forward), otherwise the highest `seq` in the answer. For a guest, that also skips other sessions' events after their last one (CR-009, Q-14). The client sends it back as `after` next time.
- **Without `after`** the answer is empty with `cursor` = the latest `seq`: the starting point (§7).
- **`after` older than the oldest kept event** (7 days), or ahead of the latest: `reset: true` and no events. The client reloads its lists and starts again from `cursor`. A guest session can't be 7 days old in practice, but a staff tablet left off for a week can.
- **Session filter:** `session_id` = the caller's session. Events of other sessions are skipped, so a guest's cursor jumps over them.
- Allowed when the restaurant is suspended, like the other guest reads (restaurants §6).

### 6.2 `GET /v1/staff/events?after=<seq>&limit=100`
The same for the whole restaurant, all roles: every event (§5), with `orders` (staff view) and `waiter_calls` snapshots. Other aggregates (sessions, bills, menu) are signals: the staff app re-reads the floor view, the bill or the menu when it sees them. Not allowed when suspended.

### 6.3 How the snapshots are loaded
`realtime` doesn't read other features' tables (architecture §4). It defines a `Snapshots` port, which `orders` (`Order` views) and `tables` (`WaiterCall` views) implement, each loading a batch by ID in one query with `id = ANY(?)`. So a catch-up call is 3–4 queries: the events, then one batch per aggregate type that appears.

**Page size:** `limit` 1–200, default 100. A page of 100 events names at most 100 orders, each ≤ 50 lines, well under Vercel's 4.5 MB response cap (CR-004).

### 6.4 Endpoints
All of these go into `core/http/Endpoints.kt` and the contract (tag `realtime`).

| Method and path | Who | Idempotency / If-Match | Allowed when suspended | Success |
|---|---|---|---|---|
| `GET /v1/guest/events` | Guest | — | Yes | 200 |
| `POST /v1/guest/realtime-token` | Guest | — (each call issues a fresh token request) | No | 200 |
| `GET /v1/staff/events` | Staff, all roles | — | No | 200 |
| `POST /v1/staff/realtime-token` | Staff, all roles | — | No | 200 |

The job routes (§10) are in the registry under the cron-secret group and not in the public contract. No new error codes: a bad `after` or `limit` is `400 validation_failed`.

## 7. What clients do (the contract the apps follow)
**Start:**
1. `GET .../events` without `after` → keep `cursor`.
2. Load the screen's state: the guest's orders and bill, or the kitchen's order list (orders §4).
3. Get a token (§8) and subscribe to the one channel.
4. `GET .../events?after=<cursor>` once, to cover anything between steps 1 and 3. Replays are harmless: versions decide.

**While connected:**
- On each notification whose `seq` > `cursor`: catch up from `cursor`. If a catch-up is already running, run one more when it ends; at most one every 500 ms.
- **Staff screens also poll every 60 s** (§14 Q1), a safety net for the kitchen if a ping is lost while Ably looks connected (NFR-REL-03: "the kitchen can always fall back to reading the list").
- When the app comes back to the foreground (a phone woke up, a tab became visible): catch up.

**When live delivery is down** (Ably reports the connection `suspended` or `failed`, or the token endpoint fails): poll `GET .../events?after=<cursor>` every **15 s for staff and 60 s for guests** (±20 % random jitter, so screens don't poll in step), until Ably is `connected` again; then catch up once and stop polling (§14 Q1).

**Release 1 (CR-013): no subscription.** Clients skip steps 3 and 4 of "Start" and the realtime-token call, and stay in the "live delivery is down" mode for good: poll `GET .../events?after=<cursor>` every **15 s for staff and 60 s for guests** (±20 % jitter). They also catch up at once after the screen's own write and when the app comes to the foreground, so a guest sees their own order and a cook their own status change without waiting for the timer. A client written to the full rules above still works: the token endpoint's `503` sends it to polling.

**Rules for every client:**
- Ignore event types and fields it doesn't know (core-and-auth §2), but still move `cursor`.
- Apply a snapshot only if its `version` is higher than the one held.
- On `reset: true`, reload the screen's state.
- On `401 table_session_closed` (guests), stop: the visit has ended (tables §3.2).

**Load if Ably is down for everyone** (architecture §9's peak): 2,500 staff screens every 15 s ≈ 167 req/s, 15,000 guest devices every 60 s ≈ 250 req/s. With the rest of the peak mix (≈ 180 req/s without the normal catch-up) that's ≈ 600 req/s, the 2× headroom the load test must pass. Live delay in that mode is up to 15 s for the kitchen and 60 s for guests: NFR-PERF-03 is missed for the length of the outage, and that time counts against the SLO budget. The staff safety poll adds ≈ 42 req/s while things are healthy.

## 8. Subscription tokens
**In plain words:** Ably doesn't know our users. The API gives each screen a short-lived Ably pass that lets it **listen** on exactly one channel, and nothing else. The pass is signed with our Ably key, and the key never leaves the server.

- **`POST /v1/guest/realtime-token`** → a token request for `{"session.<sid>": ["subscribe"]}`, the session from the caller (never from the body).
- **`POST /v1/staff/realtime-token`** (all roles) → `{"restaurant.<rid>.staff": ["subscribe"]}`.
- **Format:** an Ably *TokenRequest*, created and signed locally by the official Ably Java SDK (`createTokenRequest`), with no network call. The client's Ably SDK exchanges it for a token in its `authCallback`. No custom cryptography (core-and-auth §8).
  - Its fields keep Ably's own camelCase names (`keyName`, `ttl`, `capability`, `timestamp`, `nonce`, `mac`), because the SDK reads them as they are. It's the one exception to the `snake_case` rule (core-and-auth §2), marked in the contract.
- **Lifetime** (§14 Q3): guests 30 min, staff 60 min. Ably's SDK asks for a new one before expiry. A closed session can't get a new one (the guest call answers `401 table_session_closed`), so its devices stop receiving within 30 min. Until then they only get IDs, which they can't read with a closed session.
- **No `clientId`.** Nothing about the device or the staff member goes to Ably.
- **Suspended restaurant:** no new tokens (`403 restaurant_suspended`); existing ones run out (restaurants §6).
- **`LIVE_PUSH=off` (release 1, CR-013):** both token routes answer `503 service_unavailable` (core-and-auth §3's shared code) after the usual authentication. Release-1 clients don't call them.
- **The API key** (`ABLY_API_KEY`, a Vercel sensitive variable) is limited to `{"restaurant.*": ["publish", "subscribe"], "session.*": ["publish", "subscribe"]}`, so even a leaked token request can't reach anything else. One Ably app per environment. Creating the key and storing it is an owner step (CLAUDE.md: Claude doesn't write secrets to a secret store).
- Isolation was verified in S-3 (Q3, 9 of 9): a session token is refused on every other channel and action.

## 9. Rate limits (§14 Q1)
| Policy | Subject | Limit |
|---|---|---|
| `realtime.token` | Guest session | 30 / 1 h |
| `realtime.token` | Staff member | 60 / 1 h |
| `events.read` | Guest session | 120 / 1 min |
| `events.read` | Staff member | 240 / 1 min |

The client rules in §7 stay far below these. Over a limit → `429` with `Retry-After`, and the client waits that long before its next catch-up.

## 10. Jobs
| Job | Schedule on Pro (production) | On the free Hobby account | Work |
|---|---|---|---|
| `outbox-sweep` | Every minute | Once a day | §4.3, batches of 100 within the function's time limit |
| `event-purge` | Hourly | Once a day | Delete events older than 7 days in batches of 5,000 (`domain_events_age_ix`); their outbox rows go with them |

Both are idempotent, hold `pg_try_advisory_xact_lock`, and are called by Vercel Cron with the cron secret (architecture §10). The schedule is set per environment in `vercel.json`, because a per-minute schedule fails to deploy on Hobby.

## 11. Observability (NFR-OBS-01)
- **`realtime_publish_delay_seconds`**: from `occurred_at` to the publish that succeeded, by path (inline, by a request, sweep). The inline share should be > 99 %.
- **`realtime_outbox_pending`** and the age of the oldest unsent row, read by the sweep. Alert: oldest unsent row older than 2 min (production).
- **`realtime_publish_failures_total`** by Ably error code (`42918` rate limit, timeouts, circuit open).
- **Feed delay for NFR-PERF-03** is measured end to end by the load test's live clients (test-plan), because the server can't see when a phone received a message.
- **With `LIVE_PUSH=off`** (release 1) the three meters above stay at zero, the outbox alert and the late-publish half of the order-flow SLO don't apply, and the SLO counts failed order placements only (`deploy/observability/rules.yaml`).

## 12. Code layout
```
<base>.realtime
  domain/        EventType, Audience, Cursor, ChannelNames
  application/   EventLog (append; the port behind OrderEvents, SessionEvents, MenuEvents),
                 Publisher (inline publish, opportunistic catch-up, circuit breaker),
                 OutboxSweepJob, EventPurgeJob, CatchUpService, TokenService;
                 Snapshots (port implemented by orders and tables), RealtimePublisher (port; Ably adapter)
  persistence/   EventRepository, OutboxRepository, CounterRepository
  ably/          AblyPublisher (REST batch publish, idempotent IDs), AblyTokenIssuer (createTokenRequest)
  http/          GuestEventRoutes, StaffEventRoutes, TokenRoutes, JobRoutes (paths from Endpoints.kt)
```

## 13. Tests
| ID | Case | Req |
|---|---|---|
| IT-RT-01 | An order change writes its event and outbox row in the same transaction; a rolled-back change writes neither | NFR-REL-03, ADR-0003 |
| IT-RT-02 | 50 concurrent writes in one restaurant → `seq` 1–50 with no gaps or repeats; a reader polling meanwhile never sees seq n+1 before n | NFR-REL-03 |
| IT-RT-03 | Catch-up: events after the cursor, oldest first; one snapshot per order at its latest version; `cursor` moves on with no events; `has_more` pages | FR-005, FR-011 |
| IT-RT-04 | Guest catch-up skips other sessions' events; staff-only events (`WaiterCalled`, `BillClosed`, `MenuChanged`) never reach guests | NFR-SEC-02 |
| IT-RT-05 | Another restaurant's events never appear in staff catch-up; a token for restaurant A names only A's channel | FR-042, NFR-SEC-02 |
| IT-RT-06 | `after` older than the oldest kept event, or ahead of the latest → `reset: true` | NFR-REL-03 |
| IT-RT-07 | Ably fake down: requests still succeed; rows stay unsent; a later request of the same restaurant publishes them; the sweep publishes the rest; each event is published with its own ID (no duplicates at the fake) | NFR-REL-03, NFR-AVAIL-02 |
| IT-RT-08 | `42918` from the fake → one retry after 50 ms; two sweepers at once never claim the same row | NFR-REL-03, NFR-AVAIL-02 (ADR-0003) |
| IT-RT-09 | Token: the guest gets exactly `session.<sid>: subscribe`; the staff member gets exactly `restaurant.<rid>.staff: subscribe`; a closed session → `401`; suspended → `403`; no `clientId` | NFR-SEC-02, FR-043 |
| IT-RT-10 | Resilience (Phase 5, ST-10): screens pause and catch up under load, then every screen's events are compared with the database; no missing or extra events. Release 1: polling screens (CR-013) | NFR-REL-03 |
| IT-RT-11 | Live delay end to end (Phase 5, ST-03). Release 1: polling screens against NFR-PERF-03's release-1 target (CR-013); p95 ≤ 2 s at peak waits for live push (R-13) | NFR-PERF-03 |
| IT-RT-12 | Purge: events older than 7 days are deleted with their outbox rows; a client with an older cursor gets `reset` | NFR-REL-03, NFR-PRIV-01 (architecture §6) |
| IT-RT-13 | No personal data in events, Ably messages or tokens (names, notes, IPs, device IDs) | NFR-PRIV-01 |
| IT-RT-14 | `LIVE_PUSH=off`: a change writes its event and no outbox row, nothing reaches the fake, and catch-up still returns it; both token routes → `503 service_unavailable`. Outside dev, a missing or unknown `LIVE_PUSH` stops the start, and `ably` without a key does too (CR-013) | NFR-REL-03, FR-005, FR-011 |

## 14. Owner decisions (2026-10-15)
The owner accepted all four recommendations. They're recorded in STATUS.md (Decisions; Q-7 resolved).

1. **Polling and rate limits:** while live delivery is down, staff poll every 15 s and guests every 60 s; staff also poll every 60 s while connected; the limits in §9. At peak with Ably down for everyone, that's ≈ 600 req/s, the load test's 2× headroom (§7).
2. **Publish inline after commit, before the response,** with a 300 ms cap (≈ 5–15 ms expected from `sin1`). Publishing after the response would add nothing to the request, but whether Vercel's container runtime keeps running code after the response is sent isn't known. Phase 4 checks it, and if it does, the publish moves after the response without any contract change.
3. **Token lifetimes:** guests 30 min, staff 60 min. A closed session's devices keep receiving bare IDs for up to 30 min, which they can't use. Ably's revocable tokens aren't needed.
4. **Free-plan cron:** on the Hobby test account every job runs once a day (§10), so the outbox is also published by later requests (§4.3) and clients poll (§7). Production on Pro keeps the per-minute sweep. The same limit affects the tables auto-close job (tables §3.5: hourly on Pro, daily on Hobby) and the purge jobs.

## 15. Changes this design makes elsewhere
- **restaurants §2:** the counter table is `event_counters`, created on the first event, so onboarding needs no step for it.
- **orders §9, tables, menu:** their event ports are `EventLog.append`; `orders` and `tables` implement `Snapshots` (§6.3).
- **CLAUDE.md:** a spike and platform gotcha: Vercel Hobby cron runs at most daily, and a more frequent schedule fails the deployment.

## 16. Traceability
The RTM's design column for FR-005, FR-011, NFR-PERF-03 and NFR-REL-03 points here; FR-008, FR-042, NFR-SEC-02, NFR-PRIV-01 and NFR-OBS-01 add this design.
