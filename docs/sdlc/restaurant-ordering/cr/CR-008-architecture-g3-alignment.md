# CR-008: Align the architecture with the Phase 3 designs (G3 consistency pass)

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-008 | Approved | 2026-10-15 | Manh Nguyen (product owner), from the G3 consistency pass | Manh Nguyen (product owner) | architecture.md v1.3 |

## Summary
The G3 consistency pass found two places where architecture v1.3 no longer matches decisions already approved. Both are text corrections. No design, requirement or target changes.

## Why
1. **§6 still says "device data on sessions is removed after 90 days".** CR-005 (approved 2026-10-15) stopped storing guest device data, and changed requirements §5 accordingly, but CR-005's architecture changes covered §7 and §10 only. With no device data stored, the step has nothing to remove.
2. **§10's job list is out of date.** It names four jobs, and the designs approved since run seven: the session auto-close (tables §3.5), the event purge separate from the key purge (realtime §10), the audit export (core-and-auth §12), and an hourly sales rebuild that runs per restaurant after its own local 03:00 instead of once a night (reports §5). The full list now lives in core-and-auth §10.

## Proposed change
1. **§6, retention jobs:** delete the line "device data on sessions is removed after 90 days".
2. **§10, scheduled jobs:** replace the "Jobs:" line with: "Jobs (full list in design/core-and-auth.md §10): outbox sweep (every minute), event purge, key and token purge, session auto-close and sales rebuild (hourly; the rebuild per restaurant after its local 03:00), retention and anonymisation, and audit export (daily). Vercel Hobby runs every job at most daily, which the designs allow for (realtime §4.3, §10)."

## Impact
| Item | Change | When |
|---|---|---|
| architecture.md | v1.3 → v1.4: §6, §10 | On approval |
| Designs, contract, requirements | None | — |
| Code | None (Phase 4 hasn't started) | — |

## What stays the same
Every decision and target. TBD-4's retention values.

## Risks this adds
None.

## Cost and schedule
- **Cost:** $0.
- **Schedule:** minutes to apply.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-15 | Approved | Manh Nguyen (product owner) | Applied the same day: architecture v1.4, STATUS |
