# ADR-0011: Staff and admin sign-in: built-in accounts, no external identity provider (TBD-1)

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Accepted (CR-002 approved 2026-10-14) | 2026-10-14 | Manh Nguyen (product owner, tech lead) | FR-010, FR-040, FR-041, NFR-SEC-03, NFR-SEC-04, NFR-PRIV-02 |

ADR-0010 is reserved by CR-001 (container platform), so this record is 0011.

## Context
Requirements v1.0 assumes staff and platform admins sign in through an external OIDC identity provider (IdP). The API never sees passwords: it checks the signature of the IdP's access token against the IdP's public keys (JWKS) and reads restaurant and role from our own `staff_members` table (architecture §7). The IdP must give us:

- OIDC Authorization Code + PKCE for the staff web app and, later, mobile apps (TBD-2).
- JWT access tokens with asymmetric signatures (RS256/ES256), `iss`, an audience check and `exp`, and a lifetime ≤ 15 min (ASSUMPTION-5).
- **Proof of MFA for platform admins** (requirements line 49, NFR-SEC-04). MFA means a second factor, such as a code from an authenticator app, on top of the password.
- An admin API that creates a user and sends an invitation email (FR-040 first manager, FR-041 staff).
- A separate dev/staging tenant (requirements §5 dependencies).
- A data region that fits TBD-10 (staff name, email and phone are personal data under the Vietnam PDP Law and Decree 13).
- **A free plan that covers MFA, invitations and a second tenant** (decision 2026-10-14: learning project, free plans only). The paid-plan prices below are for reference only.

**Size (new ASSUMPTION-10):** about 10 staff accounts per restaurant → **≈ 5,000 monthly active users (MAU) at 500 restaurants, ≈ 15,000 at 3× growth** (ASSUMPTION-1). Platform admins: fewer than 10.

The backend runs on Vercel `sin1` (Singapore) with Neon Singapore (ADR-0002, ADR-0004). The IdP is called only at sign-in, token refresh and invitations; the API caches the JWKS, so IdP latency doesn't touch the order path.

## Options
Prices and features were read from the vendors' pages on 2026-10-14. Items marked *verify* are not confirmed by vendor docs and go into S-4.

| | 1. Amazon Cognito (Essentials) | 2. Microsoft Entra External ID | 3. Auth0 (Okta) | 4. ZITADEL Cloud (Pro) | 5. Keycloak, self-hosted |
|---|---|---|---|---|---|
| Cost at 5k / 15k MAU | **$0 / ≈ $75 a month** (10k MAU free per AWS account, then $0.015) | **$0 / $0** (first 50k MAU free); SMS MFA billed per message if used | B2C Essentials from $35 a month for 500 MAU; the price at 5k–15k isn't on the page (quote needed). The free plan (25k MAU) has **no MFA** | $100 / $100 a month (25k daily active users included) | No licence; we pay for and run a server and its database |
| Data region nearest Vietnam | **Singapore** (`ap-southeast-1`), same as our API and database | Data location follows the tenant's country; "Go-Local" pins it to Australia or Japan only. Where a Vietnam-registered tenant's data sits: *verify* | Japan or Australia (public cloud regions: AU, CA, EU, JP, UK, US) | Australia (regions: CH, EU, US, AU) | Anywhere we host it |
| Admin MFA proof in the access token | MFA is set per user pool (off / optional / required). User-pool access tokens carry no `amr` claim (*verify*), so we'd use a **separate admin pool with MFA required** and accept admin routes only from that pool's issuer | *verify*: whether external-tenant access tokens carry `amr`; Conditional Access requiring MFA on a separate admin app registration is the likely route | `amr` is in the ID token only; a post-login Action can copy it into a namespaced access-token claim (community answer, *verify*) | *verify* (Actions can add claims) | Built in (step-up, `acr`) |
| Invitation flow | `AdminCreateUser` emails a temporary password; text customisable | Graph API user creation; invite email *verify* | Management API + password-change ticket, or Organizations invitations | Management API sends an initialisation email | Admin API + "execute actions" email |
| Audience check | Access tokens carry `client_id`, not `aud` (*verify*); the API checks `client_id` instead | `aud` | `aud` | `aud` | `aud` |
| Separate dev/staging tenant | A second user pool (same account; shares the free 10k MAU) | A second external tenant | 3 tenants on Essentials | One instance on Pro; a second instance costs extra (*verify*) | Another realm |
| Effort for us | Low to medium (AWS account, IAM, quotas; plain hosted login page) | Medium (Azure tenant, app registrations, Graph) | Low (best docs and SDKs) | Low to medium | **High**: we'd run, patch, back up and scale it ourselves |

