# CR-004: Fit the menu read and image upload to Vercel's platform limits

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-004 | Approved | 2026-10-15 | Claude, from the menu feature design | Manh Nguyen (product owner) | requirements.md v1.2 (FR-022); architecture.md v1.0 (§1, §8, §9, §11) |

## Summary
Two documented Vercel limits, checked on 2026-10-15 while designing the menu, conflict with the baselines:
- **The CDN never caches a request that carries an `Authorization` header** ([CDN cache docs](https://vercel.com/docs/caching/cdn-cache), "Cacheable response criteria"). The architecture caches the guest menu at the edge, but FR-002 makes guests send their session token on that request. So the edge cache would never be used.
- **A function's request body is at most 4.5 MB** ([function limits](https://vercel.com/docs/functions/limitations), "Request body size"). FR-022 accepts images up to 5 MB.

This CR changes the architecture to private revalidation by menu version, and changes FR-022 to 4 MB. FR-002 stays as it is: guests still need a session to read the menu.

## Why
- Neither limit was in S-1's questions, so both slipped past G1 and G2. Without this CR, the architecture would describe a cache that never works, and a 4–5 MB image would get Vercel's own `413 FUNCTION_PAYLOAD_TOO_LARGE` instead of our error.
- FR-022 is a Should (TBD-5, 2026-10-15), so the image change costs little. It only matters if images are built.

## Proposed change
1. **architecture §1, driver 4 ("5,000 guests open menus at 19:00"):**
   - "Menu read is cacheable at the edge per restaurant and revalidated with `ETag` (short `s-maxage`); sold-out is enforced again at order time"
   - → "Menu read is behind the session and revalidated per menu version: `ETag` = menu version, `304` when unchanged, built menus cached per instance by version (Vercel's CDN doesn't cache requests with `Authorization`); sold-out is enforced again at order time".
2. **architecture §8:** "CDN caching only for the public menu read and images" → "CDN caching only for menu images (on the storage's CDN)".
3. **architecture §9, menu-read row:** "Public per restaurant, `s-maxage` ≈ 30 s + `ETag`" → "Private; `ETag` per menu version, `304` when unchanged; in-instance cache by version".
4. **architecture §11, HTTP hardening:** "RequestBodyLimit (64 KB JSON; images go via a direct upload URL)" → "RequestBodyLimit (64 KB JSON; 4 MB on the image upload route, under Vercel's 4.5 MB function body cap)".
5. **requirements FR-022 acceptance:**
   - "(JPEG, PNG or WebP, ≤ 5 MB, checked by content, not by extension)" → "(JPEG, PNG or WebP, ≤ 4 MB, checked by content, not by extension; the staff app shrinks photos before upload)";
   - "Must or Should: TBD-5" → "Should (TBD-5, decided 2026-10-15)".
6. **requirements §9:** TBD-5 marked resolved (Should).

## Impact
| Item | Change | When |
|---|---|---|
| requirements.md | v1.2 → v1.3, with a version-history entry citing CR-004: FR-022 (4 MB; Should), §9 TBD-5 resolved | On approval |
| architecture.md | v1.0 → v1.1, with a version-history entry citing CR-004: §1 driver 4, §8, §9 menu row, §11 | On approval |
| design/menu.md | Already written this way (§2, §6) | — |
| design/core-and-auth.md | §2: menu reads send `private, no-cache` instead of `no-store`; the image route allows 4 MB (menu §12) | On approval |
| test-plan.md | The FR-022 test uses 4 MB as the limit | Phase 3 |
| rtm.md | None (no requirement ID added or removed) | — |
| cost-estimate.md | None. Images are served from storage either way; the menu JSON moves from CDN egress to function egress, which is small next to images | — |
| Code | None (Phase 4 hasn't started) | — |

## What stays the same
- FR-002, word for word: no session or an expired session → 401, and the menu is "cacheable and revalidated" (by the app, with `ETag`).
- NFR-PERF-01's target (guest reads p95 ≤ 200 ms).
- Sold-out enforced at order time.

## Risks this adds
- Every menu load reaches the function, even when the answer is a 304. At 70 req/s at peak (architecture §9) the work is one primary-key read, which is well within capacity.
- Cold starts on menu loads aren't hidden by a cache. That was already true, because the session check needs the function. It's tracked by R-12 and the paid-plan check before G5.
- If a load test later shows full menu builds are a hot spot, a signed, cacheable menu URL can be added as a new endpoint. That would need FR-002 reworded (menu design §2).

## Cost and schedule
- **Cost:** $0.
- **Schedule:** under an hour to apply.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-15 | Approved | Manh Nguyen (product owner) | Applied the same day: requirements v1.3, architecture v1.1 (also the §9 cost-driver line for TBD-5), core-and-auth §2, STATUS |
