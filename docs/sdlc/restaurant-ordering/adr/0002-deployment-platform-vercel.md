# ADR-0002: Deploy on Vercel Functions in Singapore

| Status | Date | Deciders | Requirements |
|---|---|---|---|
| Proposed (pending spike S-1 and TBD-10) | 2026-10-13 | Manh Nguyen (product owner, tech lead) | NFR-PERF-01..03, NFR-SCAL-01, NFR-AVAIL-01, NFR-OPS-01, NFR-PRIV-02 |

## Context
The stakeholder requires Vercel and has deployed a Ktor service there before. The default stack in the skill assumes a long-running container platform with probes, replicas and a scrapeable `/metrics`. On a serverless function platform:
- instances start on demand (JVM cold starts);
- instances keep no state and may be frozen between requests;
- they can't hold thousands of long-lived connections;
- there is no stable instance to scrape;
- deployments are immutable, with instant rollback.

Vercel has no region in Vietnam; `sin1` (Singapore) is the nearest.

**What the stakeholder's earlier deployment shows** (Q-1, `ktor-sample`, its `docs/VERCEL.md`):
- Vercel runs an **OCI container image as a Function** (`vercel.json` service with `"runtime": "container"`), so the JVM needs no special support.
- A service needs a catch-all rewrite to be public.
- Vercel routes to `$PORT` (default 80, so set `PORT=8080` for a non-root process).
- The image's `PATH` is not passed to the process, so the entrypoint must rebuild it.
- Instances scale to zero after **5 min idle in production (30 s in preview)**, and get `SIGTERM` with 30 s of grace.
- **Secure Compute and static IPs are not available for container images.**
- Vercel overwrites `X-Forwarded-For`, so it can be trusted behind the platform.

## Options
1. **Vercel container runtime running the Ktor JVM image**, packaged as in the stakeholder's earlier deployment.
   - Pros: mandated; managed scaling, TLS, WAF and CDN; instant rollback; preview deployment per PR.
   - Cons: cold-start risk for p99; live updates need an external service (ADR-0003); rate limits need a shared store (ADR-0008); metrics must be pushed (ADR-0009); database connection bursts (ADR-0004).
2. **The same container runtime with a GraalVM native image of the Ktor app**
   - Pros: millisecond cold starts.
   - Cons: native-image friction with Exposed, JDBC, Hikari, Flyway and reflection; longer builds.
3. **A container platform** (Cloud Run, Fly.io, ECS) with the web apps on Vercel
   - Pros: the skill's default operating model; WebSockets/SSE in process.
   - Cons: overrides the stakeholder constraint, so it would need a CR.

## Decision
Option 1: a JDK 25 image on Vercel's container runtime, in region `sin1`. If S-1 shows that cold starts break the p99 targets, add a JDK AOT cache first (S-1 measures both), and then option 2. If neither works, raise a CR for option 3.

Scheduled jobs run through Vercel Cron calling `/internal/jobs/*`, protected by `CRON_SECRET`. Migrations run from CI, never in a function.

## Consequences
- Positive: no servers to run; per-PR previews; rollback in seconds.
- Negative, or risks we accept:
  - no in-process state;
  - no private networking to the managed services (R-06);
  - limits on function duration (jobs must work in bounded batches);
  - regional compliance depends on TBD-10.
- Follow-ups: S-1 (`spikes/s1-vercel/`) measures:
  - cold start, plain and with the AOT cache;
  - warm latency from Vietnam;
  - concurrency per instance;
  - maximum request duration;
  - whether SSE streams live, and for how long;
  - CPU and memory per instance;
  - SIGTERM timing;
  - Rolling Releases and WAF availability.

  Update §8 of the architecture with the results and move this ADR to Accepted.
