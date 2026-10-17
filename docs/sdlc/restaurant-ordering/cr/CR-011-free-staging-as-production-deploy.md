# CR-011: Run the free-plan staging as its own project's production deployment

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-011 | Approved (2026-10-17) | 2026-10-17 | Claude, from Phase 5 step 2 (staging) | Manh Nguyen (product owner) | architecture.md v1.4 §8 |

## Summary
Architecture §8 says that on the free plans, "staging is a preview deployment of a `staging` branch with its own Neon branch". Building staging in Phase 5 step 2 showed that a preview deployment can't run what Phase 5 tests. This CR changes that sentence: on the free plans, staging is the **production deployment of its own Vercel project** (`restaurant-staging`), deployed from the CLI with its public alias removed, on **its own Neon project**. Test-plan v0.5 §3 and §9 already describe it this way.

## Why
1. **Vercel Cron runs only on production deployments.** The five jobs (`/internal/jobs/*`) never run on a preview, so retention, session auto-close, the outbox sweep and the sales reconcile would go untested on staging (ST-15, ST-10).
2. **A preview scales to zero after 30 s idle, a production deployment after 5 min** (architecture §8, S-1). The cold-start numbers (ST-01, ST-02) would then measure a behaviour production doesn't have.
3. **A preview of a `staging` branch needs the project connected to the GitHub repo.** Then every push and PR builds it, and a merge to `main` deploys to production with a public alias (CLAUDE.md, found in Phase 2 with the spikes). Deploying from the CLI avoids that, as the spikes did.
4. **Its own Neon project, not a branch of another one.** The free plan's 0.5 GB of storage is per project (test-plan §6 sizes the history to it), and a separate project keeps staging apart from the spikes' data and from a future production database.

## Proposed change
architecture.md §8, the "On this learning project's free plans" paragraph, v1.4 → v1.5:

> On this learning project's free plans: staging is the production deployment of its own Vercel project (`restaurant-staging`), deployed from the CLI (`vercel deploy --prod`) with its public alias removed. Deployment Protection stays on, and test clients send the project's automation bypass header. It has its own Neon project (Singapore). The database is one compute that scales to zero (R-11) with a 6 h restore window (R-14), not HA. Full-size load tests wait for paid plans (R-12, R-13, R-16).

## Impact
| Item | Change | When |
|---|---|---|
| architecture.md | v1.4 → v1.5: the §8 paragraph above | Applied 2026-10-17 on branch `phase5/staging` |
| test-plan.md | None: v0.5 §3 and §9 already say so | — |
| `deploy/README.md` | The staging runbook follows it | Phase 5 step 2 |
| Code, contract, designs | None | — |

## What stays the same
- The environment table in §8 for a paid launch: staging as a Vercel staging environment with a production-sized database.
- Rollout and rollback in §8.

## Risks this adds
- **The bypass header skips Deployment Protection, not the WAF** (S-1 Q9: the WAF answered 429 on a deployment URL with the bypass header). ST-14 can therefore test the WAF rule on staging. The secret must stay out of logs and chat, like any other secret.
- **Each deploy gets a new URL**, because the stable alias is removed. Test runs take the base URL as an argument.
