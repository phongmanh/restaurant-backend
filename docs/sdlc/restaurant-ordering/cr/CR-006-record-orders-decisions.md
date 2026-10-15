# CR-006: Write the orders design decisions into the requirements and the architecture

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-006 | Approved | 2026-10-15 | Manh Nguyen (product owner), from the orders design §15 (Q-6) | Manh Nguyen (product owner) | requirements.md v1.4, architecture.md v1.2 |

## Summary
On 2026-10-15 the owner accepted the seven recommendations of the [orders design](../design/orders.md) §15. Two of them settle open items written into the requirements (TBD-7 and TBD-8). Two numbers in the architecture turned out low while designing orders. This CR updates the text to match. No target gets stricter or looser.

## Why
- The requirements still list TBD-7 and TBD-8 as open, and FR-016 still reads as a possible release 1 feature. Tests and G3 are checked against that text.
- Architecture §9 counts "≈ 6 queries" per order placement, and §6's size estimate leaves out the audit log. Neither changes a decision, but the capacity model should show the real figures before the load test.

## Proposed change
1. **TBD-7: correcting a closed bill** (owner, 2026-10-15; orders §7.2).
   - FR-013: "Corrections are a manager action that is audited (TBD-7)" → "A manager corrects a closed bill with correcting entries (a signed amount, a payment method and a reason); the closed bill itself never changes. A correction counts in reports on the local day it's made. Every correction is audited."
   - FR-050: one line: "Corrections to closed bills count on the local day they were made."
   - §9: TBD-7 marked resolved.
2. **TBD-8: FR-016 waiter confirmation** (owner, 2026-10-15; orders §15 Q1).
   - FR-016: priority C → W for release 1; the text says it isn't built in release 1 and can be added later within `/v1`, because clients accept unknown order statuses.
   - §3.7: FR-016 added to "Won't have in release 1".
   - §9: TBD-8 marked resolved.
3. **Architecture §9:** the place-order row: "≈ 6" queries → "≈ 13 statements (orders §3.1), ≈ 40–60 ms expected". The total row's query rate is recomputed.
4. **Architecture §6:** the PostgreSQL row adds the audit log: ≈ 400k rows a day at 500 restaurants (every staff order step is audited, orders §8), ≈ 70 GB inside the 1-year window (TBD-4 working value).

## Impact
| Item | Change | When |
|---|---|---|
| requirements.md | v1.4 → v1.5, with a version-history entry citing CR-006: FR-013, FR-016, FR-050, §3.7, §9 | On approval |
| architecture.md | v1.2 → v1.3: §6 and §9 | On approval |
| rtm.md | FR-016 priority W | On approval |
| test-plan.md | The FR-013 acceptance scenario (AT-013) gets a correction case; nothing for FR-016 | Phase 3 |
| cost-estimate.md | Database storage line includes the audit log when TBD-11 is revisited | With TBD-11 |
| Code | None (Phase 4 hasn't started) | — |

## What stays the same
- Every NFR target, including NFR-SEC-03 (every staff status step is audited, as it reads).
- ASSUMPTION-4 and ASSUMPTION-7.

## Risks this adds
None new. The audit volume is a storage cost, carried with TBD-11 (deferred: free plans only).

## Cost and schedule
- **Cost:** $0.
- **Schedule:** under an hour to apply.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-15 | Approved | Manh Nguyen (product owner) | Applied the same day: requirements v1.5, architecture v1.3, RTM, STATUS |
