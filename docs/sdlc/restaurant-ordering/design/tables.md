# Feature design: tables

| Field | Value |
|---|---|
| Version | 0.2 Draft: CR-005 approved and the six §13 questions decided by the owner on 2026-10-15; the whole design is approved at G3 |
| Date | 2026-10-15 |
| Package | `tables` (architecture §4) |
| Requirements | FR-001 QR → session, FR-008 waiter call (Should), FR-030 tables, FR-031 QR codes, FR-032 floor view and sessions; FR-042, NFR-SEC-02 (session isolation), NFR-SEC-04 (scan limits), NFR-PRIV-01 |
| Builds on | architecture.md v1.2 §4, §7, §10; ADR-0006 (guest tokens); ASSUMPTION-6; R-02; [core-and-auth](core-and-auth.md) 0.5; [restaurants](restaurants.md) 0.2 |
| Change request | [CR-005](../cr/CR-005-natural-idempotency-and-session-data.md), approved 2026-10-15: architecture §10's `Idempotency-Key` list, §7's guest-token claims, and the table-session row of requirements §5 |
| Owner decisions | §13, all six accepted 2026-10-15 |

## 1. Scope
**In:**
- Dining tables: create, edit, disable, delete.
- QR codes: the token format, printing, rotating one table, rotating all.
- Table sessions: open and join from a QR code, staff open without a scan, move; the guest token and the per-call check.
- Waiter calls and bill requests (FR-008).
- The floor view (FR-032).
- The `SessionAccess` port that `orders` uses.

**Out:**
- **Closing a session** (FR-013) is part of the `orders` design, because it finalises the bill and records the payment (TBD-7). This design defines the session states and the `SessionAccess.close` step that `orders` calls.
- Orders and the bill shown to guests (`orders`).
- How events reach screens (`realtime`).

## 2. QR codes (FR-031, ADR-0006)
**In plain words:** the QR on a table holds a web address with a token in it. The token says "restaurant R, table T, version 3" and carries a digital signature that only our server can make, so nobody can make up a code for another table. Rotating a table's code bumps its version to 4. After that, every printed code with version 3 is refused at once, while guests already seated keep their session.

- **URL:** `https://<customer app domain>/t/<qrToken>`. The customer web app's domain serves the App Links / Universal Links files, so a phone with the app installed opens the app and any other phone opens the web app (ADR-0006, AT-031). Both send the same token to the API.
- **Token:** `<base64url(payload)>.<base64url(signature)>`.
  - The payload is 38 bytes: a format byte (`1`), the key ID byte, the restaurant ID (16), the table ID (16) and the table's `qr_key_version` (uint32).
  - The signature is ES256 (64 bytes) over the payload, with the **QR key set**. That set is separate from the guest-session keys and from the staff keys (ADR-0006, ADR-0011).
  - The whole URL is about 165 characters, a version 9 QR code (53 × 53 modules) at error-correction level M. Printed at 3 cm or larger, it scans easily.
- **Verification**, before any database work:
  - format and length;
  - known `kid`;
  - signature.

  Any failure → `400 qr_token_invalid`. Then the database check: the table exists in that restaurant, isn't deleted, and its `qr_key_version` equals the token's; otherwise the same `400 qr_token_invalid`. That way a forged, rotated or deleted-table code reveals nothing.
- **Nothing secret is stored.** The token can be rebuilt at any time from (restaurant, table, version) and the signing key, so the printable code can be fetched again whenever needed (§6). ECDSA signatures differ each time, but all of them verify.
- **QR keys aren't retired while printed codes exist** (§13 Q5). A new signing key is used for codes made from then on, but old public keys stay in the set, otherwise every printed code would stop working. If a QR signing key leaks:
  - add a new key;
  - rotate every table (`rotate-all`, §6), which makes every old code fail;
  - reprint.

  That's the "QR leak" runbook in architecture §13.

## 3. Table sessions
**States:** `open` → `closed`. That's all. "Moved" is a change of `table_id` on an open session, not a state.

