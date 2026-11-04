# CR-017: A short grace period for a refresh token that was just used

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-017 | Approved | 2026-11-04 | Claude, from the admin web app's DEF-009 (its P4 testing); the owner chose this fix over a client-only change | Manh Nguyen (product owner) | design/core-and-auth.md v1.2 (§8.3, §15, §16 item 4); architecture.md v1.8 (§7 refresh token, threat table); `docs/api/openapi.yaml` 1.1.1 (`POST /v1/auth/refresh` description) |

## Summary
For a few seconds after a refresh token is used, the service accepts it once more. It doesn't send back the same replacement, which it can't, since it keeps only hashes. It sends a fresh replacement for the same sign-in. After that window, presenting a used token still ends the whole sign-in, as it does today. This reverses part of the owner's 2026-10-15 decision (core-and-auth §16 item 4: "Rejected alternative: a server grace period"). That decision dealt with two open tabs, which the apps can make take turns. It didn't cover a page reload, which the apps can't.

## Why
**In plain words:** the admin app refreshes its sign-in on every page load. If the admin reloads while that refresh is still in flight, the server has already swapped the refresh token, but the browser never gets the new cookie, because the page that asked was thrown away. The reloaded page sends the old token, the server sees a used token come back, takes it for theft and ends the sign-in. The admin is signed out for reloading the page.

- **Found 2026-11-04** as DEF-009 (High) in the admin web app's P4 testing (`restaurent-admin-web`, STATUS unit 7). It blocks that app's P4 gate.
- **The client can narrow the window but not close it.** A `keepalive` request (`45f8445` there) lets the browser store the answer when it arrives before the reloaded page asks again. With 100 ms of server latency, a probe was still signed out in 9 of 9 runs. A cold start on Vercel (5–7 s, S-1) makes the window far longer.
- **Taking turns doesn't help here.** §8.3 has the web apps take turns across tabs with the Web Locks API. A page's lock is released when the page unloads, so the reloaded page gets it at once, while the old request is still being handled on the server.
- **It hits every web app that refreshes on load,** the staff web app too. Staff on a slow restaurant connection reload more often than admins do.
- **Grace periods are a common practice:** Auth0 ("reuse interval"), Okta ("grace period for token rotation") and others offer one for exactly this race.

## Proposed change
### 1. Behaviour (core-and-auth §8.3, v1.2 → v1.3)
`POST /v1/auth/refresh` with token **T**:

