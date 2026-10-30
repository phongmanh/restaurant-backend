# CR-016: The staff and admin web apps' origins go on the CORS allow-list too

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-016 | Approved | 2026-10-30 | Claude, from the staff web app's first sign-in against production (Q-29) | Manh Nguyen (product owner) | design/core-and-auth.md v1.1 (§2 CORS) |

## Summary
A web app that reaches the API through its own `/api` rewrite still needs its origin on the API's CORS allow-list (`ALLOWED_ORIGINS`). The rewrite forwards the browser's `Origin` header, the API sees a host other than its own, and Ktor's CORS plugin refuses the request with a bare `403`. Core-and-auth §2 says CORS "only serves the customer web app"; that sentence is wrong and this CR corrects it. Credentials still never cross origins.

## Why
- **Found 2026-10-30** with the staff web app's first deploy (`restaurant-staff-web.vercel.app`): every sign-in failed with `403` and no body.
- **Browsers send `Origin` on every `POST`**, even to their own site (they leave it off a same-origin `GET`). So the app's reads worked and every write failed.
- **The rewrite keeps the header.** The browser calls `https://restaurant-staff-web.vercel.app/api/v1/auth/sign-in`; Vercel forwards it to `restaurant-production.vercel.app` with `Origin: https://restaurant-staff-web.vercel.app`. Ktor compares that with its own host, finds no match on the list, and answers `403` before any route runs.
- **Reproduced with `curl`** through the rewrite: the same `POST /v1/auth/sign-in` answers `403` with that `Origin` and `400` (the empty body's validation) without it.
- **No earlier test could see it.** The staff app's tests ran without a real backend (an accepted deviation there), the backend's `HttpConventionsTest` sends `Origin` only for the customer app's origin, and the design said the rewrite needs no entry.

## Proposed change
1. **Production's settings** (`deploy/production/production.env`, not baselined): `ALLOWED_ORIGINS=https://restaurant-staff-web.vercel.app`. The admin app's origin joins when it first deploys, and the customer web app's as planned. In the same redeploy `STAFF_APP_URL` becomes that domain, Q-29's third item (`vercel alias ls` shows no suffix).
2. **core-and-auth §2 (v1.1 → v1.2):** the CORS paragraph becomes: "An allow-list of every web app's origin, from config: the customer web app, which calls the API directly, and the staff and admin web apps, whose `/api` rewrite forwards the browser's `Origin` header. An unlisted origin gets `403`. Credentials are never allowed cross-origin: the staff and admin apps' refresh cookie belongs to the app's own address (§8.3), and the customer web app sends a bearer token and no cookies."
3. **Code:** no behaviour changes. The comment on `install(CORS)` in `Http.kt` says the same, and `HttpConventionsTest` asserts the `403` for an unlisted origin, so the plugin's behaviour is pinned.
4. **Handbook** (not baselined, same PR): technical reference §CORS, build-from-scratch's same-origin notes and settings table, operations handbook item 7.
5. **CLAUDE.md:** an engineering rule, "a rewrite forwards the browser's `Origin`".

## Impact
| Item | Change | When |
|---|---|---|
| `deploy/production/production.env` | `ALLOWED_ORIGINS`, `STAFF_APP_URL` | Now (not baselined); live after the owner's `deploy/release.sh production --env` |
| design/core-and-auth.md | v1.2: §2 CORS paragraph | On approval |
| `Http.kt`, `HttpConventionsTest.kt` | Comment, one assertion | Now |
| Handbook, CLAUDE.md | Wording | Now |
| Contract, requirements, architecture, other designs | None: architecture §11 already says "CORS allow-list" without naming the apps | — |

## What stays the same
- `allowCredentials = false`: no cookie or credential is ever sent cross-origin.
- The refresh cookie's `SameSite=Strict` and its path `/api/v1/auth`, and §8.3's CSRF reasoning: a cross-site form still can't send `application/json` without a preflight, and the list still refuses other origins.
- The same-origin rewrite itself (core-and-auth §16 Q2).

## Risks
Listing the staff app's origin lets a script on that origin call the API directly, cross-origin. It can already do so through the rewrite, so nothing new is reachable; without credentials, a direct call carries only the bearer token the app holds anyway. No new risk.

## Owner decisions needed
1. Approve, reject or defer this CR (the design wording in §2).
2. Run `deploy/release.sh production --env` so production gets the new settings. That step doesn't wait for the CR: `production.env` isn't baselined, and the staff app can't sign in until it's done.

## Cost and schedule
No cost. A redeploy of production's settings, about two minutes.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-30 | Approved | Manh Nguyen (product owner) | "approve CR-016". Applied the same day: core-and-auth v1.2 (§2 CORS). The settings were already live in deployment `q4zr9cdzp` |