**One open session per table**, enforced by the database: a partial unique index on `table_sessions (table_id) WHERE status = 'open'`.

### 3.1 Open or join: `POST /v1/guest/sessions`
Body `{"qr_token", "device_id"}`. `device_id` is a random UUID that the app creates once and keeps (the web app keeps it in `sessionStorage`). It only identifies the device for the token's `sub` and for rate limits; it isn't stored (§13 Q3).

Steps:
1. Rate limits: per device, per table and per IP (§9).
2. Verify the QR token (§2).
3. Set the tenant from the token's restaurant ID and check the restaurant: suspended → `403 restaurant_suspended` (FR-043).
4. Check the table: disabled → `409 table_unavailable`.
5. Find the table's open session. If there is none, insert one.
   - **Two first scans at the same moment** both try to insert. The unique index lets one through; the other gets a unique violation, rolls back to a savepoint, and joins the session the first one created.
   - Event `TableSessionOpened` (with its outbox row) only when a session was created.
6. Mint a session token for this device and session. Answer `200`:

```json
{
  "session_token": "eyJ...", "token_type": "Bearer", "expires_in": 21600,
  "session": {
    "id": "0192...", "opened_at": "2026-10-15T12:01:02.000Z",
    "table": { "id": "0192...", "number": "A-5" },
    "restaurant": { "id": "0192...", "name": "Phở Hà Nội", "languages": ["vi", "en"] }
  }
}
```

**Retries.** The same QR token from the same device twice → the same session, with a fresh token (FR-001). That follows from step 5, so this operation needs no `Idempotency-Key`. That's also why no answer is stored: storing it would keep a bearer token in `idempotency_keys` for 24 h (§13 Q1, CR-005).

### 3.2 The session token and the per-call check
- **The token:**
  - ES256 JWT with the guest key set and `kid` in the header;
  - claims `iss`, `aud` = `guest`, `sub` = device ID, `sid` = session ID, `rid` = restaurant ID, `iat`, `exp` = `iat` + 6 h;
  - no table claim, because a session can move (FR-032).
- **The guest authentication provider** (the `/v1/guest/*` route group):
  1. verify the signature, `aud`, `iss` and `exp` (30 s leeway) → otherwise `401 unauthenticated`;
  2. in a short transaction for `rid`:
     ```sql
     SELECT s.status, s.table_id, r.status AS restaurant_status
     FROM table_sessions s JOIN restaurants r ON r.id = s.restaurant_id
     WHERE s.id = :sid
     ```
  3. no row, or `status = 'closed'` → `401 table_session_closed`, so the app can say "this visit has ended, scan the code again" (§13 Q6);
  4. a suspended restaurant and an endpoint without `allowedWhenSuspended` → `403 restaurant_suspended`;
  5. `Caller.Guest(deviceId, sessionId, tableId from the row, restaurantId)`.
- **Closing a session cuts off every token at once** (ADR-0006), because each call reads `status`.
- **After 6 h** the token expires while the session may still be open. The guest scans the code again and gets a new token for the same session.

### 3.3 Read: `GET /v1/guest/session`
Returns the session as in §3.1 (without a token), plus the guest's open waiter calls. The bill and orders are in the `orders` design. Allowed when suspended.

### 3.4 Staff: open without a scan, move
- **`POST /v1/staff/tables/{tableId}/session`** (waiter, manager; FR-032):
  - opens the table's session, or returns the open one: `201` when created, `200` when it existed;
  - naturally idempotent, so no `Idempotency-Key`;
  - for guests without a phone, followed by staff orders (FR-014);
  - audit `table_session.opened_by_staff`; event `TableSessionOpened` when created;
  - a disabled table → `409 table_unavailable`.
