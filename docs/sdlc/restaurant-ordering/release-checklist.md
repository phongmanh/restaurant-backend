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
| 3 | Tag `release/1.0.0` on the release commit | Claude | The tag points at the commit staging just passed |
| 4 | Set up production (§4): Neon, Upstash, Vercel project and git disconnect, bypass secret, WAF rule | You | Each item exists; `vercel git disconnect` done |
| 5 | Generate production secrets; fill in the provider secrets | Claude, then you | `.env.production` complete, mode `600`, not in git |
| 6 | Run the migrations on production (`migrate`, V1 to V11); R-15's `BYPASSRLS` check passes as on staging | Claude | Flyway reports 11 applied, roles created |
| 7 | **Go / no-go**: every row of §8's entry list is ticked | You | You say "go" |
| 8 | Send the environment variables, deploy the release commit, remove the public alias (Q-24) | You | Deployment URL sent to Claude (never the bypass secret) |
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
- [ ] §3's P-1 to P-4 fixed, or accepted by the owner
- [ ] Release commit tagged; CI green; staging passed the smoke test and AT subset on it
- [ ] Production set up per §4; no secret shared with staging
- [ ] Migrations applied on production
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