| Case | Today | After CR-017 |
|---|---|---|
| T unused and valid | Mark T used; issue child C; 200 | Same, and C records T as its parent. Any **sibling** of T (another child of T's parent) that is still unused is marked superseded |
| T used **≤ `REFRESH_GRACE_SECONDS`** ago (default 10), the session live, the same audience, T not superseded, no child of T used yet, fewer than 3 grace swaps on T | Revoke the session; 401 | **Grace swap:** issue another child of T (a sibling of C); count the swap on T; 200 |
| T used earlier than that, or superseded, or any other grace condition fails | Revoke the session; 401 | Same: revoke, audit `auth.refresh.reuse_detected`, clear the cookie, 401 |
| T unknown or expired, or the session isn't live | 401, cookie cleared | Same |

- **Why siblings are superseded:** after a reload, the browser can end up with either child, depending on which answer lands last. Whichever one it uses first wins, and the others stop working, so the next stale one that comes back counts as theft again.
- **Why "no child of T used yet":** if a child has already been used, the sign-in has moved on, and T coming back is a real replay. Built as: using a child closes its parent's grace (sets its swap count to the cap) before anything else, so a grace swap and a child's use take turns on the parent's row.
- **Atomic:** the grace swap is one `UPDATE staff_refresh_tokens SET grace_swaps = grace_swaps + 1 WHERE token_hash = ? AND used_at > ? AND superseded_at IS NULL AND grace_swaps < 3 RETURNING session_id`, run in the same `globalTransaction` as the normal path. The row lock serialises two grace requests for the same T.
- **`REFRESH_GRACE_SECONDS`:** a setting from 0 to 30, default 10. 0 restores today's strict rule exactly. The window counts from when T was used on the server, not from when the browser sent it, so a cold start delays both requests and doesn't use up the window.
- **Logging and audit:** a grace swap isn't audited, since refreshes aren't (§8.3). A counter `auth.refresh.grace` records it with no labels beyond the audience, so we can see how often reloads hit the race. Reuse is audited as before.
- **The two-tab rule stays.** The web apps still take turns across tabs (§8.3). That keeps grace swaps rare, and two tabs stay safe when the grace is 0.

### 2. Data (migration `V12__refresh_grace.sql`; V10 and V11 were taken)
`staff_refresh_tokens` gains `parent_hash bytea NULL` (the token it replaced, indexed), `grace_swaps smallint NOT NULL DEFAULT 0` and `superseded_at timestamptz NULL`. All three are additive: the running version ignores them, and existing rows have no parent, which only means they have no siblings. `staff_refresh_tokens` stays a global table without RLS (core-and-auth §16 item 1), so `RowLevelSecurityTest`'s `GLOBAL` list doesn't change.

### 3. Contract (`docs/api/openapi.yaml` 1.1.1 → 1.1.2, description only)
`POST /v1/auth/refresh` description becomes: "The refresh token is single-use. For a few seconds after its use (`REFRESH_GRACE_SECONDS`, 10 by default), presenting it again while the session is live returns a fresh pair, so a page reloaded mid-refresh stays signed in. Presenting a used token after that ends the whole session (core-and-auth §8.3)." No status, schema or header changes, so oasdiff reports nothing breaking.

### 4. Architecture §7 (v1.8 → v1.9)
- The refresh-token paragraph: "If a used refresh token comes back **after a grace period of a few seconds** (core-and-auth §8.3), the whole family is revoked."
- Threat table, refresh tokens: "rotation on every use; reuse **after a seconds-long grace period** revokes the family".

### 5. Tests (core-and-auth §15)
- **IT-AUTH-07** keeps its meaning with the grace set to 0: rotation, then reuse ends the session and the new token stops working too.
- **IT-AUTH-14** (new): with the grace on, T used, then T again within the window → 200 and a working token. Then one sibling used → the other sibling → 401 and the session revoked. T after the window → 401 and revoked. A fourth grace swap → 401 and revoked. A clock moved by the injected `Clock`, not by waiting.
- **IT-AUTH-15** (new): three grace requests for the same T at once → all 200, `grace_swaps` = 3, the session stays live; a child's use racing its parent's grace swaps → only 200 or 401, never a deadlock or 500.
- The route-test matrix doesn't change: `/v1/auth/refresh` documents the same statuses.

### 6. Outside this repository
- **Admin web app:** its DEF-009 depends on this CR. It keeps `keepalive`, its mock gets the same grace, and an E2E with added latency checks the reload once the backend is deployed (its own CR, there CR-002).
- **Staff web app:** no change needed. It benefits once deployed.
- **Handbook** (not baselined, same PR): technical-reference's refresh-token row and build-from-scratch's step 3 line.

## Impact
| Item | Change | When |
|---|---|---|
| design/core-and-auth.md | v1.3: §8.3 grace rules and setting, §13 setting list, §15 tests, §16 item 4 note "revisited by CR-017" | On approval |
| architecture.md | v1.9: §7 two sentences | On approval |
| `docs/api/openapi.yaml` | 1.1.2: one description | On approval |
| `RefreshService`, `RefreshTokenRepository` (split out of `StaffSessionRepository`), `AppConfig`, `V12__refresh_grace.sql` | Grace path, sibling supersede, setting | After approval, one PR |
| `SessionTest` (IT-AUTH-07, -14, -15), rtm.md (FR-010 row) | Tests and trace | Same PR |
| Settings: `deploy/staging/staging.env`, `deploy/production/production.env` | None: `REFRESH_GRACE_SECONDS` defaults to 10 | Live at the next release |
| Requirements | None: FR-010 doesn't name the reuse rule | — |

## What stays the same
- Refresh tokens are 256 random bits, stored only as SHA-256, and never kept for replay (the engineering rule on secrets in idempotent responses).
- A used token after the window, or a superseded one, still revokes the session and is audited.
- Lifetimes (12 h idle, 24 h from sign-in), the cookie (`HttpOnly; Secure; SameSite=Strict`, path `/api/v1/auth`), the rate limit (120/min/IP) and the two-tab rule.

## Risks
- **A thief gets a 10-second head start.** Someone who copies a refresh token and uses it within 10 s of the real use gets a working sign-in, and the sign-in isn't ended. Copying the token is already hard: it lives in an `HttpOnly` cookie no script can read, sent only to the app's own `/api/v1/auth`. The swap cap (3) and the supersede rule stop it from turning into an open-ended copy: as soon as the real browser refreshes again (within 15 min), the thief's sibling stops working. Accepted by most identity providers for the same race.
- **A rare sign-out remains:** a tab closed mid-refresh and reopened much later still holds the old token and is signed out. That's a fresh sign-in, not a defect.

## Owner decisions needed
1. Approve, reject or defer this CR.
2. The window: **10 s** (recommended: covers a reload on a slow connection; 0 turns it off), or another value up to 30 s.
3. Then a release (`deploy/release.sh`) so the admin app's E2E can check the reload against staging.

## Cost and schedule
No cost. About half a day: one migration, the service change, three tests, the documents.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-11-04 | Approved | Manh Nguyen (product owner) | "approve". The window is the recommended 10 s. Applied the same day: core-and-auth v1.3, architecture v1.9, contract 1.1.2, migration V12 and the service change in the same PR |
