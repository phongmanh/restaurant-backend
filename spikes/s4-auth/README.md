# S-4 spike: built-in staff sign-in on Vercel `sin1`

**Throwaway.** This code answers the S-4 questions for [ADR-0011](../../docs/sdlc/restaurant-ordering/adr/0011-staff-identity-provider.md) and [CR-002](../../docs/sdlc/restaurant-ordering/cr/CR-002-built-in-staff-accounts.md) (staff accounts built into the service; design in architecture §7), and is then deleted.

Status: **built and dry-run on the laptop, 2026-10-14; waiting for the owner's deploy** (owner steps below). 15 tests pass, and every `measure.mjs` mode has run against `./gradlew runLocal`: all 18 token cases pass. The laptop proves the code, not the numbers: Vercel gives an instance 1 CPU (S-1), the laptop 8.

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

### Vercel `sin1`
Not run yet: waiting for the owner steps.
