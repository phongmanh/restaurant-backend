# G4 Code complete: readiness review

| Field | Value |
|---|---|
| Prepared | 2026-10-17, for the owner's gate decision (the gate is the owner's; this review only gathers the evidence) |
| Code reviewed | `main` at `cc002b9` (PRs #3 to #13 merged): Phase 4 build steps 1–9, the RTM, CR-009 and its option A |
| Recommendation | **Not ready yet.** Run one hardening step (H1–H4 below), then approve G4 with one waiver (FR-022's two image routes). Progress: H1–H4 done; H3's contract gaps closed by CR-010 (approved 2026-10-17); security review done, no exploitable flaw (§4 item 3) |

**In plain words:** G4 asks "is the code finished and tested well enough to start testing it as a whole system?" The features are all built, every Must requirement has passing tests, and the code is clean. What isn't finished is the *systematic* testing the test plan promised: every endpoint checked for each kind of failure, and every response checked against the API contract. Those checks find the kind of bug a feature-by-feature test misses, such as an endpoint that forgets a check the others make.

## 1. Criteria
| # | Criterion | Source | Result | Evidence |
|---|---|---|---|---|
| 1 | Unit, integration and contract tests pass | test-plan §7 (exit) | **Met** | `./gradlew build`: 244 tests pass, 2 skipped (the live Ably tests, which need a key). After H2: 251 pass, 2 skipped. After H3: 259 pass, 2 skipped. After the security review: 264 pass, 2 skipped |
| 2 | Coverage ≥ 80 % in services and domain | test-plan §7; NFR-MAINT-01 | **Met** | Kover on `*.domain.*` and `*.application.*`: 98.9 % of lines, 82.5 % of branches; no package below 96.9 % |
| 3 | No ktlint or detekt findings | test-plan §7; NFR-MAINT-01 | **Met** | Both run in `./gradlew build`, in CI on every PR |
| 4 | Every Must requirement built, with passing integration tests | RTM | **Met** | rtm.md: every Must FR has code and "IT pass". Must NFRs proved by load, resilience or operations tests are Phase 5 or 6 by design |
| 5 | Route-test matrix (401 / 400 / 404 / success) for every endpoint | RTM coverage checks ("0 at G4"); test-plan §2 | **Met, with the waiver** | After H2: 91 of 93 operations complete; the two left are the unbuilt image routes (7 cells, criterion 8's waiver), the only lines in `scripts/route-matrix-allowed.txt`. `./gradlew build` fails on any other gap (H4). At review: 60 of 93 (14 before H1), missing per column 401 2, 400 19, 404 20, 2xx 8. A status counts only where the contract documents it for that operation. Appendix A lists the gaps found at review |
| 6 | Authorisation matrix: every operation × {no token, wrong token kind, each role, another restaurant, another guest session} | test-plan §2 | **Partly met** | Token rows met by H1 (`AuthenticationMatrixTest`: every bearer operation × no token, staff, admin and guest token). Role and tenant checks are tested feature by feature (IT-…-xx with 403 and 404 cases; IT-CORE-06/07 for RLS), but there is no table-driven matrix over all operations |
| 7 | Contract tests: responses validated against `openapi.yaml`; no breaking change against the last released contract | test-plan §2 | **Met** (H3, with CR-010) | `ContractCheck` in `testApp` checks every response of the 259 tests: status, media type, schema with formats, and no undocumented field. CI runs `oasdiff breaking` against the G3 contract (`scripts/contract-breaking.sh`). It found statuses the contract didn't list; CR-010 (approved) documents them. Before H3: no body validation, no `oasdiff` |
| 8 | Every registry entry routed (`notYetRouted` empty) | `support/Api.kt` ("must be empty at G4") | **Not met, waiver proposed** | 2 entries: `PUT` and `DELETE /v1/staff/menu/items/{itemId}/image`. FR-022 is a Should that waits for TBD-13 (image store) |
| 9 | Designs and RTM describe the code | process rules | **Met** | CR-009 applied (six designs at v1.1); RTM filled (PR #12) |
| 10 | Open questions resolved | STATUS | **Met** | Q-1 to Q-15 resolved. Open TBDs don't block G4: TBD-10 blocks G5; TBD-4, TBD-9, TBD-12, TBD-13 don't block a gate |

## 2. Gaps, and how to close them
| # | Gap | Proposed | Size |
|---|---|---|---|
| H1 | 401 not checked on 75 operations (criterion 5) | One table-driven test over the contract: for every operation secured by a bearer token, no token → `401`, and each kind of token the operation doesn't accept → `401`, except a staff token on an admin-only operation → `404 not_found` (core-and-auth §6). Closes the 401 column and the token rows of criterion 6. **Done:** `AuthenticationMatrixTest` (H1, 2026-10-17) | Small |
| H2 | Success never reached on 6 built operations; 400 or 404 missing in 39 places | Tests for each, mostly the admin-side member routes of step 4 (`GET /v1/admin/restaurants/{restaurantId}`, `POST /v1/admin/restaurants/{restaurantId}/members`, `GET /v1/admin/restaurants/{restaurantId}/members/{staffId}`, `PATCH /v1/admin/restaurants/{restaurantId}/members/{staffId}`, `POST /v1/admin/restaurants/{restaurantId}/members/{staffId}/reactivate`, `POST /v1/admin/restaurants/{restaurantId}/members/{staffId}/invitation`), unknown IDs (404) and bad bodies or queries (400). **Done:** 6 new tests and 7 extended ones close all 42 gaps; the allowed list holds only the image routes. Admin member routes end to end (`StaffMembersTest`), a staff token on `/v1/admin/admins` → `404 not_found` (IT-REST-03), and 400s and 404s across auth links, restaurants, admins, menu, tables, orders and catch-up (H2, 2026-10-17) | Medium |
| H3 | Responses not checked against the contract (criterion 7) | Validate every JSON response the tests receive against `openapi.yaml` (a schema validator in `testApp`, so all 244 tests check their answers); add `oasdiff breaking` in CI against the G3 contract. **Done:** `ContractCheck` fails a test on an undocumented status or media type, a schema failure (formats included) or a field the contract doesn't name; `scripts/contract-breaking.sh` runs oasdiff in CI. The first run failed 13 tests, all on contract gaps (no schema mismatch in the service's fields): shared codes (`415`, `500`, `503`) the contract lists on 2 operations, `409` on member reactivation, `400` on the waiter-call list. CR-010 (approved) documents them (H3, 2026-10-17) | Medium |
| H4 | `scripts/route-matrix.py` is a report, not a rule | Run it in CI after the tests, failing when a documented status isn't seen, with the two image routes as the only allowed gaps. **Done:** the `routeMatrix` task runs `route-matrix.py --check` in `./gradlew build` after the tests; it fails on a gap not in `scripts/route-matrix-allowed.txt` and on a listed gap a test now covers. The list holds the image routes plus the 42 gaps H2 closes, and H2 empties that second part (H4, 2026-10-17) | Small |

Criterion 6's role rows (each role × each operation) stay as feature tests unless the owner wants them in the matrix too: each operation's allowed roles live in the designs, not in the registry, so a full role matrix means writing that table down once (medium).

## 3. Proposed waiver and conditions
- **Waiver:** FR-022 (Should) and its two routes and IT-MENU-14 stay unbuilt until TBD-13 (image store) is decided. Nothing else depends on them.
- **Carried to G5 (unchanged):** TBD-10 (legal review of data residency) is G2's condition and blocks G5.
- **Accepted risks carried on:** R-11 to R-14 (free-plan limits).

## 4. Owner actions before the gate
1. **Run the live Ably test once** (your choice at step 8): `ABLY_API_KEY=… ./gradlew test --tests '*AblyLiveTest*'` in your own terminal. STATUS has no record of a run.
2. ~~**Decide the hardening scope.**~~ Decided 2026-10-17: H1–H4 in full (STATUS Decisions).
3. ~~**Consider a security review of the code before G4.**~~ Done 2026-10-17 at the owner's request: [security-review.md](security-review.md). No exploitable flaw found against R-03 (a missed tenant check) or R-10 (a flaw in our own sign-in); 3 hardening fixes and a schema test that every tenant table keeps forced RLS. Two items logged for later: L1 (guest idempotency scope, a CR if wanted) and L2 (`x-real-ip` off Vercel, Phase 5). The ZAP scan and ASVS checklist stay in Phase 5.

## 5. How this review was made
- Statuses per operation: a test-only recorder (`support/RouteLog.kt`) logs the method, path and status of every response during `./gradlew test` (no bodies, tokens or queries) into `build/route-log/`; `python3 scripts/route-matrix.py` maps them onto the contract's operations and the statuses each documents.
- Coverage: `./gradlew koverXmlReport` (the same filters as the build's Kover gate).

## Appendix A. Operations with a documented status no test produced
At review (before H1 and H2). H2 closed every row but the two image routes; `python3 scripts/route-matrix.py --markdown` prints the current table after `./gradlew test`.

`n/a`: the contract doesn't document that status for the operation.

| Operation | 401 | 400 | 404 | 2xx | Statuses seen |
|---|---|---|---|---|---|
| `POST /v1/auth/mfa` | yes | **no** | n/a | yes | 200, 401 |
| `POST /v1/auth/links/inspect` | n/a | **no** | yes | yes | 200, 404 |
| `POST /v1/auth/password-resets/complete` | n/a | **no** | **no** | yes | 204 |
| `POST /v1/staff/members` | yes | **no** | n/a | yes | 201, 401, 403, 409 |
| `PATCH /v1/staff/members/{staffId}` | yes | **no** | yes | yes | 200, 401, 403, 404, 409, 412, 428 |
| `GET /v1/admin/restaurants/{restaurantId}` | yes | n/a | yes | **no** | 401, 404 |
| `PATCH /v1/admin/restaurants/{restaurantId}` | yes | **no** | **no** | yes | 200, 401 |
| `POST /v1/admin/restaurants/{restaurantId}/suspend` | yes | yes | **no** | yes | 200, 400, 401 |
| `GET /v1/admin/restaurants/{restaurantId}/members` | yes | **no** | **no** | yes | 200, 401 |
| `POST /v1/admin/restaurants/{restaurantId}/members` | yes | **no** | **no** | **no** | 401 |
| `GET /v1/admin/restaurants/{restaurantId}/members/{staffId}` | yes | n/a | **no** | **no** | 401 |
| `PATCH /v1/admin/restaurants/{restaurantId}/members/{staffId}` | yes | **no** | **no** | **no** | 401 |
| `POST /v1/admin/restaurants/{restaurantId}/members/{staffId}/deactivate` | yes | n/a | **no** | yes | 200, 401 |
| `POST /v1/admin/restaurants/{restaurantId}/members/{staffId}/reactivate` | yes | n/a | **no** | **no** | 401 |
| `POST /v1/admin/restaurants/{restaurantId}/members/{staffId}/invitation` | yes | n/a | **no** | **no** | 401 |
| `POST /v1/admin/restaurants/{restaurantId}/members/{staffId}/password-reset` | yes | n/a | **no** | yes | 201, 401 |
| `GET /v1/admin/admins` | yes | **no** | **no** | yes | 200, 401 |
| `POST /v1/admin/admins` | yes | yes | **no** | yes | 201, 400, 401, 409 |
| `POST /v1/admin/admins/{staffId}/password-reset` | yes | n/a | **no** | yes | 201, 401, 409 |
| `POST /v1/admin/admins/{staffId}/totp-reset` | yes | n/a | **no** | yes | 201, 401, 409 |
| `POST /v1/staff/menu/categories` | yes | **no** | n/a | yes | 201, 401, 403, 409 |
| `PATCH /v1/staff/menu/categories/{categoryId}` | yes | **no** | yes | yes | 200, 401, 403, 404, 412 |
| `PUT /v1/staff/menu/items/{itemId}/image` | **no** | **no** | **no** | **no** | none |
| `DELETE /v1/staff/menu/items/{itemId}/image` | **no** | n/a | **no** | **no** | none |
| `PATCH /v1/staff/tables/{tableId}` | yes | **no** | yes | yes | 200, 401, 403, 404, 409, 412, 428 |
| `DELETE /v1/staff/tables/{tableId}` | yes | n/a | **no** | yes | 204, 401, 403, 409 |
| `POST /v1/staff/tables/{tableId}/session` | yes | n/a | **no** | yes | 200, 201, 401, 403, 409 |
| `POST /v1/staff/table-sessions/{sessionId}/move` | yes | yes | **no** | yes | 200, 400, 401, 403, 409, 412, 428 |
| `GET /v1/guest/orders` | yes | **no** | n/a | yes | 200, 401 |
| `POST /v1/staff/table-sessions/{sessionId}/orders` | yes | **no** | yes | yes | 201, 401, 403, 404 |
| `GET /v1/staff/orders` | yes | **no** | n/a | yes | 200, 401, 403 |
| `POST /v1/staff/orders/{orderId}/lines/{lineId}/reject` | yes | **no** | yes | yes | 200, 401, 403, 404, 409, 412 |
| `GET /v1/guest/events` | yes | **no** | n/a | yes | 200, 401 |
