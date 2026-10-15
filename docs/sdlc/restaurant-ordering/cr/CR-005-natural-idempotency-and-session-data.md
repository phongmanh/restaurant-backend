# CR-005: Drop the idempotency key where retries are already safe, and store no guest device data

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-005 | Approved | 2026-10-15 | Claude, from the tables feature design | Manh Nguyen (product owner) | architecture.md v1.1 (§7, §10); requirements.md v1.3 (§5) |

## Summary
The tables design ([design/tables.md](../design/tables.md) §3, §4, §5) finds three places where the baselines ask for more than the design needs:
1. **Architecture §10** requires an `Idempotency-Key` on session open/join and waiter calls. Both are already safe to retry. Storing their answers would also keep a guest's bearer token in the database for 24 h.
2. **Architecture §7** puts a table claim in the guest token. Sessions can move to another table (FR-032), so the claim would go stale; the table comes from the database on each call instead.
3. **Requirements §5** lists a "device token" stored with each table session. The design stores no device data at all.

## Why
- An `Idempotency-Key` exists to stop a retry from doing something twice.
  - **Session open/join** can't do anything twice: the database allows one open session per table, so a retry joins the same one (FR-001's retry rule holds).
  - **Waiter calls** merge repeats within 60 s (FR-008), so a retry returns the same call.
  - A stored replay would hold the session token, a bearer secret, against the rule added to core-and-auth §4 and CLAUDE.md on 2026-10-15.
- A table claim that can be wrong after a move is a bug waiting to happen. The per-call database check (ADR-0006) already reads the session row, so the table ID comes from there for free.
- Storing less personal data is what NFR-PRIV-01 asks for. Device IDs are needed only in the token (`sub`) and in the rate-limit counters, which expire within minutes.

## Proposed change
1. **architecture §10, Idempotency:**
   - "`Idempotency-Key` required on order placement, session open/join, waiter calls, staff-placed orders, session close and restaurant onboarding."
   - → "`Idempotency-Key` required on order placement, staff-placed orders, session close and restaurant onboarding. Session open/join and waiter calls are idempotent by design (one open session per table; repeats within 60 s merge) and take no key, so no bearer token is ever stored for a replay."
2. **architecture §7, Table session:** "claims for session, table and restaurant" → "claims for session and restaurant (the table is read from the database on each call, because a session can move)".
3. **requirements §5, table-session row:**
   - Data: "session ID, device token, open/close times, table" → "session ID, open/close times, table (no device data is stored)".
   - Classification: "Personal (pseudonymous, linkable to a device)" → "Internal".
   - Retention: "90 days, then device data removed (session kept with the bill)" → "Kept with the bill it belongs to (orders and bills retention, TBD-4)".

## Impact
| Item | Change | When |
|---|---|---|
| architecture.md | v1.1 → v1.2, with a version-history entry citing CR-005: §7, §10 | On approval |
| requirements.md | v1.3 → v1.4, with a version-history entry citing CR-005: §5 | On approval |
| design/tables.md | Already written this way | — |
| design/core-and-auth.md | §4: the list of operations that require the key follows architecture §10 | On approval |
| test-plan.md | NFR-REL-02's resilience test keeps "no duplicate session" and checks it without a key | Phase 3 |
| rtm.md | None | — |
| Code | None (Phase 4 hasn't started) | — |

## What stays the same
- FR-001's retry rule ("presenting the same token twice from the same device returns the same session") and NFR-REL-02 ("no duplicate order, session or restaurant when a client retries").
- The `Idempotency-Key` on order placement, staff orders, session close and onboarding.
- ADR-0006: ES256 session tokens, a 6 h lifetime, the per-call session check.

## Risks this adds
None found. The uniqueness that makes the retries safe is a database constraint, which tests IT-TBL-01 and IT-TBL-02 cover.

## Cost and schedule
- **Cost:** $0.
- **Schedule:** under an hour to apply.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-15 | Approved | Manh Nguyen (product owner) | Applied the same day: architecture v1.2 (§7, §10), requirements v1.4 (§5), core-and-auth §4, STATUS |
