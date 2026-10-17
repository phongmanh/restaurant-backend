# CR-010: Document in the contract the statuses the service already answers

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-010 | Approved | 2026-10-17 | Claude, from G4 hardening H3 (contract tests) | Manh Nguyen (product owner) | `docs/api/openapi.yaml` 1.0.0 |

## Summary
H3 checks every response the test suite receives against `docs/api/openapi.yaml`. The service matched the contract's schemas everywhere, but it answers some statuses the contract doesn't list. This CR documents them. No behaviour changes, and every change is additive: `oasdiff breaking` against the G3 contract finds nothing.

## Why
A client generated from the contract, or a developer reading it, should know every status an operation can return. H3 found three gaps:

1. **Shared codes aren't documented per operation.** core-and-auth §3 lists `413 payload_too_large`, `415 unsupported_media_type`, `426 client_upgrade_required`, `500 internal_error` and `503 service_unavailable` as shared codes that any endpoint can answer. The contract lists them on only 2 of 93 operations (sign-in's `503`, and the unbuilt image upload). The tests produced `415` on 26 operations with a body (a request without `Content-Type: application/json`), `500` on the bill close (an injected ledger failure, IT-ORD-16) and `503` on the realtime token (no Ably key in dev).
2. **Reactivating a member can be refused at the seat limit.** restaurants §7: at most 100 invited and active accounts, else `409 member_limit_reached`. Reactivation adds an active account, so the service checks the limit there too (`StaffService`; IT-REST-15 tests it), but the contract lists no `409` for either reactivate route.
3. **An unknown `status` filter on the waiter-call list is a `400`.** The contract's `status` parameter is `enum: [open, acknowledged]`, and the service answers `400 validation_failed` to anything else (IT-TBL-11), but the operation lists no `400`.

## Proposed change
All in `docs/api/openapi.yaml`, version 1.0.0 → 1.0.1 (it documents existing behaviour and adds no capability):

1. **`info.description`:** one paragraph after the conventions: any operation may also answer the shared codes of core-and-auth §3 without listing them (`413` and `415` only operations with a body; `426`, `500`, `503 service_unavailable`); an operation lists one only when it has its own reason to. This avoids adding 5 responses to each of 93 operations, and it matches how the design states the rule.
2. **`POST /v1/staff/members/{staffId}/reactivate`** and **`POST /v1/admin/restaurants/{restaurantId}/members/{staffId}/reactivate`:** add `'409': Conflict`.
3. **`GET /v1/staff/waiter-calls`:** add `'400': BadRequest`.

The alternative for 1 is to list the shared codes on every operation (≈ 400 lines, but a generated client sees each one). The description-only form is proposed because the error model already treats them as global.

## Impact
| Item | Change | When |
|---|---|---|
| `docs/api/openapi.yaml` | 1.0.0 → 1.0.1, the three changes above | Applied on branch `phase4/h3-contract-tests` |
| Contract tests (`ContractCheck`) | Accept the shared codes on any operation (with a `Problem` body and the matching `code`); the rest must be documented | Same branch |
| Route-test matrix | `GET /v1/staff/waiter-calls` now documents a `400`; IT-TBL-11 already produces it | Same branch |
| Designs, requirements, RTM | None: core-and-auth §3, restaurants §7 and tables §4 already state the behaviour | — |
| Code | None | — |

## What stays the same
- Every `/v1` schema, operation and status already in the contract.
- The service's behaviour.

## Risks this adds
None found. Clients must already handle an unknown error status as a generic error (core-and-auth §3).

## Cost and schedule
- **Cost:** $0.
- **Schedule:** applied with H3.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-17 | Approved | Manh Nguyen (product owner) | The shared codes stated once in `info.description`, not listed per operation |
