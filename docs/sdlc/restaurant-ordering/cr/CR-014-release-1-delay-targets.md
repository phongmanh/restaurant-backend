# CR-014: Release 1's live-update p95 targets with room for the request

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-014 | Proposed | 2026-10-18 | Manh Nguyen (product owner), choosing Q-22 option (a) after step 6c's ST-03 run | Manh Nguyen (product owner) | requirements.md v1.7 (NFR-PERF-03); test-plan.md v0.6 (ST-03) |

## Summary
Release 1's staff p95 target goes from 15 s to 16 s, and guests' from 60 s to 62 s. The p99 targets (20 s, 75 s) and the poll timers (15 s, 60 s, ±20 %) stay. The release-1 p95 then leaves room for the request itself, which CR-013's targets didn't.

## Why
- **CR-013's p95 targets equal the timers, and that can't be met.** A screen polls every T ± 20 % from a random point of its timer, so a write lands at a random moment in the gap between two polls. Longer gaps catch more writes, and the wait for the next poll comes out with p95 = T exactly (p99 1.11 T, max 1.2 T). The request's own time comes on top, so every run lands just over a p95 of T.
- **Step 6c measured exactly that** (run `tn3k8b`, 2026-10-18, 55 polling screens on L under the smoke load): staff p95 15.4 s and p99 17.1 s (theory 15.0 and 16.7 s plus the request); guests p95 60.6 s and p99 67.6 s (theory 60.0 and 66.6 s). Every write was shown; the p99s pass with margin.
- The kitchen gets the same speed either way: the timers don't change, and neither does what a user sees. Only the target is corrected.

## Proposed change
1. **Requirements (v1.7 → v1.8), NFR-PERF-03:** "a change reaches staff screens within 15 s at p95 (20 s at p99) and guest screens within 60 s at p95 (75 s at p99)" → "within 16 s at p95 (20 s at p99) … within 62 s at p95 (75 s at p99)". A version-history row citing CR-014. KPI-2 cites NFR-PERF-03's p99 (20 s), so its text stays.
2. **Test plan (v0.6 → v0.7), ST-03:** the release-1 target becomes "staff p95 ≤ 16 s, p99 ≤ 20 s; guests p95 ≤ 62 s, p99 ≤ 75 s". The run `tn3k8b` is assessed against it: staff 15.4 / 17.1 s and guests 60.6 / 67.6 s, so it **passes, scaled**.
3. **Code:** `LiveScreens`'s targets follow (16 s, 62 s).
4. **STATUS:** Q-22 closes with this CR.

## Impact
| Item | Change | When |
|---|---|---|
| requirements.md | v1.8: NFR-PERF-03's release-1 p95s; version history | On approval |
| test-plan.md | v0.7: ST-03's target and result | On approval |
| `LiveScreens.kt` | `STAFF_TARGET` 16 s, `GUEST_TARGET` 62 s | On approval, in PR #33 |
| Contract, architecture, designs, cost | None: realtime §7's timers and the capacity figures stay | — |

## What stays the same
- The poll timers (staff 15 s, guests 60 s, ±20 %) and so the request load (≈ 640 req/s at peak) and the cost estimate.
- The p99 targets (20 s, 75 s) and KPI-2.
- The 2 s target (5 s at p99) once live push is added.

## Risks
None new. R-19 (polling too slow for the kitchen in practice) is unchanged: the pilot judges the experience, not the target.

## Owner decisions needed
1. Approve, reject or defer this CR.

## Cost and schedule
No cost. Documents and a two-line code change, in PR #33.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| | | | |
