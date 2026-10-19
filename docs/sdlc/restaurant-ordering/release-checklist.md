# Release checklist: release 1

| Field | Value |
|---|---|
| Version | 0.2 (Draft: Q-24 and Q-25 decided, P-1 to P-4 fixed) |
| Phase | 6 Deployment and release; ends at G6 Launch accepted |
| Release | Release 1, from `main` after G5 (`sdlc/restaurant-ordering/G5`), plus the Phase 6 fixes in §3 |
| Owner | Manh Nguyen: product owner, tech lead and operations owner |
| Last updated | 2026-10-18 |

## 1. What "launch" means for release 1
The owner decided on 2026-10-18: release 1 launches to a **free production environment**. That's a second, separate environment on free plans, holding **test data only**. It is released from the commit staging passed, following architecture §14's release steps: migrations, deploy, smoke test, a rollback rehearsal and a watch period. The aim is to run a real release procedure once, end to end.

It is not a launch with real customers. The gate log keeps what such a launch still needs (G5 conditions): the legal review (TBD-10, R-04), full-size load (R-16), the paid-plan checks (R-12), ST-07's live half and Q-18's items (R-21). The accepted risks R-09, R-12, R-14, R-16, R-19, R-20 and R-21 apply to this production as they did to staging. Production has the same limits as staging: one database compute that scales to zero (R-11), a 6-hour restore window (R-14), cron jobs once a day, and runtime logs kept for 1 hour (R-20).

