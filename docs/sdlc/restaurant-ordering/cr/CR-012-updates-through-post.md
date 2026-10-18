# CR-012: Send the seven updates as `POST …/update`, because Vercel turns a `PATCH` into its own `412`

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-012 | Approved | 2026-10-18 | Claude, from D-1 (Phase 5 step 5a) and the Q-19 spike; option (b) chosen by the owner on 2026-10-18 | Manh Nguyen (product owner) | `docs/api/openapi.yaml` 1.0.1; design/core-and-auth.md v1.0, restaurants.md, menu.md and tables.md v1.1; architecture.md v1.5 (§8); rtm.md |

## Summary
On Vercel, every `PATCH` with `If-Match` saves the change and then answers the client with Vercel's own `412`, not the service's `200`. This CR adds a `POST` twin at `<the same path>/update` for each of the seven `PATCH` operations. Each twin takes the same body, headers and rules and gives the same answers, and clients use it. The `PATCH` operations stay, because removing one would break `/v1`. They're marked deprecated, with the reason. Every change is additive.

## Why
**D-1** (found by the step 5a system suite on staging): Vercel's edge compares the request's `If-Match` with the `ETag` of the function's *response*, after the function has run. An update always makes a new version, so the two never match. The client gets `412` with `X-Vercel-Error: PRECONDITION_FAILED`, believes the save failed, and reloads to find it saved. That breaks seven operations on the deployed service (FR-020, FR-030, FR-041, FR-044). CI can't see it, since tests don't go through Vercel.

**The Q-19 spike** (`spikes/q19-etag/`, run 2026-10-18 on Vercel, 13 cases × 3) settled the options:
- **Option (a) doesn't work.** A `PATCH` `200` *without* an `ETag` still gets the edge's `412` (F1, F5), with or without `Cache-Control`. The edge counts a missing `ETag` as one that doesn't match.
- Only `2xx` answers are affected. The service's own `412`, `428` and `404` reach the client unchanged (F2, F3, F4, F6).
- **`POST` with a stale `If-Match` and a new `ETag` reaches the client unchanged** (P1), every time. So does `PUT` (X1). `DELETE` (P2) passes too. This CR rests on P1.

The service already uses `POST` with `If-Match` for its action endpoints (session move, order status, bill close). Those pass the edge, so an update sent as `POST` follows a pattern the service has already proven.

**Other options considered:**
- **(c) Move off Vercel (CR-001).** Rejected for now. It's a platform change late in Phase 5, and it leaves the free plans.
- **`PUT` instead of `POST`.** It passed too (X1). But `PUT` means "replace the whole resource", and these are partial edits. An edge that starts evaluating `If-Match` on `PATCH` could just as well start on `PUT`.
- **A method-override header** (`X-HTTP-Method-Override: PATCH` on a `POST`). Rejected: the contract can't describe it per operation, and the endpoint registry and IT-CORE-10 match on method and path.

## Proposed change
### 1. Contract (`docs/api/openapi.yaml` 1.0.1 → 1.1.0, a new capability)
Add one `POST` operation per row. Each takes the `PATCH`'s request body, its `If-Match` parameter, its responses (`200` with the new `ETag`, and every error status it lists), its `x-auth` group and its `x-allowed-when-suspended`:

| `PATCH` today (kept) | New `POST` | `operationId` | Allowed when suspended |
|---|---|---|---|
| `/v1/staff/restaurant` | `/v1/staff/restaurant/update` | `updateOwnRestaurantPost` | No |
| `/v1/staff/members/{staffId}` | `/v1/staff/members/{staffId}/update` | `updateMemberPost` | No |
| `/v1/admin/restaurants/{restaurantId}` | `/v1/admin/restaurants/{restaurantId}/update` | `updateRestaurantPost` | Yes |
| `/v1/admin/restaurants/{restaurantId}/members/{staffId}` | `/v1/admin/restaurants/{restaurantId}/members/{staffId}/update` | `updateRestaurantMemberPost` | Yes |
| `/v1/staff/menu/categories/{categoryId}` | `/v1/staff/menu/categories/{categoryId}/update` | `updateMenuCategoryPost` | No |
| `/v1/staff/menu/items/{itemId}` | `/v1/staff/menu/items/{itemId}/update` | `updateMenuItemPost` | No |
| `/v1/staff/tables/{tableId}` | `/v1/staff/tables/{tableId}/update` | `updateTablePost` | No |

