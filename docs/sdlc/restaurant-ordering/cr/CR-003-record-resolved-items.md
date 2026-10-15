# CR-003: Write the owner's resolved decisions into the requirements and correct the decree citation

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-003 | Approved | 2026-10-14 | Manh Nguyen (product owner), from STATUS open items | Manh Nguyen (product owner) | requirements.md v1.1 |

## Summary
Four things in requirements v1.1 are out of date. Three are decisions the owner has already made (TBD-2, TBD-3, ASSUMPTION-11). The fourth is a wrong legal citation (Decree 13/2023 was replaced on 2026-01-01). This CR changes the text only. No requirement gets stricter or looser, and no design changes.

## Why
- The requirements are the baseline that the tests and the G2 review are checked against. If they still show these items as open, that baseline is wrong.
- STATUS.md notes each of the four as "changing that text needs a CR". This CR covers all four at once, so none of them needs a separate one.

## Proposed change
1. **TBD-2: mobile platforms** (decided 2026-10-14).
   - §2 customer mobile app: "Android / iOS: TBD-2" → "Android and iOS".
   - §2 staff app: "old versions supported for TBD-3 if it is also shipped as a store app" → "web only in release 1 (no store app), so only the latest version is supported".
   - §9: TBD-2 marked resolved.
2. **TBD-3: support window** (decided 2026-10-14).
   - §2 customer mobile app: "old versions supported for TBD-3 (proposed 12 months)" → "old versions supported for 12 months".
   - NFR-COMPAT-01: "the support window (TBD-3)" → "the customer mobile app's support window (12 months)".
   - §9: TBD-3 marked resolved.
3. **ASSUMPTION-11: which day a bill counts on** (confirmed 2026-10-14, S-5 design point 4).
   - FR-050, add one acceptance line: "A bill counts on the restaurant's local day on which it was closed, even if the guests sat down the day before."
   - §9: ASSUMPTION-11 added as confirmed.
4. **Decree citation** (found while writing the legal-review brief, TBD-10).
   - §7: "Decree 13/2023/ND-CP" → "Decree 356/2025/ND-CP (which replaced Decree 13/2023 on 2026-01-01)".
   - This only corrects the name. What the decree requires (residency, transfers abroad, erasure deadline) is still for the legal review under TBD-10 and TBD-4.

## Impact
| Item | Change | When |
|---|---|---|
| requirements.md | v1.1 → v1.2, with a version-history entry citing CR-003: §2 customer mobile app and staff app rows, FR-050, NFR-COMPAT-01, §7 regulations, §9 (TBD-2, TBD-3 resolved; ASSUMPTION-11 added) | On approval |
| architecture.md | §1 constraints: Decree 13 → Decree 356/2025. Draft, so no CR is needed for it; done together with this CR | On approval |
| test-plan.md | None. The FR-050 test (AT- scenario) gets a case for a bill opened before midnight and closed after it, which S-5 already tested | Phase 3 |
| rtm.md | None (no requirement ID added or removed) | — |
| STATUS.md | TBD-2, TBD-3 and ASSUMPTION-11 rows: drop "needs a CR"; TBD-10 row: drop "Requirements §7 still cites Decree 13"; API consumers field: platforms decided | On approval |
| ADR-0001, ADR-0011 | None. Their mentions of TBD-2 and Decree 13 record what was known when they were decided | — |
| Code | None (Phase 4 hasn't started) | — |

## What stays the same
- Every FR and NFR target.
- TBD-4 (retention) and TBD-10 (residency) stay open. Their working values wait for the lawyer.

## Risks this adds
None. If the legal review later says something different about the decree, that will be its own CR.

## Cost and schedule
- **Cost:** $0.
- **Schedule:** under an hour to apply.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-14 | Approved | Manh Nguyen (product owner) | Applied the same day: requirements v1.2, architecture §1, STATUS |
