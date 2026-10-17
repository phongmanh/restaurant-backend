# Code security review before G4

| Field | Value |
|---|---|
| Date | 2026-10-17 |
| Asked by | The owner (G4 readiness §4 item 3) |
| Code reviewed | `main` at `f72bdf4` (H1–H4 merged), fixes on branch `phase4/security-review` |
| Aimed at | R-03 (a missed tenant check) and R-10 (a flaw in our own sign-in) |
| Result | **No exploitable flaw found.** 3 hardening fixes and 1 new test made; 2 items logged for later |

**In plain words:** a code review reads the code looking for ways an attacker could get in or see what they shouldn't. It's not the same as the Phase 5 checks (the ZAP scan probes the running service from outside; the ASVS checklist goes through a standard list). It found no hole, but four places where the code relied on a single defence where a second one is cheap. Those are now fixed.

## 1. What was checked
| Area | Checked | Finding |
|---|---|---|
| Authentication | `endpoint()` authenticates by path group before every handler; access, guest and QR tokens (ES256, key rotation, expiry, token kind) | Sound |
| Sign-in (R-10) | Argon2id, one `401 invalid_credentials` body and one hash per failure, lock counter, MFA (TOTP replay step, challenge expiry), refresh rotation and reuse detection, invitation and reset links (hashed, one-time, superseded), password change, cookies | Sound |
| Crypto | AES-256-GCM for TOTP secrets, secure random tokens stored as SHA-256 | Sound; no custom cryptography |
| Abuse | Rate limits (WAF + Upstash), the cron guard (`job()`, constant-time compare) | Sound; secret length now enforced (fix 2) |
| Tenant isolation (R-03) | Forced RLS on every tenant table; the restaurant behind every transaction; `RestaurantScope` role checks before input; another restaurant's or session's ID → 404 | Sound; global-table updates tightened (fix 3), RLS now guarded by a test (fix 4) |
| Retries | Idempotency scope, stored in the same transaction | Sound; one low item logged (L1) |
| Live updates | Ably token capabilities: a guest gets only their session's channel, staff only their restaurant's | Sound |
| Orders | Catch-up reads by session, prices taken from the menu on the server, never from the request | Sound |
| HTTP | Response headers | Two headers architecture §11 lists were missing (fix 1) |

## 2. Fixes
| # | Fix | Why | Test |
|---|---|---|---|
| 1 | Every response carries `Strict-Transport-Security: max-age=63072000; includeSubDomains` and `X-Content-Type-Options: nosniff` (`core/http/Http.kt`). A small plugin, not Ktor's `DefaultHeaders`, whose `Server` header would name the framework and its version | Architecture §11 lists both; the code didn't set them. HSTS tells a browser to use HTTPS only; `nosniff` stops it from reading a JSON reply as another file type | `HttpConventionsTest`: HSTS, nosniff and no `Server` header on success, error and health responses |
| 2 | The service refuses to start if `CRON_SECRET` is shorter than 32 characters (`core/config/AppConfig.kt`) | The secret is the only thing guarding the scheduled jobs; a short one could be guessed. `openssl rand -hex 16` makes a 32-character one | `AppConfigTest`: a short secret stops startup, and the message doesn't contain it |
| 3 | Staff `deactivate`, `reactivate` and `anonymise` match the account's restaurant as well as its ID (`StaffMemberRepository`) | `staff_accounts` is a global table without RLS. The services already found the member in its restaurant first, so this was safe; now the update itself can't reach another restaurant's account either (IT-CORE-07) | The existing staff-management tests, which run these updates |
| 4 | A test reads the migrated schema: every table with `restaurant_id` but `staff_accounts` has forced RLS and exactly one policy, `tenant_isolation`, comparing `restaurant_id` with the tenant setting; every other table is on a named list of global tables | Before, nothing caught a new migration that forgot RLS | `RowLevelSecurityTest` (IT-CORE-06) |

## 3. Logged, not fixed
| # | Item | Risk | Next step |
|---|---|---|---|
| L1 | The guest idempotency scope is `guest:<device id>`, and the guest's app picks the device ID at the QR scan (core-and-auth §4). Two guests who chose the same device ID share a scope | Low: both keys are random UUIDs, and a reused key with a different request is refused, not replayed | Binding the scope to the table session as well changes core-and-auth §4, so it needs a CR. Owner's call |
| L2 | The client IP is read from `x-real-ip` (`core/http/Http.kt`), which Vercel's edge sets. Anywhere else a client could set it, and the IP goes into audit records and rate-limit keys | None on Vercel | Check in Phase 5, and again if CR-001 moves the service off Vercel |

## 4. Not covered
- The running service from outside (ZAP scan) and the ASVS checklist: Phase 5, as planned.
- Dependency vulnerabilities: no scan was run in this review.
- Vercel, Neon, Ably and Upstash settings (who can sign in to them, their keys): outside the code.
