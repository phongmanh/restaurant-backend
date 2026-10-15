# CR-007: Take FR-051 (CSV export) out of release 1

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-007 | Proposed | 2026-10-15 | Manh Nguyen (product owner), from the reports design §13 Q3 (Q-8) | Manh Nguyen (product owner) | requirements.md v1.5 |

## Summary
On 2026-10-15 the owner accepted the reports design's recommendation that the CSV export of sales lines (FR-051, a Could) isn't built in release 1. This CR writes that into the requirements, as CR-006 did for FR-016. No other requirement changes.

## Why
- A 93-day export for a busy restaurant (300 bills a day, ≈ 6 lines each) is ≈ 167k lines, ≈ 15 MB. Vercel caps a function's response at 4.5 MB (CR-004), so the export would have to be built by a background job and handed over as a file in object storage.
- The object store (TBD-13) isn't chosen yet, and only FR-022 (images, a Should) needs it so far. Building the job and the download flow for a Could isn't worth it before launch.
- The daily report (FR-050) already gives managers each day's totals, payment methods, tables and top items.

## Proposed change
1. **FR-051:** priority C → W for release 1. The text keeps the story and adds: "Not built in release 1 (owner, 2026-10-15). If built later: produced asynchronously into object storage (TBD-13), because a 93-day export exceeds the 4.5 MB response limit."
2. **§3.7 Won't have in release 1:** add "FR-051 CSV export of sales lines".

## Impact
| Item | Change | When |
|---|---|---|
| requirements.md | v1.5 → v1.6, with a version-history entry citing CR-007: FR-051, §3.7 | On approval |
| rtm.md | FR-051 priority W | On approval |
| test-plan.md | None (a Could has no acceptance scenario) | — |
| design/reports.md | §13 Q3 already records the decision | — |
| Code | None (Phase 4 hasn't started) | — |

## What stays the same
- FR-050 and NFR-PERF-04.
- TBD-13 stays open for FR-022.

## Risks this adds
Managers who want line-level data for an accountant get daily totals only in release 1. Adding the export later is additive (a new endpoint within `/v1`).

## Cost and schedule
- **Cost:** $0.
- **Schedule:** minutes to apply.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| | | | |
