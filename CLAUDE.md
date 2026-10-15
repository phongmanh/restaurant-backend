# restaurant-backend

Multi-restaurant ordering backend (Kotlin, Ktor 3, JDK 25, PostgreSQL), run as a phased SDLC with gates G1–G6. The owner is product owner and tech lead in one person, and new to backend work: explain backend concepts plainly.

## Resume here
1. Read `docs/sdlc/restaurant-ordering/STATUS.md` first. It is the single source of truth for the phase, gates, decisions, risks, open items and spikes.
2. Don't read whole documents. Find what you need with `grep -n '^## '` and read only that section with `sed -n`. Architecture sections: §8 deployment, §9 capacity, §17 spikes (exit criteria).
3. Each spike has a README in `spikes/<id>/` with its questions, how to run it, exit criteria and results. Continue from there.

## Document map (`docs/sdlc/restaurant-ordering/`)
| File | Holds |
|---|---|
| requirements.md | v1.3 (baselined at G1, changed by CR-002, CR-003 and CR-004). Changing it needs a CR |
| architecture.md | v1.1: approved at G2 (2026-10-15) with the TBD-10 condition, then changed by CR-004. Changing it needs a CR |
| adr/0001–0009, 0011 | Decisions; ADR-0002 (Vercel) is Accepted with conditions (paid-plan cold-start check before G5); 0003 (live updates through Ably) is Accepted after S-3; 0004 (Neon PostgreSQL) and 0008 (rate limiting: WAF + Upstash) are Accepted; 0010 is reserved by CR-001; 0011 (built-in staff sign-in) is Accepted too |
| cr/ | Change requests. CR-001 (move off Vercel) is Deferred; CR-002 (built-in staff accounts), CR-003 and CR-004 (menu read and image limits) are Approved |
| design/ | Phase 3 feature designs: core-and-auth (Draft 0.5), restaurants (Draft 0.2), menu (Draft 0.2); tables, orders, realtime and reports come next |
| `docs/api/openapi.yaml` (repo root) | The API contract, built up feature by feature and frozen at G3 |
| rtm.md, test-plan.md | Traceability and test plan |
| cost-estimate.md | Monthly cost (TBD-11), v0.2 for the owner's review; S-3 confirmed the Ably line |

## Process rules
- One phase at a time; gates are the owner's decision. Never mark a gate or a CR approved yourself.
- A change to a baselined document goes through a CR logged in STATUS.md.
- Record every decision in STATUS.md (Decisions table) when it's made.
- Commit or push only when asked.

## Engineering rules (designs and code)
These rules are the owner's. Each one points to where its detail lives. **Keep the list growing:** when the work turns up a best practice or a lesson that is critical (security, tenant isolation, data integrity, client compatibility, or a gotcha that cost real time), add it here in the same change, as one line with its source, and log it in the STATUS Decisions table. Leave out matters of taste.