## 2. What ships
| Item | Value |
|---|---|
| Code | `main` at the release commit (§3's fixes on top of the G5 baseline `9fce6f8`), tagged `release/1.0.1`; version `1.0.1`: `release/1.0.0` (Q-25) plus CR-015's key prefix, which production needs. `release/1.0.0` stays where it is, since a published tag isn't moved |
| Image | `Dockerfile.vercel`, built by Vercel from that commit (Vercel builds from source; CI's `image` job builds the same file but doesn't push it) |
| Contract | `docs/api/openapi.yaml` 1.1.1, 100 operations (the seven deprecated `PATCH` twins included) |
| Migrations | V1 to V11, all expand-only; production starts from an empty database |
| Settings | `APP_ENV=production`, `LIVE_PUSH=off` (CR-013), region `sin1`; the cron schedule in `vercel.json` |
| Waived | FR-022's two image routes (TBD-13, G4 waiver) |

## 3. Before the release: findings to fix or accept
Writing this checklist turned up four things staging never exercised. Each one is fixed in a Phase 6 PR, or the owner accepts it.

| ID | Finding | Why it matters | Fix (branch `phase6/release-prep`) |
|---|---|---|---|
| P-1 | No deployment has started with `APP_ENV=production` yet. That value turns on extra start-up checks: https app URLs, no `localhost` origin (`AppConfig`, core-and-auth §13) | The first production start runs checks no deployment has run. A miss shows up only as a crashed deploy | **Fixed:** `DeployedSettingsTest` resolves `application.yaml` from each committed `deploy/<env>/<env>.env` plus test secrets and runs the startup checks, so CI runs production's checks first. It also fails on a setting in an env file that nothing reads (a typo) |
| P-2 | `Dockerfile.vercel` uses floating base-image tags (`eclipse-temurin:25-jdk`, `25-jre`) | Vercel rebuilds the image for each project, so production could get a different JRE patch from the one staging tested. The "same build" promise then doesn't hold | **Fixed:** both images pinned by digest (`25-jdk@sha256:2d5d…`, `25-jre@sha256:dbb4…`, current on 2026-10-18); Dependabot's new weekly `docker` entry moves them to patched images. Staging is redeployed from the pinned build at release step 2 |
| P-3 | Every alert selector filters only `job="restaurant-backend"`. Staging and production would push to the one free Grafana stack under the same `job` | Staging's numbers would mix into production's alerts, or hide them. Example: staging's daily cron gauges look like production's jobs | **Fixed:** Grafana Cloud promotes the resource attribute `deployment.environment` (the service sends `APP_ENV`) to the label `deployment_environment` (Grafana's OTLP docs). Every selector in `rules.yaml`, `rules-test.yaml` and the dashboards now also filters `deployment_environment="production"`, and `MetricNamesTest` fails on one that doesn't. Staging keeps pushing, but no alert or dashboard reads it. **Still to see live:** Explore shows the label on staging's series (the Grafana token answered 401 on 2026-10-18) |
| P-4 | The system suite and `seedStaging` read `deploy/staging/` paths and the `staging.url` property | The production smoke test can't run without them | **Fixed:** the accounts file follows the exported `APP_ENV`: `deploy/<env>/.accounts.<env>` (staging or production; anything else is refused). The URL was already a parameter. `injectErrors` pushes under the exported environment too |

## 4. Production environment
It is built the way staging was (deploy/README.md, staging runbook steps 1–12), with these differences:

| Part | Staging | Production |
|---|---|---|
| Vercel project | `restaurant-staging` | `restaurant-production`, same Hobby account, disconnected from GitHub at once |
| Public domain | Removed; Deployment Protection on, test clients send the bypass header Removed, as on staging: Deployment Protection on, test clients send the bypass header (Q-24, decided) |
| Neon | Project `restaurant-staging`, Singapore, PostgreSQL 18 | Project `restaurant-production`, same settings |
| Upstash | `restaurant-staging`, Singapore | Staging's database, keys prefixed `production:` (CR-015, approved 2026-10-18): the free plan allows one database per account |
| Grafana Cloud | The free stack | The same stack; see P-3 |
| Settings file | `deploy/staging/staging.env` | `deploy/production/production.env` (committed): `APP_ENV=production`, `AUTH_ISSUER=https://restaurant-production.vercel.app`, `https` app URLs, `LIVE_PUSH=off` |
| Secrets | `deploy/staging/.env.staging` | `deploy/production/.env.production`, made by `generateSecrets`. No key or password is shared with staging (key policy) |
| WAF | `auth-per-ip`, 300 per 60 s | `auth-per-ip`: 60 per 60 s, core-and-auth §11's design value (staging's 300 was raised for its load runs, Q-20). Read it back with `rules inspect`, then probe it |
| Data | Seed plus history (test-plan §6) | `seedStaging` only: its first admin comes from the operator's bootstrap (the real start-up path), then restaurants A and B through the API. No history, no load |

## 5. Release steps
**You** marks the owner's steps. The auto-mode safety check blocks Claude from production deploys, from writing secrets to Vercel and from merging. Claude runs the rest and reads the results.

| # | Step | Who | Done when |
|---|---|---|---|
| 1 | §3's fixes merged to `main`; CI green on the release commit | Claude (PR), you (merge) | CI's `build` and `image` jobs pass |
| 2 | Staging redeployed from the release commit; the AT subset passes there (architecture §14 step 2) | You (deploy), Claude (run) | Smoke and AT subset pass on staging |
| 3 | Tag `release/1.0.1` on the release commit (`1.0.0` was tagged first; CR-015 made it `1.0.1`) | Claude | The tag points at the commit staging just passed |
| 4 | Set up production (§4): Neon, Upstash, Vercel project and git disconnect, bypass secret, WAF rule | You | Each item exists; `vercel git disconnect` done |
| 5 | Generate production secrets; fill in the provider secrets | Claude, then you | `.env.production` complete, mode `600`, not in git |
| 6 | Run the migrations on production (`migrate`, V1 to V11); R-15's `BYPASSRLS` check passes as on staging | Claude | Flyway reports 11 applied, roles created |
| 7 | **Go / no-go**: every row of §8's entry list is ticked | You | You say "go" |
| 8 | Send the environment variables, deploy the release commit and remove the public alias `restaurant-production.vercel.app` (Q-24): `deploy/release.sh production --env` does all three | You | Deployment URL sent to Claude (never the bypass secret) |
| 9 | Smoke-check: `/health`, the alias check (no alias answers 200 without the bypass header), start-up log line, logs free of secrets and personal data | Claude | All pass |
| 10 | Seed production with `seedStaging` and production's exported settings: the first admin from the operator's bootstrap, then restaurants A and B | Claude | `deploy/production/.accounts.production` written, mode `600` |
| 11 | Smoke run: the system suite (`systemTest -PstagingUrl=<production URL>`) with production's settings exported: sign-in, onboarding, the full order flow, reports, guest scan, each response checked against the contract | Claude | Every AT scenario passes |
| 12 | Rollback rehearsal (§6): point production at a second deployment and back, timed | You (alias), Claude (checks) | Each switch takes under 5 minutes and `/health` answers 200 after each |
| 13 | Watch period (§7) | Claude, you | §7's exit criteria are met |
| 14 | G6 decision | You | Gate log row filled |

## 6. Rollback
Migrations are expand-only, so the earlier deployment works on the newer schema, and switching the alias back is the whole rollback. **Don't restore the database for a code problem.** A restore loses everything written since the restore point (RPO ≤ 5 min, ST-08), so it is only for a data problem.

- **How:** `vercel alias set <earlier deployment URL> <production domain>`, not `vercel rollback`, which answered `500` on staging once its domain was removed (CLAUDE.md, ST-19). For a release with no earlier production deployment (this first one), roll back by removing the alias, which takes production offline.
- **Roll back when** any of these happens within the watch period: `/health` fails for 5 minutes; an order-flow SLO alert fires; any 5xx on order placement that isn't a platform cold start; a contract mismatch in the smoke run; a secret or personal data in the logs (then also rotate, key policy).
- **Data problem:** the restore drill's runbook (deploy/README.md "Restore drill", RTO ≤ 13 min measured).

## 7. Watch period
The first **48 hours** after step 9. That covers two cron cycles, because Hobby runs each job once a day.

- Day 1: after the deploy, and again the next morning: Vercel logs reviewed with `log-review.py` (5xx, start-ups, privacy), the Grafana dashboards, and the alert states.
- Day 2: each of the five cron jobs has one recorded run, and their `…_timestamp_seconds` gauges are fresh; the outbox and event purge counts are as expected (outbox empty with `LIVE_PUSH=off`).
- **Exit:** no rollback trigger hit, no open S1/S2 defect, two clean cron cycles.

## 8. G6 criteria
**Entry (step 7, go / no-go):**
- [x] §3's P-1 to P-4 fixed, or accepted by the owner
- [x] Release commit tagged; CI green; staging passed the smoke test and AT subset on it
- [x] Production set up per §4; no secret shared with staging (beside CR-015's Upstash token; Grafana token separate since 2026-10-19, Q-26)
- [x] Migrations applied on production
- [x] Q-24 and Q-25 decided (2026-10-18)

**Exit (G6, Launch accepted):**
- [ ] Steps 8–13 done, each with its evidence in §9
- [ ] Watch period exited cleanly (§7)
- [ ] deploy/README.md has a production runbook (this checklist's §4–§6 as steps) and the environments table names production
- [ ] RTM and STATUS updated; accepted risks carried into the G6 row

## 9. Evidence
Filled during the release.

| Step | Result | Date | Reference |
|---|---|---|---|
| 1 | PR #39 merged (P-1 to P-4, version `1.0.0`); CI `build`, `contract` and `image` passed; release commit `main` `35826b8` | 2026-10-18 | PR #39 |
| 2 | Staging redeployed from `35826b8` (deployment `pyojll743`, `dpl_3CrqS9Ee4qjixwPAV9JUa2o6XW6T`). Smoke: `/health` 200 with the bypass header, 302 without; `restaurant-staging-ecru.vercel.app` answers 404 `DEPLOYMENT_NOT_FOUND`; start-up clean (both pools, ready in 2.2–2.4 s). `restaurant-staging-liam-36c7.vercel.app` still pointed at the earlier deployment (a manual alias since ST-19 doesn't follow new deploys); the owner moved it with `vercel alias set`, and it answers 302 without the header. System suite run `tn419f`: 32 tests, 29 passed, 0 failed, 3 skipped (ST-08's restore drill, run only on request); 970 requests. Log review: ST-15 nothing found in 1,007 lines, ST-18 both request IDs found | 2026-10-18 | `build/test-results/systemTest/` |
| 3 | Annotated tag `release/1.0.0` pushed, pointing at `35826b8` (the commit staging passed in step 2) | 2026-10-18 | tag `release/1.0.0` |
| 4 | Neon project `restaurant-production` (Singapore, PostgreSQL 18); Upstash shared with staging (CR-015). Vercel project `restaurant-production` (`prj_SpK4IOtHUOuWGnmhmUnKA0vs59Ro`) linked from the repository root: no Git link (checked through the API), Deployment Protection on, no deployment yet. Protection Bypass for Automation secret created by the owner and stored in `.env.production` (32 characters; not read back). WAF rule `auth-per-ip` from `deploy/production/waf-auth-per-ip.json` (`rules add --json`, then `publish`), read back with `rules inspect`: active, valid, path prefix `/v1/auth/`, fixed window 60 per 60 s per IP; no pending draft. The probe waits for step 9, when a deployment exists | 2026-10-18 | `deploy/production/waf-auth-per-ip.json` |
| 6 | Migrations on production, run by the owner (the auto-mode check blocks Claude): 11 applied, V1 to V11, PostgreSQL 18.6, 8.5 s; the roles' creation raised no error (R-15). The service's first connection as `app` and `app_platform` confirms it | 2026-10-18 | owner's terminal |
| 2 (1.0.1) | Staging redeployed from `main` `67e0cf1` (PR #41, CI `build`, `contract`, `image` green): deployment `4av9m1h4l`. Smoke: `/health` 200 with the bypass header in 0.8 s; without it the deployment and `restaurant-staging-liam-36c7.vercel.app` answer 302, `restaurant-staging-ecru.vercel.app` 404; start-up clean (ready in 2.2 s). System suite run `tn458b`: 32 tests, 29 passed, 0 failed, 3 skipped (ST-08 on request only); 946 requests; ST-14's burst limited by the app, then the WAF at 300. Log review: ST-15 nothing found in 1,198 lines, ST-18 both request IDs found. **Incident** on the first attempt: the command sent to production, see the STATUS decision of 2026-10-19 | 2026-10-19 | `build/test-results/systemTest/` |
| 3 (1.0.1) | Annotated tag `release/1.0.1` pushed, pointing at `67e0cf1` (the commit staging passed above; `build.gradle.kts` says `1.0.1`) | 2026-10-19 | tag `release/1.0.1` |
| 5 | `deploy/production/.env.production` complete: no empty value, mode `600`, ignored by git (`.gitignore` `.env*`); its secrets are production's own beside CR-015's Upstash pair (Q-26) | 2026-10-19 | checked by Claude |
| 7 | Go given by the owner for `1.0.1` | 2026-10-19 | STATUS Decisions |
| 8 | Run by the owner: 20 variables sent to `restaurant-production`; `main` `ba8a748` (same `src/main` as `release/1.0.1`) deployed as `hgpiz00l3` (`dpl_Cu6BcjRHEBjZYT3y6kU3rRP7sWCe`), image `sha256:d11f80d787ad…`, ready about 00:04:55 UTC. The pasted line wrapped, so its `vercel alias rm` ran without the alias: `restaurant-production.vercel.app` answered `200` until Claude removed it at about 00:05:40 UTC. Its one request in the logs was Claude's own `/health`; no account existed and the first admin comes only from the operator's command, so nothing could be claimed. Fix: `deploy/release.sh` (CLAUDE.md) | 2026-10-19 | owner's terminal; STATUS Decisions |
| 9 | `/health` 200 with the bypass header on the deployment (0.7 s) and on `restaurant-production-liam-36c7.vercel.app` (8.9 s, a cold start); without it both answer `302` and `restaurant-production.vercel.app` `404`; `vercel alias ls` lists only the protected alias. Start-up clean: 6 instances, both pools, application started in 2.1–2.6 s, no WARN or ERROR (only the JDK's `Unsafe` and native-access notices). `log-review.py --project restaurant-production`: no personal data, secret or token in 106 lines | 2026-10-19 | `log-review.py` |
| 10 | Run by the owner (`deploy/run-task.sh production seed`; the auto-mode check blocks Claude from writing to production's database): two admins (the first TOTP-enrolled through the operator's command, the second invited), restaurants A (`01a15195-e516-75e1-9a26-bb3cd4eb74d1`) and B (`01a15196-06b9-737d-99c4-2bee674c6ec5`), 150 requests. One `429` from the WAF's `auth-per-ip` (60 per 60 s), waited out. `deploy/production/.accounts.production` mode `600`, ignored by git | 2026-10-19 | owner's terminal |
| 11 | Run by the owner (`deploy/run-task.sh production system`), run `tn4pzw`, 5 min 50 s, 705 requests: 32 tests, 0 failures, 3 skipped (ST-08's restore drill, which runs only in a drill); every response checked against the contract. Vercel's records for the run: 704, no `5xx` (200 ×417, 201 ×57, 204 ×28, 400 ×4, 401 ×42, 403 ×55, 404 ×58, 409 ×11, 412 ×1, 429 ×31: the suite's negative cases, the abuse-limit tests and the WAF's `auth-per-ip`, waited out). `log-review.py`: no personal data, secret or token in 721 lines | 2026-10-19 | `build/test-results/systemTest` |
| 12 | Run by the owner (`deploy/switch.sh`). Second deployment `blmz4e4fk` (`dpl_DQu8FD4UwokSdt4qHtLpcQ3MYQej`, from the same tree, made by `deploy/release.sh production` at 01:26:55 UTC); both answered `/health` 200 through the bypass header before the switch. Rollback to `hgpiz00l3` at 01:35:54 UTC: alias set in 1 s, `/health` 200 after 2 s. Forward to `blmz4e4fk` at 01:37:22 UTC: 1 s, 200 after 2 s. After each, `vercel alias ls` showed the protected domain on the intended deployment, `restaurant-production.vercel.app` answered `404` and the protected domain `302` without the header. Production now serves `blmz4e4fk`; `hgpiz00l3` is the rollback target | 2026-10-19 | checked by Claude |
| 13 (day 1) | Logs: `log-review.py --project restaurant-production --since 1h` (Hobby keeps an hour) at about 01:45 UTC: 7 requests, no `5xx` (five `/health` from the step 12 checks, two `GET /` `404` on `blmz4e4fk` just after it was ready); 4 start-ups, application started in 2.4 s; of 91 lines the service's are all INFO, the rest the JDK's known start-up warnings (`sun.misc.Unsafe`, native access; 8 per start-up); no personal data, secret or token. Grafana: production's requests arrive (`ktor_http_server_requests_milliseconds_count`, `deployment_environment="production"`, last 3 h: 325 `2xx`, 134 `4xx`, no `5xx`). Alert states: none, because no rule is loaded in the stack (`load.sh` never ran since ST-07's live half was dropped; R-21). `.env.grafana` was refilled by the owner (it held two values of five); its service-account token reads the stack, its rules token is refused by the ruler (`401`) | 2026-10-19 | checked by Claude |