- **`POST /v1/staff/table-sessions/{sessionId}/move`** `{"to_table_id"}` + `If-Match` (waiter, manager):
  - locks the session row (`FOR UPDATE`) and checks:
    - session open → otherwise `409 session_not_open`;
    - `If-Match` current → otherwise `412` (FR-032: "concurrent moves → only one succeeds");
    - target table enabled and not deleted → otherwise `409 table_unavailable`;
    - target free → otherwise `409 table_occupied` (the unique index guarantees it even under a race);
    - not the same table → `400 validation_failed`;
  - then sets `table_id`;
  - its orders and bill move with it, because they belong to the session, not the table;
  - audit `table_session.moved` (from → to); event `TableSessionMoved`;
  - guests' tokens keep working, and their next call sees the new table.
- **`GET /v1/staff/table-sessions/{sessionId}`** (waiter, manager, kitchen): the session with its table and `version`, plus the bill summary from `orders`.

### 3.5 Abandoned sessions (§13 Q2)
A session that's never closed would make the next guests at that table join the last guests' bill.
- **No orders:** the hourly job closes an open session with no orders (or only cancelled ones) after 2 h without activity (`last_activity_at`). Audit actor `system`, event `TableSessionClosed`.
- **With orders:** a session is never closed automatically, because closing means settling money (FR-013). The floor view (§6) flags it `stale: true` once it has been open more than 6 h, so staff close it.

## 4. Waiter calls (FR-008, Should)
- **`POST /v1/guest/waiter-calls`** `{"kind": "call_waiter" | "request_bill"}`:
  - **Merging (FR-008):** if the session already has an unacknowledged call of the same kind, or one of that kind made less than 60 s ago, return it with `200`. Otherwise create one and return `201`. So repeated taps make one call, and a retry is harmless without an `Idempotency-Key` (CR-005).
  - Event `WaiterCalled` (table number, kind) to the restaurant's staff.
  - Limit: at most 10 calls per session per hour (`429`).
- **`GET /v1/staff/waiter-calls?status=open`** (waiter, manager): oldest first, with the table number.
- **`POST /v1/staff/waiter-calls/{callId}/acknowledge`** (waiter, manager):
  - idempotent: acknowledging twice gives the same result;
  - records who and when, and sends `WaiterCallAcknowledged`, so every waiter's screen clears it;
  - the guest app shows "a waiter is coming" from `GET /v1/guest/session`.
- When a session closes, its open calls are closed with it (`status = 'closed_with_session'`).

## 5. Data model
Tenant tables: `restaurant_id` on every row, forced RLS (ADR-0005).

```sql
CREATE TABLE dining_tables (
  id              uuid PRIMARY KEY,
  restaurant_id   uuid NOT NULL,
  number          text NOT NULL CHECK (char_length(number) BETWEEN 1 AND 20),  -- "5", "A-5", "Garden 2"
  area            text CHECK (char_length(area) <= 50),
  seats           smallint CHECK (seats BETWEEN 1 AND 50),
  enabled         boolean NOT NULL DEFAULT true,
  qr_key_version  integer NOT NULL DEFAULT 1,               -- +1 on rotation; printed codes carry it
  deleted_at      timestamptz,
  version         integer NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL
);
CREATE UNIQUE INDEX dining_tables_number_uq ON dining_tables (restaurant_id, lower(number)) WHERE deleted_at IS NULL;

CREATE TABLE table_sessions (
  id                uuid PRIMARY KEY,
  restaurant_id     uuid NOT NULL,
  table_id          uuid NOT NULL REFERENCES dining_tables (id),
  status            text NOT NULL CHECK (status IN ('open', 'closed')),
  opened_at         timestamptz NOT NULL,
  opened_by         uuid,                                   -- staff ID; NULL when a guest scanned
  last_activity_at  timestamptz NOT NULL,                   -- join, order, call (§3.5)
  closed_at         timestamptz,
  closed_by         uuid,                                   -- staff ID; NULL = system (§3.5)
  close_reason      text CHECK (close_reason IN ('paid', 'abandoned_empty', 'closed_by_staff')),
  version           integer NOT NULL DEFAULT 0,
  CHECK ((status = 'closed') = (closed_at IS NOT NULL))
);
CREATE UNIQUE INDEX table_sessions_one_open_uq ON table_sessions (table_id) WHERE status = 'open';
CREATE INDEX table_sessions_open_ix ON table_sessions (restaurant_id, status, last_activity_at) WHERE status = 'open';
CREATE INDEX table_sessions_closed_ix ON table_sessions (restaurant_id, closed_at);

CREATE TABLE waiter_calls (
  id               uuid PRIMARY KEY,
  restaurant_id    uuid NOT NULL,
  session_id       uuid NOT NULL REFERENCES table_sessions (id),
  kind             text NOT NULL CHECK (kind IN ('call_waiter', 'request_bill')),
  status           text NOT NULL CHECK (status IN ('open', 'acknowledged', 'closed_with_session')),
  created_at       timestamptz NOT NULL,
  acknowledged_at  timestamptz,
  acknowledged_by  uuid
);
CREATE UNIQUE INDEX waiter_calls_one_open_uq ON waiter_calls (session_id, kind) WHERE status = 'open';
CREATE INDEX waiter_calls_open_ix ON waiter_calls (restaurant_id, created_at) WHERE status = 'open';
```

