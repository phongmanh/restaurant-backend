# ADR-0007: Hand-written OpenAPI contract, validated by contract tests

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Accepted | 2026-10-13 | Manh Nguyen (tech lead) | NFR-COMPAT-01 |

## Context
The skill's default generates the served OpenAPI document from the routes with the Ktor compiler plugin and diffs it against the frozen contract. That generation is experimental in Ktor 3.6, so using it needs an ADR. It would also add a compiler-plugin dependency to a build that may have to target GraalVM native (ADR-0002).

## Options
1. **Generate from code (experimental) and diff against the contract**
   - Pros: catches drift automatically.
   - Cons: experimental API; tighter coupling between Kotlin and the Ktor versions.
2. **Hand-written contract; every route-test response validated against the contract schema; a test that compares the route listing with the contract's paths; `oasdiff breaking` against the last released contract**
   - Pros: stable tooling.
   - Cons: drift is caught only where tests exercise the routes.

## Decision
Option 2. The route-test matrix (401/400/404/success per endpoint) runs through a response validator. Revisit option 1 when generation is stable.

## Consequences
- Positive: no experimental dependency; the contract stays the design-first source.
- Negative: technical debt D-01 (possible drift in untested responses).
- Follow-ups: choose the validator library in Phase 4 (an OpenAPI 3.1 request/response validator for the JVM).
