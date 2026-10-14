# S-4 spike: built-in staff sign-in on Vercel `sin1`

**Throwaway.** This code answers the S-4 questions for [ADR-0011](../../docs/sdlc/restaurant-ordering/adr/0011-staff-identity-provider.md) and [CR-002](../../docs/sdlc/restaurant-ordering/cr/CR-002-built-in-staff-accounts.md) (staff accounts built into the service; design in architecture §7), and is then deleted.

Status: **run on Vercel `sin1`, 2026-10-14. Both exit criteria are met:** warm sign-in p95 86 ms (≤ 500 ms), and 19 of 19 token cases behave as specified. 15 tests pass locally (Q4).

## Questions
| # | Question | Endpoint | `measure.mjs` mode |
|---|---|---|---|
| Q1 | How long does a sign-in take on a Vercel function in `sin1` with Argon2id at 19 MiB, t=2, p=1: warm, cold (first sign-in on a fresh instance), and 10 at once on 1 CPU? How much memory? | `POST /v1/auth/sign-in` (`spike` timing in the response), `GET /spike/info` | `signin`, `burst`, `cold`, `info` |
| Q2 | Which Argon2id library: Bouncy Castle or Password4j? Speed, and whether they read each other's hashes | `?lib=bc\|p4j` | `compare`; `PasswordsTest` |
| Q3 | Do the token checks hold on the deployed function: expired, forged, `alg: none`, `alg: HS256`, wrong issuer, guest key, wrong audience, admin without `otp`, MFA token as an access token, previous key accepted (rotation overlap)? Does the admin TOTP step work end to end? | `/v1/staff/me`, `/v1/admin/me`, `/v1/auth/mfa`, `GET /spike/test-tokens` | `tokens` |
| Q4 | Stateful rules: per-account lock after 10 failures, TOTP code used once, MFA token used once, refresh rotation and reuse revoking the family, sign-out, 12 h refresh expiry | same routes | Local tests only (`AuthFlowTest`): see Design |

## Exit criteria (architecture §17)
- Warm sign-in p95 ≤ 500 ms, measured inside the function.
- Every token case in Q3 behaves as specified.

## Design
- Ktor app packaged like S-1 and S-2 (`Dockerfile.vercel`, `PORT=8080`, PATH fix, `sin1`), as a **new** Vercel project `restaurant-s4-spike` on the free test account.
- **No database.** Accounts are in memory (`Seeds.kt`): a waiter, an inactive waiter, and a platform admin with TOTP. Their password and TOTP secret are public on purpose: these accounts guard nothing. The password hashes are constants, not computed at start-up, so a cold instance's first sign-in runs Argon2id before the JIT has warmed up, as it would with hashes read from the database.
- **Stateful rules are checked locally only (Q4).** Lock counters, used TOTP steps, used MFA tokens and refresh tokens are in memory per instance here. Vercel spreads requests over instances, so on Vercel these would look broken when they aren't. In the service they live in PostgreSQL (architecture §7), so they hold across instances; their logic is the same and runs in `AuthFlowTest`.
- **Keys.** ES256 (P-256): a current and a previous staff key (rotation with overlap) and a guest key (ADR-0006), which must never verify a staff token. Every instance needs the same keys, so they come from one environment variable, `SPIKE_KEYS`, made by `scripts/setup-env.mjs` (kept in `.env.spike`, ignored by git, never printed).
- **Libraries.** Ktor `auth-jwt` 3.6.0 (Auth0 `java-jwt`) for tokens; Bouncy Castle 1.86 and Password4j 1.8.4 for Argon2id; TOTP on the JDK's HMAC-SHA1, checked against the RFC 6238 test vectors (`TotpTest`).
- **Hash gate.** At most `HASH_PERMITS` (default 4) hashes run at once per instance; the rest wait. `waitMs` in the response shows the wait.
- Decisions made while building, to carry into Phase 3:
  - A wrong TOTP code uses up the MFA token, so the admin enters the password again. It's simple and limits guessing; if it annoys admins, allow 3 tries per MFA token.
  - Unknown emails are checked against `Seeds.DUMMY_PHC`, so they cost one hash like a known email.