- **Endpoints are centralised.** Every path is declared once, in `core/http/Endpoints.kt`. Route files use those constants, never path literals. The same change adds the endpoint to `docs/api/openapi.yaml`, and test IT-CORE-10 fails if Ktor's routes, the registry and the contract differ (owner, 2026-10-15; core-and-auth §16).
- **Contract first.** Change `docs/api/openapi.yaml` before the code. Within `/v1` only additive changes are allowed (NFR-COMPAT-01). Lint it with `npx --yes @redocly/cli@1.34.5 lint docs/api/openapi.yaml`, run from the scratchpad, and expect it to pass.
- **The path prefix decides authentication:** `/v1/auth`, `/v1/guest`, `/v1/staff`, `/v1/admin`; `/internal/jobs` needs the cron secret (core-and-auth §2).
- **Tenant isolation.** Every repository method takes a `RestaurantId` and filters by it. Tenant tables have forced RLS. Use `Transactor.inTransaction(restaurantId)`; use `platformTransaction` only for admin work and global tables. Another restaurant's or session's ID → 404 (ADR-0005; core-and-auth §6–7).
- **Who the caller is comes from the token plus the database,** never from the request body. Role checks live in services, not routes (core-and-auth §6).
- **Errors** are a `DomainError` → problem+json with a stable `code`. Every new code goes into `x-error-catalogue` in the contract. Never send stack traces or internals to clients (core-and-auth §3).
- **Retries and concurrent edits.** Operations listed in architecture §10 require `Idempotency-Key`, stored in the same transaction as the change. Versioned resources require `If-Match`: missing → 428, stale → 412 (core-and-auth §4–5).
- **Audit and outbox rows are written in the same transaction as the change** they record (NFR-SEC-03, ADR-0003).
- **No personal data, secrets or tokens in logs:** no emails, names, phones, notes, IPs, passwords or tokens (architecture §11).
- **No custom cryptography.** Argon2id with Bouncy Castle, ES256 JWTs, AES-256-GCM, secure random tokens stored as SHA-256 hashes (core-and-auth §8).
- **Wire formats:** money is a string of whole đồng (`BIGINT` in the database); times are UTC RFC 3339; IDs are UUID v7; JSON fields are `snake_case`.
- **One-time secrets** (invitation and reset links, tokens) are shown once. They are never stored in an idempotent response or written to a log; a replay issues a fresh one, which replaces the old one (core-and-auth §4).
- **Every endpoint registry entry sets `allowedWhenSuspended` explicitly;** there's no default, and the contract mirrors it as `x-allowed-when-suspended` (restaurants §6).
- **A rule that spans several rows** (e.g. "at least one active manager") is serialised with `pg_advisory_xact_lock` on its scope. Otherwise two concurrent changes can each pass the check and break it together (restaurants §7).
- **YAML gotcha:** in OpenAPI files, quote any plain value that contains `: ` or, inside `{ }`, a `,`. Otherwise it parses into the wrong structure. Scripts that generate contract text quote every `summary` and `description`. It bit twice on 2026-10-15.

## Spikes: conventions and known gotchas
- Spikes are throwaway code under `spikes/`. Results go into the spike README's Results table, then a one-line summary into the STATUS open-items row.
- Secrets live in `spikes/<id>/.env.spike` (ignored by git through `.env*`). Run scripts as `node --env-file=.env.spike …`. Never print, read back or commit a secret.
- Vercel account `liam-36c7` is a **free Hobby test account**. Its limits (300 s max duration, production scales to zero) are not production findings.
- Vercel accepts only `Dockerfile.vercel` or `Containerfile.vercel` as a container entrypoint name.
- The first deploy of a Vercel project goes to production, and its production alias is public. Remove it (`vercel alias rm`) for spikes.
- Spike projects keep their environment variables on Production only, so a preview deploy starts without them and crashes. Redeploy spikes with `vercel deploy --prod`, then remove the alias.
- The client network drops idle TCP connections after ~40 s. Long-held requests need TCP keepalive at 10 s or less (see `spikes/s1-vercel/scripts/measure.mjs`).
- Vercel's CDN never caches a request that carries an `Authorization` header (or a response with `set-cookie`, `private`, `no-cache` or `no-store`). Don't plan edge caching for anything a token protects (CR-004, from Vercel docs read 2026-10-15).
- A Vercel Function's request or response body is at most 4.5 MB; larger gets Vercel's own `413 FUNCTION_PAYLOAD_TOO_LARGE` (CR-004).
- The auto-mode classifier blocks production deploys, reading secrets from APIs, and writing secrets to a secret store (`vercel env add`, even piped from `.env.spike`). Ask the owner to do those steps.

## Saving context (automated in `.claude/settings.json`)
- `SessionStart` hook (`.claude/hooks/resume_brief.py`): every session, `/clear` and compaction starts with a brief built from STATUS.md. Keep STATUS.md current, because the brief is only as good as it is.
- `PreToolUse` hook on Read (`.claude/hooks/section_reads.py`): whole-file reads of Markdown over 150 lines are refused with the file's outline; read the section with `offset` and `limit`.
- `bashOutputMaxChars: 10000`: longer command output goes to a file with a preview.
- Still on you: run long measurements in the background and read only their summaries; filter logs before reading them.
