# ADR-0001: One Gradle module, packages by feature

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Accepted | 2026-10-13 | Manh Nguyen (tech lead) | NFR-MAINT-01 |

## Context
The skill's size rule picks Gradle modules per bounded context (layout B) only for one of these: more than about 5 bounded contexts, more than about 3 developers, slow builds, or parts expected to become separate deployments.

This service has five bounded contexts: restaurants, menu, tables, orders and reports. Real-time is an infrastructure concern. The team is small (TBD-9), and everything ships as one Vercel project.

## Options
1. **A: one module, packages by feature** (default)
   - Pros: simplest build; one bundle for Vercel; fastest to start.
   - Cons: feature boundaries are enforced only by convention.
2. **B: Gradle modules per feature**
   - Pros: the compiler enforces boundaries.
   - Cons: more build setup and slower configuration, for no current driver.

## Decision
Layout A: `com.<org>.restaurant.{core, restaurants, menu, tables, orders, reports, realtime}`, each with `domain/application/persistence/api` packages and its own Koin module.

## Consequences
- Positive: one deployable unit, which matches the platform; a later move to B stays mechanical.
- Negative: cross-feature access must go through ports (`MenuCatalog`, `SessionAccess`, `OrderEvents`). An architecture test in Phase 4 forbids feature-to-feature imports outside those ports.
- Follow-ups: no shared `api-contract` KMP module for now; revisit when TBD-2 decides the mobile platforms.