## Owner steps (things Claude can't do)
Production deploys and adding secrets to Vercel are yours (CLAUDE.md). From `spikes/s4-auth`; the `node … |` pipe passes the keys from `.env.spike` to the CLI without showing them. `.env.spike` already holds `SPIKE_KEYS` (Claude ran `setup-env.mjs`).
```
vercel link                                   # new project "restaurant-s4-spike" in liam-36c7
for t in production preview; do
  echo 8080 | vercel env add PORT $t
  node --env-file=.env.spike -e 'process.stdout.write(process.env.SPIKE_KEYS)' | vercel env add SPIKE_KEYS $t
done
vercel deploy --prod                          # first deploy; ~3 min (image build)
vercel alias rm restaurant-s4-spike.vercel.app  # the production alias is public
```
Then:
1. Put this project's `VERCEL_AUTOMATION_BYPASS_SECRET` in `.env.spike` (`restaurant-s4-spike` → Settings → Deployment Protection → Protection Bypass for Automation; the secret is per project).
2. Give Claude the deployment URL (`https://restaurant-s4-spike-….vercel.app`).

## How to run
From `spikes/s4-auth`:
```
./gradlew test                                                      # 15 tests, no Docker needed
node scripts/setup-env.mjs                                          # once: SPIKE_KEYS into .env.spike
node --env-file=.env.spike -e 'require("child_process").execFileSync("./gradlew", ["-q", "runLocal"], {stdio: "inherit"})'
                                                                    # dry run on localhost:8080
node --env-file=.env.spike scripts/measure.mjs <mode> <baseUrl> [--option value]   # modes: see the script's header
```
Order of the Vercel run: `info` → `tokens` → `compare --n 60` → `signin --n 200 --c 1` → `burst --n 40 --c 10` → `info` (peak memory) → `cold --samples 3 --idle 360` (on the free account, production scales to zero, so an idle gap gives a fresh instance).

## Results
### Dry run on the laptop (2026-10-14; Apple M1 Pro, 8 CPUs, JDK 21): proves the code, not the numbers
| Question | Result |
|---|---|
| Q1 sign-in, one at a time | Bouncy Castle: total p50 20.9 · p95 25.7 ms, of which Argon2id p50 20.1 ms |
| Q1 burst, 10 at a time | Total p50 53 · p95 76 ms: hash ~26 ms, plus up to 49 ms waiting for one of the 4 hash slots |
| Q2 libraries | Bouncy Castle p95 25.7 ms; Password4j p95 41.7 ms (≈ 1.6× slower). Each reads the other's PHC strings (`PasswordsTest`) |
| Q3 token cases | **18 of 18 pass**, incl. the admin TOTP step end to end |
| Q4 stateful rules | `AuthFlowTest` passes: lock after 10 failures (also with the right password) and unlock after 15 min; TOTP code and MFA token single-use; refresh rotation, reuse revokes the family; sign-out; refresh expiry at 12 h |