- **No `Idempotency-Key`**, the same as the `PATCH`. `If-Match` already makes a retry harmless: a retry of a saved change gets `412`, and the client reloads. The architecture §10 list doesn't change.
- **Each `PATCH`** gets `deprecated: true` and one line in its description: "Through Vercel's edge this answers `412` even when the change is saved (D-1); use `POST …/update`." It keeps working everywhere else (CI, local, any other platform). If `scripts/contract-breaking.sh` flags `deprecated: true`, the description line alone is used. (Applied: oasdiff accepts it, so both are used.)
- **`info.description`** gets one line: an update to a versioned resource is `POST <path>/update` with `If-Match`; the `PATCH` forms are kept only for compatibility within `/v1`.
- **No path collides with an ID.** The IDs are UUID v7, so `update` can't be one. The only existing sub-paths on these resources are actions such as `…/members/{staffId}/reactivate`.

### 2. Code
- **`core/http/Endpoints.kt`:** seven new entries (`HttpMethod.Post`, `ifMatch = true`, the same `allowedWhenSuspended`).
- **The route files:** each update handler is declared once and bound to both entries, so the two methods can't drift apart. The role check before reading the input stays as it is (401 → 403 → 400 → 404 → 409/412).
- **Behaviour:** unchanged for the `PATCH`. The response keeps its `ETag`, which P1 shows passes on a `POST`.

### 3. Tests
- **Feature tests:** each existing update test (RestaurantProfileTest, StaffMembersTest, the menu and table tests) runs for both methods. The route-test matrix then sees the 401, 400, 404 and success of every new operation, and `route-matrix-allowed.txt` doesn't grow. The IT IDs stay the same, and each test's name says which method it sent.
- **IT-CORE-10:** checks the seven new entries against the contract, with no change to the test.
- **System suite** (`src/systemTest`): MenuTest, StaffAccessTest, GuestOrderingTest and IsolationTest send `POST …/update`. With that, step 5a's recorded run can pass on staging, once Grafana's `OTEL_*` settings are in place (TBD-12).

### 4. Documents
| Document | Change |
|---|---|
| design/core-and-auth.md §5 | v1.0 → v1.1. Updates are `POST <path>/update` with `If-Match`; `PATCH` is kept for compatibility, with D-1 as the reason |
| design/restaurants.md §4, §7, §11 | v1.1 → v1.2. The three edits shown as `POST …/update` (the `PATCH` noted as deprecated) |
| design/menu.md §5, §8 | v1.1 → v1.2. The same for the category and item edits |
| design/tables.md §6, §8 | v1.1 → v1.2. The same for the table edit |
| architecture.md §8 | v1.5 → v1.6. One line under Vercel's platform constraints: the edge evaluates `If-Match` on `PATCH` after the function ran, so the service's updates are `POST …/update` (D-1, CR-012) |
| rtm.md | FR-044's row names `POST /v1/staff/restaurant/update` |
| CLAUDE.md | A new engineering rule: "Updates are `POST …/update` with `If-Match`; never add a `PATCH` operation." |
| STATUS.md | Q-19 and D-1 close once the fix is deployed and the system suite passes on staging; R-18 stays open (its mitigation worked as intended) |

## Impact
| Item | Change | When |
|---|---|---|
| `docs/api/openapi.yaml` | 1.0.1 → 1.1.0: 7 operations added, 7 deprecated | First, before the code (contract first) |
| Code | 7 registry entries, 7 route bindings | One PR, after approval |
| Tests | Feature tests for both methods; the system suite on `POST` | Same PR |
| Designs, architecture, RTM, CLAUDE.md | As in §4 above | Same PR |
| The Q-19 spike | `spikes/q19-etag/` kept in the repository with its results, as spikes S-1 to S-5 are (owner, 2026-10-18); the Vercel project `restaurant-q19-spike` removed (the owner) | Same PR |
| Clients | Use `POST …/update` from the start. No mobile client has shipped, so none needs an upgrade | — |

## What stays the same
- Every `/v1` operation, schema and status already in the contract, including the seven `PATCH` operations and their behaviour.
- Optimistic locking: `If-Match` required (missing → `428`, stale → `412`), the `ETag` on the `200`, and the version check in the `UPDATE`.
- The role checks, tenant isolation, audit rows and events of each update.

## Risks this adds
- **Two ways to do one thing.** A later change could be made to one method and not the other. Mitigated: one handler serves both, and the tests run every update case through both.
- **Vercel's edge may change again** (R-18). The system suite's contract check on every staging deploy is what found D-1. It would catch the same thing on `POST`.

## Cost and schedule
- **Cost:** $0.
- **Schedule:** before step 6b's load test, which sends updates through Vercel. It's one PR, with the contract change first.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-18 | Approved | Manh Nguyen (product owner) | "CR-012 approved, apply it" |
