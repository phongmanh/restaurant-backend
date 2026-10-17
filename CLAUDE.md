# restaurant-backend

Multi-restaurant ordering backend (Kotlin, Ktor 3, JDK 25, PostgreSQL), run as a phased SDLC with gates G1–G6. The owner is product owner and tech lead in one person, and new to backend work: explain backend concepts plainly.

## Resume here
1. Read `docs/sdlc/restaurant-ordering/STATUS.md` first. It is the single source of truth for the phase, gates, decisions, risks, open items and spikes.
2. Don't read whole documents. Find what you need with `grep -n '^## '` and read only that section with `sed -n`. Architecture sections: §8 deployment, §9 capacity, §17 spikes (exit criteria).
3. Each spike has a README in `spikes/<id>/` with its questions, how to run it, exit criteria and results. Continue from there.
4. Phase 4 code lives at the repository root, one Gradle module under `io.github.phongmanh.restaurant`. The build order (9 steps, one PR each) and its progress are in the STATUS Decisions table. `./gradlew build` runs ktlint, detekt, the tests and the Kover gate, as CI does; Gradle downloads JDK 25 if it's missing. From step 2 the integration tests need Docker running (Testcontainers).

## Document map (`docs/sdlc/restaurant-ordering/`)
| File | Holds |
|---|---|
| requirements.md | v1.6 (baselined at G1, changed by CR-002 to CR-007). Changing it needs a CR |
| architecture.md | v1.4: approved at G2 (2026-10-15) with the TBD-10 condition, then changed by CR-004 to CR-006 and CR-008. Changing it needs a CR |
| adr/0001–0009, 0011 | Decisions; ADR-0002 (Vercel) is Accepted with conditions (paid-plan cold-start check before G5); 0003 (live updates through Ably) is Accepted after S-3; 0004 (Neon PostgreSQL) and 0008 (rate limiting: WAF + Upstash) are Accepted; 0010 is reserved by CR-001; 0011 (built-in staff sign-in) is Accepted too |
| cr/ | Change requests. CR-001 (move off Vercel) is Deferred; CR-002 (built-in staff accounts), CR-003, CR-004 (menu read and image limits), CR-005 (natural idempotency, no guest device data), CR-006 (orders decisions: closed-bill corrections, no FR-016 in release 1), CR-007 (no CSV export in release 1), CR-008 (architecture aligned at the G3 pass) and CR-009 (designs aligned with the Phase 4 build, Q-10 to Q-15) are Approved |
| design/ | Feature designs, approved at G3 (2026-10-15) at v1.0: core-and-auth, restaurants, menu, tables, orders, realtime, reports. All but core-and-auth are v1.1 since CR-009 (2026-10-17). Changing one needs a CR |
| `docs/api/openapi.yaml` (repo root) | The API contract, 1.0.0, frozen at G3 (2026-10-15). Changing it needs a CR, and within `/v1` only additively |
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
- **Tenant isolation.** Every repository method takes a `RestaurantId` and filters by it. Tenant tables have forced RLS. Use `Transactor.inTransaction(restaurantId)`, for an admin's work on one restaurant too (step 4); use `platformTransaction` only for admin work and writes that span restaurants, and `globalTransaction` (the `app` role, no tenant) for hot-path work on global tables only, such as the caller lookup on every call, so it doesn't queue for the small platform pool. On a global table (`staff_accounts`, `restaurants`) a query a restaurant's staff can trigger filters by their restaurant (IT-CORE-07). Another restaurant's or session's ID → 404 (ADR-0005; core-and-auth §6–7; step 3).
- **Who the caller is comes from the token plus the database,** never from the request body. Role checks live in services, not routes (core-and-auth §6).
- **The role check comes before reading the input:** 401 → 403 → 400 → 404 → 409/412 (core-and-auth §3). A manager-only service operation takes a `RestaurantScope`, which only the role check makes, and the route makes it before it reads the body, `If-Match` or the query (step 4).
- **Declare routes with `endpoint()`**: for `/v1/staff`, `/v1/admin` and `/v1/guest` it authenticates by the path's group before the handler runs, and the handler reads `call.caller()`. A route declared another way gets no authentication (core-and-auth §2, §6; step 3). The one exception is a registry entry with `qrCredential` (the scan, `x-auth: qr`), whose QR token in the body is the credential; IT-CORE-10 checks the flag against the contract (tables §8; step 6).
- **Declare scheduled jobs with `job()`** (`core/http/Jobs.kt`): it checks `CRON_SECRET` in constant time before the handler runs. A job route declared another way is open to anyone. Each job holds `pg_try_advisory_xact_lock` on its scope so two runs don't overlap (core-and-auth §2, §13; step 6).
- **Retention work is a `RetentionTask`** bound in the feature's module (`bind RetentionTask::class`). The one `/internal/jobs/retention` route runs them all, and each holds `pg_try_advisory_xact_lock` per restaurant (core-and-auth §10; step 7).
- **Errors** are a `DomainError` → problem+json with a stable `code`. Every new code goes into `x-error-catalogue` in the contract. Never send stack traces or internals to clients (core-and-auth §3).
- **Retries and concurrent edits.** Operations listed in architecture §10 require `Idempotency-Key`, stored in the same transaction as the change. Versioned resources require `If-Match`: missing → 428, stale → 412 (core-and-auth §4–5).
- **Idempotent by design beats a stored key.** When a database constraint already makes a retry harmless (one open session per table, merged waiter calls), the operation takes no `Idempotency-Key` (CR-005).
- **Audit and outbox rows are written in the same transaction as the change** they record (NFR-SEC-03, ADR-0003).
- **A remote call runs in `tx.afterCommit`, never inside the transaction** (architecture §9). `EventLog.append` registers the Ably publish there, so a rolled-back change sends nothing and a slow Ably holds no row lock. An after-commit action that throws is logged and skipped: the change is saved and the request still succeeds; the outbox covers what wasn't sent (realtime §4.2; step 8).
- **No personal data, secrets or tokens in logs:** no emails, names, phones, notes, IPs, passwords or tokens (architecture §11).
- **No custom cryptography.** Argon2id with Bouncy Castle, ES256 JWTs, AES-256-GCM, secure random tokens stored as SHA-256 hashes (core-and-auth §8).
- **Sign-in failures look and cost the same.** Every failure is one `401 invalid_credentials` body and runs Argon2id exactly once (a dummy hash when there's no usable account). The timing is only even while hashes share parameters, so stored hashes are re-hashed at sign-in when the parameters change. Only a finished sign-in clears the lock counter: an admin's correct password alone doesn't (core-and-auth §8.1; IT-AUTH-02; step 3).
- **Wire formats:** money is a string of whole đồng (`BIGINT` in the database); times are UTC RFC 3339; IDs are UUID v7; JSON fields are `snake_case`.
- **Secrets are never stored in an idempotent response or written to a log.** That covers one-time secrets (invitation and reset links) and bearer tokens (session and access tokens). A replay issues a fresh one; for links, the fresh one replaces the old (core-and-auth §4, tables §3.1). `JsonResponse.storedBody` is the copy kept without the secret, and `IdempotentRoutes.respond(reissue = …)` makes the replay's fresh one (step 4).
- **Ending someone's access ends it everywhere, in the same transaction.** A role change revokes their sessions; a deactivation, a TOTP reset or an erasure revokes their sessions and voids every open link. A stale reset link would otherwise work again once the account is active (restaurants §7, §8, §10; step 4).
- **Every endpoint registry entry sets `allowedWhenSuspended` explicitly;** there's no default, and the contract mirrors it as `x-allowed-when-suspended` (restaurants §6).
- **A rule that spans several rows** (e.g. "at least one active manager") is serialised with `pg_advisory_xact_lock` on its scope. Otherwise two concurrent changes can each pass the check and break it together (restaurants §7).
- **A feature's writes that check counts or names take turns on one lock row, taken first.** Every menu write locks the restaurant's `menu_state` row before anything else, so two creates can't both pass the last free slot; the concurrency test fails without it (menu §3; step 5).
- **One lock order for every write:** dining table → table session → bill → order rows → daily sales rows → event counter → audit chain head. A transaction that skips a step keeps the order of the rest; two writes that lock in opposite orders can deadlock under load (orders §3.1, reports §4; the dining table first, step 6). `AuditLog.record` keeps the audit head last by itself: it appends in a before-commit hook, so call it anywhere in the transaction (step 2).
- **`SessionAccess.close` writes an event (`TableSessionClosed`), so it takes the event counter.** Call it after the order rows and the sales ledger, never before them: the orders close writes the ledger first (Q-13; step 7).
- **Lock a row you'll update with `FOR NO KEY UPDATE` (or `FOR UPDATE`), never `FOR SHARE`.** Two transactions that each take a share lock and then update the row wait for each other: a deadlock. `SessionAccess.requireOpenForUpdate` takes `FOR NO KEY UPDATE`, which still makes a close (`FOR UPDATE`) wait (Q-12; `SessionAccessTest`; step 6).
- **Lock with a query on the row's own table, then read it with its joins.** After a locking query waits, PostgreSQL re-checks the row against the whole query, so if the other transaction changed a join column the row drops out: a `404` where the loser of two concurrent moves should get `412` (`SessionRepository.lock`; IT-TBL-08; step 6).
- **"Create unless it exists" is `INSERT … ON CONFLICT … DO NOTHING`, then a read.** Any error aborts a PostgreSQL transaction, so a unique violation can't be caught and carried on from. For a partial unique index, name its predicate: `ON CONFLICT (table_id) WHERE status = 'open'` (open-or-join, waiter calls; tables §3.1, §4; step 6).
- **A transaction runs once.** Exposed retries a failed transaction 3 times by default, which would redo an operation's work; `DatabasePools` sets `defaultMaxAttempts = 1`. A retry comes from the client, with its `Idempotency-Key` (step 2, 2026-10-15).
- **Bind times as `timestamptz`.** pgjdbc sends a `java.sql.Timestamp` parameter untyped, and inside a `CASE` PostgreSQL reads it as text and the statement fails. `Jdbc.bind` sends an `Instant` as an `OffsetDateTime`; take `now` from the injected `Clock`, not the database's `now()`, so tests can move time (step 3).
- **Cast nullable SQL parameters** (`?::text`, `?::uuid`, `?::boolean`). `Jdbc.bind` sends `null` untyped, and inside `nullif`, `CASE` or `IS DISTINCT FROM` PostgreSQL can't infer its type (step 5).
- **A migration that fills a new tenant table does it before `FORCE ROW LEVEL SECURITY`**, which holds the table's owner (the migrator) to the policy too, so the insert would be refused (V5; step 5).
- **SQL errors keep row values out of logs.** pgjdbc's `Detail:` line ("Key (email)=(…) already exists") and Exposed's `ExposedSQLException.toString()` both print values. So `logServerErrorDetail` is `"false"`, the `Exposed` logger is at ERROR, and `ExposedTransactor.translate` drops Exposed's wrapper. Hikari driver properties must be **strings**: `java.util.Properties` ignores anything else, which had silently dropped both `logServerErrorDetail` and `prepareThreshold`. PostgreSQL itself hides the values under RLS, so test SQL-error privacy with a role RLS doesn't restrict, or the test passes for the wrong reason (`LogPrivacyTest`; step 2, 2026-10-15).
- **Clients accept enum values they don't know** (shown as a neutral state), and the contract says so on the field. That keeps a new value, such as FR-016's `pending_confirmation`, additive within `/v1` (NFR-COMPAT-01; orders §2).
- **YAML gotcha:** in OpenAPI files, quote any plain value that contains `: ` or, inside `{ }`, a `,`. Otherwise it parses into the wrong structure. Scripts that generate contract text quote every `summary` and `description`. In Python, write an apostrophe inside a double-quoted string: `'table''s'` is two adjacent literals and silently becomes `tables`. Lint passes either way, so read the parsed values back, and check every `x-error-catalogue` entry parses to exactly `code`, `status`, `meaning`. It bit three times on 2026-10-15, and the G3 pass found five catalogue entries that had been split at their commas since contract 0.1.0.

## Spikes: conventions and known gotchas
- Spikes are throwaway code under `spikes/`. Results go into the spike README's Results table, then a one-line summary into the STATUS open-items row.
- Secrets live in `spikes/<id>/.env.spike` (ignored by git through `.env*`). Run scripts as `node --env-file=.env.spike …`. Never print, read back or commit a secret.
- Vercel account `liam-36c7` is a **free Hobby test account**. Its limits (300 s max duration, production scales to zero) are not production findings.
- Vercel accepts only `Dockerfile.vercel` or `Containerfile.vercel` as a container entrypoint name.
- The first deploy of a Vercel project goes to production, and its production alias is public. Remove it (`vercel alias rm`) for spikes.
- Spike projects keep their environment variables on Production only, so a preview deploy starts without them and crashes. Redeploy spikes with `vercel deploy --prod`, then remove the alias.
- A Vercel project linked from inside this repo can get connected to the GitHub repo, and then every push and PR builds it, and a merge to `main` deploys it to production with a public alias. Run `vercel git disconnect --yes` in each spike folder right after linking; spikes deploy from the CLI only. Found when PR #1's checks showed failed builds of `restaurant-s1-spike` and `restaurant-s4-spike`, disconnected before the merge (2026-10-15).
- The client network drops idle TCP connections after ~40 s. Long-held requests need TCP keepalive at 10 s or less (see `spikes/s1-vercel/scripts/measure.mjs`).
- Vercel's CDN never caches a request that carries an `Authorization` header (or a response with `set-cookie`, `private`, `no-cache` or `no-store`). Don't plan edge caching for anything a token protects (CR-004, from Vercel docs read 2026-10-15).
- A Vercel Function's request or response body is at most 4.5 MB; larger gets Vercel's own `413 FUNCTION_PAYLOAD_TOO_LARGE` (CR-004).
- Vercel Hobby runs a cron job at most **once a day** (±59 min); a more frequent schedule fails the deployment. Set schedules per environment; on Hobby, jobs that should run every minute or hour run daily (Vercel docs read 2026-10-15; realtime §10).
- The auto-mode classifier blocks production deploys, reading secrets from APIs, and writing secrets to a secret store (`vercel env add`, even piped from `.env.spike`). Ask the owner to do those steps.

## Build: known gotchas
- detekt 1.23.8 crashes (the error is just the JDK version, e.g. `> 25.0.4.1`) when Gradle itself runs on JDK 25, because its embedded Kotlin compiler can't parse the version. It passes on a machine whose Gradle runs on JDK 21, so it fails only in CI. `build.gradle.kts` therefore runs detekt-cli as a `JavaExec` task on a JDK 21 toolchain; don't switch back to the detekt Gradle plugin until detekt 2 is stable. Reproduce with `JAVA_HOME=<jdk 25> ./gradlew detekt --rerun` (PR #3, 2026-10-15).

- A `/*` inside a KDoc or comment (e.g. a path like `/v1/auth/*` in backticks) opens a nested comment in Kotlin, and the compiler reports "Unclosed comment" at the end of the file. Write the path without the `*` (step 3).
- Tests never reach Ably: `testApp` binds a `FakeAbly` (down, rate-limited, publish hook). A test that inspects it passes its own in `overrides`. The real adapter is checked by `AblyLiveTest`, which runs only when `ABLY_API_KEY` is set in the shell (step 8).
- macOS has no `timeout` command: `timeout 300 ./gradlew test` fails at once and prints nothing, which reads like a passing run. Run long Gradle tasks with the Bash tool's background mode and read `build/test-results` (step 3).

## Saving context (automated in `.claude/settings.json`)
- `SessionStart` hook (`.claude/hooks/resume_brief.py`): every session, `/clear` and compaction starts with a brief built from STATUS.md. Keep STATUS.md current, because the brief is only as good as it is.
- `PreToolUse` hook on Read (`.claude/hooks/section_reads.py`): whole-file reads of Markdown over 150 lines are refused with the file's outline; read the section with `offset` and `limit`.
- `bashOutputMaxChars: 10000`: longer command output goes to a file with a preview.
- Still on you: run long measurements in the background and read only their summaries; filter logs before reading them.