**Option 6: built-in accounts in the service.** Email and password (Argon2id hash) in our database; the service issues its own ES256 access tokens and rotating refresh tokens; admins add TOTP from an authenticator app; invitations and resets are one-time links that the inviter passes on.
- Pros: $0 and no new vendor or cloud account; staff data stays in our database (the same region as everything else, TBD-10); admin MFA proof is a claim we set ourselves; teaches how sign-in works.
- Cons: we own credential security (hashing, brute-force limits, token rotation); no email sender, so links are passed on by hand; Argon2id costs memory and CPU on each sign-in; no single sign-on until an IdP is added later.

## Trade-offs
- **Region.** Only Cognito keeps staff data in Singapore, next to everything else. None of the five lists a Vietnam region, so every option is a cross-border transfer of staff personal data, which the TBD-10 legal review must cover anyway. If the review demands storage in Vietnam, all five managed options fail equally and this ADR reopens.
- **Cost.** Entra is free at our size; Cognito is free now and ≈ $75 a month at 3× growth; ZITADEL is a flat $100 a month; Auth0 is the most expensive once MFA is needed, and its price at our size needs a quote.
- **Admin MFA.** No managed option documents an `amr` claim in its access tokens out of the box. The provider-neutral design is a **separate admin application (Cognito: a separate pool) where the IdP always requires MFA**, with admin routes accepting only that issuer or client. The proof then rests on IdP configuration, not a token claim, so S-4 must test that a non-MFA sign-in to the admin app is impossible. Architecture §7 ("an MFA claim in the token") would change to match on acceptance.
- **Self-hosting** (option 5) contradicts the serverless choice in ADR-0002 and adds an always-on service to run. Rejected.

## Decision
**Option 6, built-in accounts**, chosen by the owner on 2026-10-14: no dependency on an outside identity provider for now. The external IdP that requirements v1.0 assumes is replaced through **CR-002**, which holds the full design (sign-in, tokens, admin TOTP, invitations, resets, limits). CR-002 was approved on 2026-10-14.

Before that choice, the recommendation among external providers was option 1 (Cognito, data in Singapore), with option 2 (Entra External ID) as runner-up. Both fit the free plan. The comparison above stays as the record in case an external IdP is added later, for example for single sign-on.

## Consequences
- Positive: no outside service in the sign-in path; $0; staff personal data stays in the service database; one token-signing mechanism for guests and staff (separate keys and audiences).
- Negative: credential storage, brute-force protection and session handling are ours to get right (ASVS L2 V6/V7); invitation and reset links are passed on by hand until an email sender is added.
- Follow-ups:
  - S-4 redefined: Argon2id time and memory on a Vercel function in `sin1`, plus the token cases (valid, expired, forged, wrong audience, admin without TOTP). Exit criterion: sign-in p95 ≤ 500 ms warm, and every case behaves as specified.
  - Architecture §2, §5, §7, §10, §12 and §17 updated as listed in CR-002.
  - ADR-0008 adds sign-in, refresh and invitation limits.

## Sources (read 2026-10-14)
- Cognito pricing and tiers: https://aws.amazon.com/cognito/pricing/
- Entra External ID pricing and Go-Local: https://www.microsoft.com/en-sg/security/pricing/microsoft-entra-external-id
- Auth0 pricing: https://auth0.com/pricing · regions: https://auth0.com/docs/troubleshoot/customer-support/operational-policies/public-cloud-service-endpoints · `amr` in ID tokens: https://auth0.com/docs/secure/multi-factor-authentication/step-up-authentication/configure-step-up-authentication-for-web-apps
- ZITADEL pricing: https://zitadel.com/pricing/detail · regions: https://help.zitadel.com/where-is-zitadel-cloud-data-stored
