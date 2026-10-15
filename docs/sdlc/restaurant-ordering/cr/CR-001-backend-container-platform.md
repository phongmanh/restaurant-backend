# CR-001: Run the backend on a container platform instead of Vercel Functions

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-001 | Deferred | 2026-10-13 | Manh Nguyen (tech lead), from spike S-1 | Manh Nguyen (product owner) | requirements.md v1.0 (G1) |

## Summary
Deploy the Ktor service on a container platform with always-warm instances (recommended: Google Cloud Run in `asia-southeast1`, Singapore, with minimum instances). The client web apps can stay on Vercel. This replaces the requirements constraint "Deployment on Vercel" and ADR-0002, as ADR-0002 itself anticipated ("If neither works, raise a CR for option 3").

## Why
Spike S-1 (`spikes/s1-vercel/README.md`, 2026-10-13) ran the service's runtime (JDK 25, Ktor 3) on Vercel's container runtime in `sin1`, measured from Vietnam:

- **Cold starts are 5–7 s** (plain image p50 6.4–6.8 s, production 6.5 s; AOT cache p50 5.4 s), against p99 targets of 500 ms (NFR-PERF-01) and 800 ms (NFR-PERF-02).
- **About 4.7 s of each cold start is the platform**, before the JVM starts (the JVM itself takes 0.65–1.7 s). The AOT cache and a GraalVM native image, ADR-0002's two fallbacks, can only remove the JVM's share.
- **Cold starts are frequent at low traffic.** Instances scale to zero after a few minutes idle, and the request after a cold start often lands on a second new instance (4 of 6 production samples). A quiet restaurant's first guest would wait 6–7 s, often twice in a row.
- **Scale-out costs a full cold start** for the requests that trigger it (10 of 50 parallel requests waited about 7 s).
- **Requests and SSE streams end at 300 s** on the account tested (800 s at most on Pro), so live updates must go through an external service.

Warm performance is good (p95 ≈ 49 ms at the edge; at least 23 concurrent requests per instance), but no configuration of Vercel Functions keeps an instance warm, so the p99 targets can't be met.

## Proposed change
1. **Requirements §1 constraints:** replace "Deployment on Vercel (mandated…)" with "The backend runs as a container on a managed container platform with always-warm instances, in the region nearest Vietnam allowed by TBD-10 (CR-001). Client web apps may be hosted on Vercel."
2. **Requirements §6 integrations:** replace the Vercel row with the chosen container platform (no function duration limit for live connections; minimum instances; private networking to the database).
3. **Requirements §1 top risks:** R-01 becomes "container platform limits" (lower likelihood; see STATUS).
4. **ADR-0010** (new) records the platform choice and supersedes ADR-0002.

### Platform options for ADR-0010
| Option | Fit | Watch |
|---|---|---|
| **Google Cloud Run**, `asia-southeast1` (recommended) | Minimum instances remove cold starts for steady traffic; long request timeouts (up to 60 min) allow SSE from the service; Cloud SQL for PostgreSQL over private IP (addresses R-06); per-revision tagged URLs for PR previews; managed TLS and scaling | Always-on minimum instances cost money even when idle (TBD-11); SIGTERM grace and scale-out cold starts to measure in S-6 |
| AWS ECS on Fargate, `ap-southeast-1` | Always-running tasks, no cold start; RDS over private networking; mature | More to operate (load balancer, VPC, autoscaling policies); PR previews are custom work |
| Fly.io, `sin` | Machines stay running; simple deploys; per-PR apps are easy | Managed PostgreSQL and SLAs less mature; fewer compliance documents for TBD-10 |

All three run the same OCI image built for S-1, so ADR-0001, ADR-0005, ADR-0006 and ADR-0007 stay as they are.

## Impact
| Item | Change | When |
|---|---|---|
| requirements.md | v1.0 → v1.1: constraint, integration row and risk line above; changelog entry citing CR-001 | On approval |
| ADR-0002 | Status → Superseded by ADR-0010 (CR-001); S-1 results kept as the record | On approval |
| ADR-0010 (new) | Container platform, region, minimum instances, preview environments, shutdown | On approval, Accepted after S-6 |
| ADR-0003 live updates | Re-evaluate: SSE from the service, fanned out across instances through Redis or PostgreSQL `LISTEN/NOTIFY`, becomes an option next to managed pub/sub. The event-log catch-up stays either way | Before G2; S-3 compares both |
| ADR-0004 database and pooling | Fewer, long-lived instances: an application pool sized by maximum instances, maybe no external pooler; private IP. R-09 shrinks | Before G2; S-2 runs against the chosen database |
| ADR-0008 rate limiting | Vercel WAF replaced by the platform's edge (e.g. Cloud Armor) or a CDN in front; shared Redis stays | Before G2 |
| ADR-0009 observability | OTLP push stays; logs go to the platform's logging instead of a Vercel log drain | Before G2 |
| ADR-0001, ADR-0006 | Wording only ("one Vercel project", "Vercel environment variables" → the platform's secret manager) | With ADR-0010 |
| architecture.md | Deployment view (§8), capacity model (§9), environments and release (Rolling Releases → traffic splitting), runbooks, threat model for the new network boundary | Phase 2, before G2 |
| test-plan.md | System and NFR environments: staging and per-PR previews on the new platform | Before G2 |
| rtm.md | NFR-SCAL-01 and NFR-PRIV-02 design column: ADR-0002 → ADR-0010 | On approval |
| STATUS.md | Deployment platform row; R-01, R-06, R-09; S-1 closed; new spike S-6; TBD-11 cost inputs | On approval |
| Code | None yet (Phase 4 hasn't started) | — |

## What stays the same
- Every functional requirement and every NFR target: this CR changes how the targets are met, not the targets.
- Kotlin, Ktor, JDK 25, one module, PostgreSQL with RLS, guest QR tokens, the hand-written OpenAPI contract.
- Data residency is still open (TBD-10, blocks G2). The region choice in ADR-0010 depends on it.

## Cost and schedule
- **Cost:** always-on minimum instances replace pay-per-request. This becomes an input to TBD-11. A pub/sub vendor may no longer be needed if S-3 favours in-process SSE.
- **Schedule:** G2 waits for spike **S-6**, which runs the S-1 app on the chosen platform and measures cold start with minimum instances, scale-out latency, warm latency from Vietnam, SSE duration and SIGTERM grace. That's about a day of work, plus the ADR and architecture updates.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-13 | Deferred | Manh Nguyen (product owner) | Keep Vercel for now. S-1 ran on a free Hobby account, where production scales to zero; Vercel says Pro and Enterprise keep one production instance warm ("scale to one"). Reopen this CR if cold starts on a paid plan still break the p99 targets |
