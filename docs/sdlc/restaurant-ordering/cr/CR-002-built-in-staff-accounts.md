# CR-002: Staff sign in with accounts built into the service, not an external identity provider

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-002 | Approved | 2026-10-14 | Manh Nguyen (product owner), from ADR-0011 | Manh Nguyen (product owner) | requirements.md v1.0 (G1) |

## Summary
Staff and platform admins sign in with an email and password stored in our own database. Admins add a second factor: a 6-digit code from an authenticator app (TOTP). The service issues its own short-lived access tokens. This replaces the external OIDC identity provider (TBD-1) that requirements v1.0 assumes. ADR-0011 records the design.

## Why
- The owner doesn't want a dependency on an outside identity provider for now (decision 2026-10-14).
- It's a learning project on free plans (decision 2026-10-14). Built-in accounts cost nothing, add no vendor, and teach how sign-in works from the inside.
- The two managed options that fit the free plan (Cognito, Entra External ID) each need a new cloud account and still had unverified points: how tokens prove MFA, and where data is stored (ADR-0011).

## Proposed change
1. **Accounts.** A staff account is an email (unique, case-insensitive) plus a password hash, stored in the service database. Passwords are hashed with **Argon2id** (OWASP parameters: 19 MiB memory, 2 iterations). The password itself is never stored or logged.
2. **Sign-in.** `POST /auth/sign-in` with email and password. Success returns:
   - an **access token**: ES256 JWT, lifetime 15 min (ASSUMPTION-5), the same signing set-up as the guest tokens (ADR-0006) with its own key and audience;
   - a **refresh token**: random, stored hashed in the database, rotated on every use, lifetime 12 h (one shift). In the staff web app it lives in an `HttpOnly; Secure; SameSite=Strict` cookie; the access token lives in memory only.
3. **Admin MFA.** Platform admins must enrol TOTP and enter a code at every sign-in. The access token carries `amr: ["pwd","otp"]`, which the service set itself, so admin routes can trust it.
4. **Invitations (FR-040, FR-041).** An invitation is still addressed to an email and carries a role. The service returns a **one-time invitation link** (random token, stored hashed, valid 72 h). The inviting admin or manager passes it on themselves; there's **no email sender in release 1**. Opening the link lets the person set their password.
5. **Forgotten password.** A manager issues a reset link for their own staff; a platform admin issues one for a manager; another platform admin, or a one-off command, for an admin. No self-service reset by email in release 1.
6. **Protection (ASVS L2, chapter V6).**
   - Passwords of 8 to 64 characters, all characters allowed, no composition rules, checked against a bundled list of common passwords (no outside call).
   - Sign-in is rate-limited per account and per IP through the existing Redis limits (ADR-0008).
   - The same generic error for an unknown email and a wrong password.
7. **Revocation.** Deactivating an account, or changing its password or role, deletes its refresh tokens. The access token still expires within 15 min. Restaurant and role are still read from `staff_members` on every call (architecture §7), so a change takes effect on the next call.

## Impact
| Item | Change | When |
|---|---|---|
| requirements.md | v1.0 → v1.1, with a changelog entry citing CR-002:<br>- consumers table: staff app "access token issued by the service"; admin console "MFA (TOTP) required";<br>- FR-010: "when they sign in with their email and password"; drop "wrong-issuer" and the IdP-down criterion; add "wrong password or unknown email → the same 401";<br>- FR-040/FR-041: "invite by email" → "invite an email address; the inviter receives a one-time link";<br>- data table: staff account holds a password hash and, for admins, an encrypted TOTP secret; storage is the service database only; no processor;<br>- integrations table: remove the identity-provider row;<br>- TBD-1 resolved | On approval |
| ADR-0011 | Accepted with the design above | On approval |
| architecture.md | §2 context diagram without the IdP; §5 route groups; §7 rewritten; §10 dependency table without JWKS; §12 threat model rows for credential stuffing, brute force, invitation-link leakage and session theft; §17 S-4 redefined | Phase 2, before G2 |
| ADR-0006 | Note that staff tokens share the signing mechanism but use their own key and audience | With ADR-0011 |
| ADR-0008 | Add sign-in, refresh and invitation-accept limits | Before G2 |
| test-plan.md | Test data: staff accounts created by a seed script instead of an IdP tenant; tests for lockout, refresh-token rotation and reuse, TOTP, invitation expiry | Before G2 |
| rtm.md | FR-010, FR-040, FR-041, NFR-SEC-04 design column → ADR-0011 | On approval |
| STATUS.md | TBD-1 resolved; S-4 redefined (below); R-xx for owning credential storage | On approval |
| Code | None yet (Phase 4 hasn't started) | — |

**S-4, redefined:** measure Argon2id hashing at the chosen parameters on a Vercel function in `sin1` (time and memory, cold and warm), and run the token checks (valid, expired, forged, wrong audience, admin without TOTP). Exit criterion: sign-in p95 ≤ 500 ms warm, and every token case behaves as specified.

## What stays the same
- Every NFR target. NFR-SEC-04 still requires MFA for the admin console.
- Guest authentication (ADR-0006), tenant isolation through RLS (ADR-0005), and roles read from the database.
- An external identity provider can be added later (for example, single sign-on for restaurant chains). `staff_members` keeps a nullable `idp_subject` column for that.

## Risks this adds
- **We own credential security.** A bug in hashing, rate limiting or token handling is ours to find. Mitigation: ASVS V6/V7 checklist in Phase 3 design, tests in the test plan, no custom cryptography (a vetted Argon2 library and a JWT library).
- **No email sender.** Invitations and resets depend on the inviter passing the link on safely. Adding an email service later is a small, separate change.
- **Argon2id on serverless.** Each sign-in costs about 19 MiB and some CPU on the function; S-4 measures it.

## Cost and schedule
- **Cost:** $0. No new service.
- **Schedule:** S-4 gets simpler (about half a day) and is no longer blocked by TBD-1. Phase 4 adds the sign-in, refresh, invitation, reset and TOTP endpoints (about 3–5 days), work an external IdP would have done for us.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-14 | Approved | Manh Nguyen (product owner) | Applied the same day: requirements v1.1, ADR-0011 Accepted, RTM, STATUS. Still to do before G2: architecture.md, ADR-0006/0008 notes, test-plan.md, then S-4 |
