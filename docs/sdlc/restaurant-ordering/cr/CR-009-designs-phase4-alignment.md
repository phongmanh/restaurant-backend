# CR-009: Align the feature designs with what Phase 4 built (Q-10 to Q-15)

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-009 | Approved (option A) | 2026-10-17 | Manh Nguyen (product owner), from the Phase 4 build (Q-10 to Q-15) | Manh Nguyen (product owner) | design/restaurants.md, menu.md, tables.md, orders.md, realtime.md, reports.md (all v1.0, approved at G3) |

## Summary
While building Phase 4, each step settled a few details its design got wrong, contradicted elsewhere, or didn't cover. Each one was logged as a question (Q-10 to Q-15) and built the way the question recommends. This CR writes those details into the six designs, so the designs describe the code again. It also traces four tests that name no requirement, as the RTM's coverage check requires.

Everything here is a wording change to match code that is already built and tested, except **one decision** (change 9b): whether a manager's note on a bill correction is cleared after 30 days, like the other free-text notes.

Approving this CR also confirms Q-10 to Q-13 as built. The owner confirmed Q-14 and Q-15 on 2026-10-17.

## Why
- The designs are baselined at G3, so a change to one needs a CR (CLAUDE.md, process rules).
- A design that disagrees with the code misleads whoever reads it next: the reviewer at G4, or the person changing the code later.
- Three of the six questions are about locking and lock order (Q-12, Q-13). A future change that followed the old text would bring back a deadlock the tests caught.

## Proposed changes

### restaurants.md v1.0 → v1.1 (Q-10)
1. **§6, suspension audit.** Replace "Audit `restaurant.suspended` (with the reason) and `restaurant.reactivated`, in the restaurant's chain." with: "Audit `restaurant.suspended` and `restaurant.reactivated` in the restaurant's chain, without the reason. The reason is an admin's free text: core-and-auth §12 keeps free text out of audit `details`, and the restaurant's managers read that chain (§9). It stays in `restaurants.suspension_reason`, which only admins read."

### menu.md v1.0 → v1.1 (Q-11)
2. **§5.4, `option_groups` row.** Move the two counts out of the validation table: "≤ 10 groups and ≤ 20 options per group are menu limits (§3): passing one is `409 menu_limit_reached`, as IT-MENU-12 tests." The row keeps the per-field rules (`min_choices`, `max_choices`, `price_delta`), which stay `400 validation_failed`.

### tables.md v1.0 → v1.1 (Q-12)
3. **§7, `requireOpenForUpdate`.** Replace "locks the session row `FOR SHARE`" with "locks the session row `FOR NO KEY UPDATE`", and add: "Not `FOR SHARE`: the same order transaction then updates the row (`touch`), and two transactions that each hold a share lock and wait to update deadlock. `FOR NO KEY UPDATE` still makes a close (`FOR UPDATE`) wait, so FR-003's 'closed meanwhile → rejected' holds. Two orders of one session take turns, as the bill row already makes them do (orders §6)."
4. **§3.3, `GET /v1/guest/session`.** Replace "plus the guest's open waiter calls" with "plus the latest waiter call of each kind while it is open or acknowledged, so the app can show 'a waiter is coming' after an acknowledgement (§4)".

### orders.md v1.0 → v1.1 (Q-13)
5. **§7.1, the order of the steps.** Move step 9 (`SalesLedger.recordClosedBill`) before step 8 (`SessionAccess.close`), with: "The ledger comes first because `SessionAccess.close` writes `TableSessionClosed`, which takes the event counter, and the lock order puts the daily sales rows before the event counter (CLAUDE.md, lock order). Nothing a reader sees changes: it is one transaction."
6. **§11, ports used.** Add `SessionAccess.find`: "a batch read of sessions with their table, open or closed, for `table` on orders and bills".
7. **§4.1, a session without orders.** Replace "returns an empty open bill with `version` 0" with "returns an empty bill with `version` 0, open or closed as its session is".
8. **§11, ports provided.** Name the `SalesSource` implementation: "`SalesFactsSource`, on `SalesRowRepository`: one local day's closed bills, their lines and its corrections, built with the same `itemsOf` as the close, so the rebuild and the ledger can't disagree (reports §5)."
9. **§6, free text.**
   - a. Wording: no change needed for `note` and `reason_note`.
   - b. **Decision:** `bill_adjustments.note` is a manager's free text on a correction, and §6 doesn't say how long it's kept. Step 7 keeps it.
     - **Option A (recommended): clear it 30 days after the correction**, like the other notes. Free text can hold personal data ("refund for Mr. Nam"), and minimisation under the PDP law favours clearing it. The amount, method and reason code stay: they are the accounting record. §6 gains "`bill_adjustments.note` is cleared 30 days after the correction."
     - **Option B: keep it with the accounting record** (10 years, TBD-4). §6 then says so, and requirements §5 lists it as kept data, which would need a requirements change too.

