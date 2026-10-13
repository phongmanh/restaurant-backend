# S-1 spike: Ktor on Vercel's container runtime

**Throwaway.** This code answers the S-1 questions for [ADR-0002](../../docs/sdlc/restaurant-ordering/adr/0002-deployment-platform-vercel.md) and is then deleted. Nothing here is reused in the service. Keep Deployment Protection on while it's deployed: `/spike/sleep` and `/spike/sse` hold connections open by design.

It deploys the way `ktor-sample` does (see that project's `docs/VERCEL.md`): an OCI image run as a Vercel Function (`"runtime": "container"`), with `PORT=8080` and the `PATH` fix in the entrypoint. The differences: JDK 25, and the function region pinned to `sin1`.

## Questions and how each is answered
| # | Question | Endpoint | Command |
|---|---|---|---|
| Q1 | Region, CPUs, memory limit, JVM, proxy headers (which client-IP header to trust) | `GET /spike/info` | `info` |
| Q2 | Warm latency of a trivial route from Vietnam | `GET /spike/info` | `warm` |
| Q3 | Cold-start latency after scale-to-zero (5 min idle in production, 30 s in preview), with and without the AOT cache | `GET /spike/info` (`firstRequestOnInstance`, `startupMs`) | `cold` |
| Q4 | Concurrent requests per instance; when Vercel adds instances | `GET /spike/sleep?ms=` (`inFlightAtStart`, `instanceId`) | `concurrency` |
| Q5 | Maximum request duration | `GET /spike/sleep?ms=` | `duration` |
| Q6 | Is SSE streamed live or buffered, and how long can a stream stay open? (decides whether ADR-0003 needs a vendor) | `GET /spike/sse?seconds=&intervalMs=` | `sse` |
| Q7 | CPU share per instance (input to the capacity model) | `GET /spike/cpu?ms=` | `cpu` |
| Q8 | SIGTERM on scale-down: when, and how much grace | log line `spike shutdown …` | `vercel logs <url>` |
| Q9 | Rolling Releases on our plan; WAF rate-limit rules for container services; log drains | — | Vercel dashboard (manual) |

## Run it
Prerequisites: the Vercel CLI (`vercel login`), Node 18+.

```bash
cd spikes/s1-vercel
./gradlew test                      # local check, JDK 21+
vercel link                         # new project, e.g. "restaurant-s1-spike", in your team
vercel env add PORT                 # value 8080, for Preview and Production
vercel deploy                       # preview; ~3 min (image build)
vercel deploy --prod                # production: needed for the 5-min scale-down behaviour
vercel inspect <url>                # confirm the region is sin1
```

For the scripts, create a **Protection Bypass for Automation** secret (Project Settings → Deployment Protection) and export it:

```bash
export VERCEL_AUTOMATION_BYPASS_SECRET=...   # never commit it
URL=https://<deployment-url>
node scripts/measure.mjs info $URL
node scripts/measure.mjs warm $URL --n 200
node scripts/measure.mjs concurrency $URL --c 50 --hold 5000
node scripts/measure.mjs concurrency $URL --c 200 --hold 5000
node scripts/measure.mjs cpu $URL --ms 2000
node scripts/measure.mjs sse $URL --seconds 900 --interval 1000
node scripts/measure.mjs duration $URL --steps 60,300,800,1800,3600
node scripts/measure.mjs cold $URL --samples 10 --idle 45      # preview URL (30 s scale-down)
node scripts/measure.mjs cold $URL --samples 6 --idle 330      # production URL (5 min scale-down)
```

Run the scripts from Vietnam (or note where they ran from): latency includes the client's network path to `sin1`.

**AOT variant (Q3).** Change `"entrypoint"` in `vercel.json` to `Dockerfile.vercel-aot`, redeploy, and repeat `info` (the JVM args should show `-XX:AOTCache`) and both `cold` runs.

## Exit criteria (from architecture §17)
- Warm p95 ≤ 50 ms for `/spike/info`, measured at the edge (subtract the client's network RTT to `sin1`).
- Cold starts small and rare enough that NFR-PERF-01/02 p99 (500 / 800 ms) hold at the expected cold-start rate. If not, AOT variant; then GraalVM native (ADR-0002 option 2); then a CR.
- Q6 decides ADR-0003. SSE streams live for ≥ 15 min with tens of streams per instance → SSE from the service is an option. Otherwise the managed pub/sub design stands.

## Results
Paste each command's output here, with the date, the deployment URL (not the bypass secret) and where the client ran.

| Question | Result | Meets criterion? |
|---|---|---|
| Q1 info | | |
| Q2 warm | | |
| Q3 cold (plain / AOT) | | |
| Q4 concurrency | | |
| Q5 duration | | |
| Q6 SSE | | |
| Q7 CPU | | |
| Q8 SIGTERM | | |
| Q9 platform features | | |

## Not verified locally
- The Docker images weren't built on the development machine (the Docker daemon wasn't running). Vercel builds them anyway. To check locally first: `docker build -f Dockerfile.vercel .`.
- The AOT variant needs JDK 25 (`-XX:AOTCacheOutput`, JEP 514). It is built and trained inside the image; the local JDK is 21.