- **`table_qr_keys` (architecture §4) isn't needed.** The table's `qr_key_version` and the QR key set in configuration are the whole mechanism. This finalises §4's table list, as it allows.
- **No device data is stored** (§13 Q3). Requirements §5 lists a "device token" for table sessions; with this design there's none, so the row's personal data and its 90-day removal step go away (CR-005). Retention for sessions follows the bill they belong to (`orders` design, TBD-4).
- **Limits:** ≤ 300 tables per restaurant (ASSUMPTION-4) → `409 table_limit_reached`.

## 6. Tables and the floor view
| Operation | Request | Who | Rules |
|---|---|---|---|
| Floor view | `GET /v1/staff/tables` | Waiter, manager | Every undeleted table, sorted by `area`, then `number`, each with `state: "free" \| "occupied"`. An occupied table also shows `session: {id, opened_at, order_count, running_total, stale, open_calls}` (FR-032). The counts and totals come from `orders` through the `SessionSummaries` port, in one batch call for all open sessions |
| Create | `POST /v1/staff/tables` `{"number", "area"?, "seats"?}` | Manager | Duplicate number (case-insensitive) → `409 duplicate_name` |
| Read | `GET /v1/staff/tables/{tableId}` | Waiter, manager | `ETag` |
| Edit | `PATCH /v1/staff/tables/{tableId}` + `If-Match` | Manager | `number`, `area`, `seats`, `enabled`. Disabling with an open session → `409 table_has_open_session` (FR-030) |
| Delete | `DELETE /v1/staff/tables/{tableId}` + `If-Match` | Manager | Soft delete. With an open session → `409 table_has_open_session`. The table's printed codes stop working |
| QR code | `GET /v1/staff/tables/{tableId}/qr-code` | Manager | `{"url", "key_version"}`. The staff app draws the QR image itself |
| All QR codes | `GET /v1/staff/tables/qr-codes` | Manager | The same for every table, for printing a sheet |
| Rotate | `POST /v1/staff/tables/{tableId}/qr-code/rotate` | Manager | `qr_key_version += 1` → returns the new code. Guests already in a session keep it (FR-031). Audit `table.qr_rotated` (NFR-SEC-03) |
| Rotate all | `POST /v1/staff/tables/qr-codes/rotate-all` | Manager | Every table at once, the "QR leak" runbook. Audit `table.qr_rotated_all` |

The QR endpoints don't return a token to store: the URL is the only form of the code, and fetching it again later gives an equally valid one.

**Audit:**
- `table.created`, `table.updated` (changed field names), `table.deleted`;
- `table.qr_rotated`, `table.qr_rotated_all`;
- `table_session.opened_by_staff`, `table_session.moved`, `table_session.auto_closed`;
- `waiter_call.acknowledged` (who answered).

