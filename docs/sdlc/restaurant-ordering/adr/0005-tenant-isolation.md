# ADR-0005: Tenant isolation by restaurant ID in services, with PostgreSQL row-level security as a second line

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Accepted (RLS mechanics verified in S-2) | 2026-10-13 | Manh Nguyen (tech lead) | FR-042, NFR-SEC-02 |

## Context
Isolation is quality goal 1. One missed filter in one query would leak a restaurant's data to another.

## Options
1. **Shared schema, `restaurant_id` on every tenant row, checks in the service layer only**
   - Pros: simple.
   - Cons: a single missed filter leaks data.
2. **Option 1 + PostgreSQL RLS** keyed by `SET LOCAL app.restaurant_id` in each transaction
   - Pros: defence in depth.
   - Cons: every transaction must set the variable; platform-admin work needs a bypass role.
3. **Schema or database per tenant**
   - Pros: strong isolation.
   - Cons: 500 schemas of migrations; connection pooling per tenant; overkill.

## Decision
Option 2:
- `Caller` carries `restaurantId`. Every repository method takes it, and every query filters by it.
- `Transactor.inTransaction(restaurantId)` issues `SET LOCAL app.restaurant_id`. RLS policies on every tenant table compare against it.
- Platform-admin operations use a separate transactor path with an explicit, audited bypass.
- Composite indexes lead with `restaurant_id`.

## Consequences
- Positive: two independent controls; tenant ID available for future sharding.
- Negative: slightly more setup per transaction; tests must cover the RLS policies.
- Follow-ups: integration tests prove that a query without the filter returns nothing under RLS; an authorisation-matrix test per endpoint.