### realtime.md v1.0 → v1.1 (Q-14, confirmed 2026-10-17)
10. **§6.1, `cursor`.** Replace "the highest `seq` in the answer, or the restaurant's latest `seq` when there are no events" with "the restaurant's latest `seq` read when there is no next page (the page was read up to it), otherwise the highest `seq` in the answer. For a guest, that also skips other sessions' events after their last one".
11. **§4.2, inline publishing.** Add: "A request publishes its events in order and stops at the first failure. When Ably is down, it isn't sent the rest, each of which could wait 300 ms; those rows wait for the next publisher."
12. **§4.3, catching up.** Add to way 1: "Skipped while the circuit breaker is open, like the inline try." Add to way 2: "A sweep run also stops at its first failure. It ignores the circuit breaker: it is the probe that finds Ably back."

### reports.md v1.0 → v1.1 (Q-15, confirmed 2026-10-17)
13. **§5, the rebuild.**
    - Step 2: "Compare the figures with the stored rows, leaving the display names aside (table number, item names): those are the latest snapshot a close wrote, which the raw rows can't rebuild."
    - Step 3: "Only when they differ, delete the day's rows and insert the computed ones. A rewritten row keeps the stored names; a row that was missing takes the table's current number and the order lines' names."
    - Cost: replace "batches of 50 restaurants … continues on the next run" with "up to 1,000 restaurants per run (S-5: ≈ 2 min); the rest are due again on the next run, and the lag log line shows them".
    - Add: "A restaurant whose rebuild fails is logged (its ID and the error type) and skipped; the run goes on. The restaurants and their time zones come from the `RestaurantZones` port of `restaurants`."
14. **§6, the report.** Add: "Payments are listed by method, tables by number then ID. The route checks the role, then the rate limit, then the query (403, then 429, then 400), as the realtime reads do."

### Test tables: requirements for four tests (RTM coverage check)
15. In each design's test table, fill the Req column:

| Test | Design | Req now | Req after |
|---|---|---|---|
| IT-TBL-13 (auto-close of an idle empty session) | tables §11 | §3.5 | FR-032 |
| IT-ORD-22 (auto-close with only a cancelled order) | orders §14 | tables §3.5 | FR-032, FR-050 |
| IT-RT-08 (retry after `42918`; sweepers never claim the same row) | realtime §13 | ADR-0003 | NFR-REL-03, NFR-AVAIL-02 |
| IT-RT-12 (purge after 7 days; old cursor gets `reset`) | realtime §13 | architecture §6 | NFR-REL-03, NFR-PRIV-01 |

Also add NFR-I18N-01 to IT-ORD-16 and IT-REP-02, which test the restaurant's local day.

## Impact
| Item | Change | When |
|---|---|---|
| design/restaurants.md, menu.md, tables.md, orders.md, realtime.md, reports.md | v1.0 → v1.1: changes 1–15 | On approval |
| rtm.md | None: it already shows the traces of change 15 (2026-10-17) | — |
| Requirements, architecture, contract | None with option A. Option B: requirements §5 (data inventory), through this CR | On approval |
| Code | None with option B. **Option A:** a migration allowing the retention job to clear `bill_adjustments.note`, the clearing in `NoteRetention`, and IT-ORD-21 extended to it. A small change on its own branch | After approval |
| STATUS.md | Q-10 to Q-13 resolved; Q-14 and Q-15 closed; CR-009 in the CR table | On approval |

## What stays the same
Every requirement, target, endpoint and error code. The code as built in PRs #6 to #11, except option A's retention addition.

## Risks this adds
None. Leaving the designs as they are is the risk: a later change that follows the old text of tables §7 or orders §7.1 would bring back a deadlock the tests caught.

## Cost and schedule
- **Cost:** $0.
- **Schedule:** about an hour to apply the wording; option A adds a small code change with its test.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-17 | Approved, with option A for change 9b | Manh Nguyen (product owner) | Applied the same day: the six designs at v1.1, STATUS (Q-10 to Q-15 closed), the RTM note. Option A's code change (clearing `bill_adjustments.note`) follows on its own branch |