## 7. `SessionAccess` (for `orders`)
- **`requireOpenForUpdate(restaurantId, sessionId)`** locks the session row `FOR SHARE` inside the order transaction and fails with `session_not_open` if it's closed. Closing takes `FOR UPDATE`. So an order can't slip into a session at the moment it closes: one waits for the other (FR-003 "session closed meanwhile → rejected").
- **`touch(sessionId)`** sets `last_activity_at` (§3.5); orders call it on every order.
- **`close(restaurantId, sessionId, reason, by)`**, called by `orders` when it closes the bill (FR-013):
  - sets `closed`;
  - closes open waiter calls;
  - writes `TableSessionClosed`.

  Closing an already closed session is a no-op, which makes FR-013's "closing twice → same result" true here.
- **`openSessionsFor(restaurantId)`** lists open sessions with their table numbers, for the floor view and the kitchen feed.

## 8. Endpoints
All of these go into `core/http/Endpoints.kt` and the contract.

| Method and path | Who | Idempotency / If-Match | Allowed when suspended | Success |
|---|---|---|---|---|
| `POST /v1/guest/sessions` | None (QR token in body) | Natural (§3.1) | No → 403 | 200 |
| `GET /v1/guest/session` | Guest | — | Yes | 200 |
| `POST /v1/guest/waiter-calls` | Guest | Natural (merge, §4) | No | 201, or 200 when merged |
| `GET /v1/staff/tables` | Waiter, manager | — | No | 200 |
| `POST /v1/staff/tables` | Manager | — | No | 201 |
| `GET /v1/staff/tables/{tableId}` | Waiter, manager | — | No | 200 + ETag |
| `PATCH /v1/staff/tables/{tableId}` | Manager | If-Match | No | 200 + ETag |
| `DELETE /v1/staff/tables/{tableId}` | Manager | If-Match | No | 204 |
| `GET /v1/staff/tables/{tableId}/qr-code` | Manager | — | No | 200 |
| `POST /v1/staff/tables/{tableId}/qr-code/rotate` | Manager | — | No | 200 |
| `GET /v1/staff/tables/qr-codes` | Manager | — | No | 200 |
| `POST /v1/staff/tables/qr-codes/rotate-all` | Manager | — | No | 200 |
| `POST /v1/staff/tables/{tableId}/session` | Waiter, manager | Natural | No | 201 or 200 |
| `GET /v1/staff/table-sessions/{sessionId}` | Staff, all roles | — | No | 200 + ETag |
| `POST /v1/staff/table-sessions/{sessionId}/move` | Waiter, manager | If-Match | No | 200 + ETag |
| `GET /v1/staff/waiter-calls` | Waiter, manager | — | No | 200 |
| `POST /v1/staff/waiter-calls/{callId}/acknowledge` | Waiter, manager | Natural | No | 200 |

`POST /v1/guest/sessions` is the one `/v1/guest` route without a session token: the QR token in the body is its credential. The registry marks it `auth: qr`.

**New error codes:**

| Status | `code` | When |
|---|---|---|
| 400 | `qr_token_invalid` | Malformed, forged or rotated QR code, or a deleted table |
| 401 | `table_session_closed` | The guest's session has been closed; scan again |
| 409 | `table_unavailable` | The table is disabled or deleted |
| 409 | `table_occupied` | Moving a session onto a table that has one |
| 409 | `table_has_open_session` | Disabling or deleting a table with guests |
| 409 | `session_not_open` | Moving (or ordering into) a closed session |
| 409 | `table_limit_reached` | 300 tables |