### Vercel `sin1` (2026-10-14)
Deployment `restaurant-s4-spike-dcs63nl0u-liam-36c7.vercel.app` (`dpl_3sJvvNMNHqASoVZAJS8AqTpBiSTZ`), production target on the free test account; the public alias `restaurant-s4-spike.vercel.app` was removed. Instance: JDK 25.0.4.1, **1 CPU**, max heap 1,536 MB, container 2,048 MB, JVM start to ready 2.2–2.5 s. Client in Vietnam (round trip ≈ 40–45 ms, which doesn't count). Times are measured inside the function.

| Question | Result | Meets criterion? |
|---|---|---|
| Q1 warm sign-in, one at a time | Bouncy Castle, 200 sign-ins: **total p50 38 · p95 86 · p99 102 ms** (max 129); Argon2id is almost all of it (p50 36 · p95 84 ms). The spread between p50 and p95 is the shared vCPU, not the code | **Yes: p95 ≤ 500 ms** |
| Q1 burst, 10 at once | 40 sign-ins over 4 instances: total p50 311 · p95 713 · p99 801 ms. On 1 CPU the hashes running together share it, so each one stretches (Argon2id p50 219 ms) and the rest wait for a slot (up to 314 ms). Expected load is under 1 sign-in a second even at shift change, so this is a capacity note, not a failure | Not part of the criterion |
| Q1 cold, first sign-in on a fresh instance | 3 samples after 6 min idle, each on a new instance: **inside the function 419–601 ms** (Argon2id 206–255 ms before the JIT warms up, plus first-use class loading); **client wait 7.7–8.2 s**, of which the platform's cold start is the bulk, as S-1 found (CR-001) | Cold starts are the known S-1 issue on the free account |
| Q1 memory | Peak RSS per instance 214–516 MB of 2,048 MB; after 60 Password4j sign-ins 487 MB, after Bouncy Castle 374 MB. 4 hashes at once need ≈ 76 MiB for Argon2id itself | Yes |
| Q2 library | 60 sign-ins each, one at a time: **Bouncy Castle p50 36 · p95 85 ms; Password4j p50 80 · p95 130 ms** (≈ 2× slower, more memory). Each reads the other's PHC strings | **Bouncy Castle, chosen by the owner 2026-10-14** |
| Q3 token cases | **19 of 19 pass** (table below), including the admin TOTP step end to end | **Yes** |
| Q4 stateful rules | Local tests (`AuthFlowTest`). One showed up on Vercel by accident: two `tokens` runs 12 s apart on one instance, and the second run's TOTP code was refused as a replay; with the next 30 s step it passed | Yes (locally) |

**Vercel's edge firewall** refuses a bearer token with an empty signature (an `alg: none` token) with **403 and `x-vercel-mitigated: deny`**, before the function sees it. The same token with a junk signature reaches the app, which refuses it with 401. The app check is what counts; the edge is an extra layer (free plan, default rules), and clients should treat a 403 from the edge like a 401.

**Hash slots on 1 CPU.** `HASH_PERMITS=4` doesn't add throughput on 1 CPU: four hashes take as long together as one after another. It only bounds memory. Phase 3 can set the permits to the instance's CPU count; the latency under a burst stays about the same either way.

Token cases (2026-10-14T04:02:33Z):

| Case | Route | Expected | Got | Pass |
|---|---|---|---|---|
| valid staff token | `/v1/staff/me` | 200 | 200 | yes |
| no token | `/v1/staff/me` | 401 | 401 | yes |
| not a JWT | `/v1/staff/me` | 401 | 401 | yes |
| expired (15 min ago) | `/v1/staff/me` | 401 | 401 | yes |
| signed with the previous staff key (rotation overlap) | `/v1/staff/me` | 200 | 200 | yes |
| signed with the guest key | `/v1/staff/me` | 401 | 401 | yes |
| wrong issuer | `/v1/staff/me` | 401 | 401 | yes |
| MFA token used as an access token | `/v1/staff/me` | 401 | 401 | yes |
| payload changed, signature kept | `/v1/staff/me` | 401 | 401 | yes |
| `alg: none`, empty signature | `/v1/staff/me` | 401 | 403 at Vercel's edge | yes |
| `alg: none`, junk signature (reaches the app) | `/v1/staff/me` | 401 | 401 | yes |
| `alg: HS256` with a guessed secret | `/v1/staff/me` | 401 | 401 | yes |
| staff token on the admin route (wrong audience) | `/v1/admin/me` | 401 | 401 | yes |
| admin audience without `otp` in `amr` | `/v1/admin/me` | 403 | 403 | yes |
| admin sign-in returns `mfa_required` | `/v1/auth/sign-in` | 200 + mfa_token | 200 + mfa_token | yes |
| wrong TOTP code | `/v1/auth/mfa` | 401 | 401 | yes |
| right TOTP code | `/v1/auth/mfa` | 200 | 200 | yes |
| admin token with `otp` | `/v1/admin/me` | 200 | 200 | yes |
| admin token on the staff route (wrong audience) | `/v1/staff/me` | 401 | 401 | yes |