## 9. Rate limits (NFR-SEC-04; §13 Q4)
| Policy | Subject | Limit |
|---|---|---|
| `qr.present` | Device ID | 10 / 1 min |
| `qr.present` | Table | 30 / 1 min |
| `qr.present` | IP | 60 / 1 min (a restaurant's guests may share Wi-Fi) |
| `waiter_call` | Session | 10 / 1 h, after merging |
| WAF backstop on `/v1/guest/sessions` | IP | 300 / 1 min |

Per-session order limits are in the `orders` design (ASSUMPTION-4: ≤ 10 orders/min per session).

## 10. Code layout
```
<base>.tables
  domain/        DiningTable, TableSession, WaiterCall, QrToken (format), MergeRule
  application/   TableService, QrService (sign, verify, rotate), SessionService (open/join, staff open, move,
                 auto-close job), WaiterCallService, FloorViewService;
                 SessionAccess (port provided to orders), SessionSummaries (port provided by orders)
  persistence/   TableRepository, SessionRepository, WaiterCallRepository
  http/          GuestSessionRoutes, StaffTableRoutes, StaffSessionRoutes, WaiterCallRoutes; GuestAuthProvider
  crypto/        QrSigner (ES256, QR key set), GuestTokenSigner (ES256, guest key set)
```

## 11. Tests
| ID | Case | Req |
|---|---|---|
| IT-TBL-01 | First scan opens a session; a second device joins the same one; the same device twice → same session, two valid tokens | FR-001 |
| IT-TBL-02 | Two first scans at the same moment → one session (concurrency test) | FR-001 |
| IT-TBL-03 | Forged signature, unknown `kid`, truncated token, rotated version, deleted table → identical `400 qr_token_invalid`, nothing created | FR-001, FR-031 |
| IT-TBL-04 | Rotation: the old code is refused at once; guests already seated keep ordering; `rotate-all` refuses every old code | FR-031 |
| IT-TBL-05 | Disabled table → 409 `table_unavailable`; suspended restaurant → 403 `restaurant_suspended` | FR-001, FR-043 |
| IT-TBL-06 | Session closed → the next guest call → 401 `table_session_closed`; the next scan opens a new session | FR-001, FR-013 |
| IT-TBL-07 | A guest token of restaurant A with a session ID of B, or a session of another table → 404/401, never B's data | FR-042, NFR-SEC-02 |
| IT-TBL-08 | Move: to a free table → orders follow; to an occupied table → 409; two concurrent moves → one 200, one 412; guests' next call sees the new table | FR-032 |
| IT-TBL-09 | Floor view: free and occupied tables with start time, order count, running total; stale flag after 6 h | FR-032 |
| IT-TBL-10 | Table CRUD: duplicate number → 409; delete or disable with guests → 409; waiter or kitchen creating → 403; 301st table → 409 | FR-030 |
| IT-TBL-11 | Waiter calls: taps within 60 s merge into one; an acknowledged call clears for all waiters; the 11th call in an hour → 429 | FR-008 |
| IT-TBL-12 | Rate limits: the 11th scan from one device in a minute → 429 with `Retry-After` | NFR-SEC-04 |
| IT-TBL-13 | Auto-close: an empty session idle 2 h is closed by the job, audited as `system`; a session with orders isn't | §3.5 |
| IT-TBL-14 | An order racing a close: either the order is in the bill, or it's rejected with `session_not_open`; never an order in a closed session | FR-003, FR-013 |
| IT-TBL-15 | No device ID, IP or user agent is stored in `table_sessions` or `waiter_calls` | NFR-PRIV-01 |

## 12. Traceability
The RTM's design column for FR-001, FR-008, FR-030, FR-031 and FR-032 points here; FR-042, NFR-SEC-02, NFR-SEC-04 and NFR-PRIV-01 add this design.

## 13. Owner decisions (2026-10-15)
The owner approved CR-005 and accepted all six recommendations. They're recorded in STATUS.md (Decisions; Q-5 resolved).

1. CR-005: session open/join, staff-opened sessions and waiter calls take no `Idempotency-Key`; retries are safe by the database constraints (§3, §4). Architecture v1.2.
2. Abandoned sessions: auto-close without orders after 2 h idle; sessions with orders are flagged in the floor view after 6 h, never auto-closed (§3.5).
3. No device data is stored (§5; requirements v1.4 §5).
4. The scan and call limits in §9 as starting values.
5. QR signing keys aren't retired while printed codes exist; a leak is handled by `rotate-all` and reprinting (§2).
6. A closed session answers `401 table_session_closed` (§3.2).
