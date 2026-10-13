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

**AOT variant (Q3).** Vercel rejects `Dockerfile.vercel-aot` (and `Dockerfile.aot`) as an entrypoint: "It must name a Dockerfile or Containerfile". Copy it over `Dockerfile.vercel` for that deploy only (`cp Dockerfile.vercel-aot Dockerfile.vercel && vercel deploy; git checkout Dockerfile.vercel`), and repeat `info` (the JVM args should show `-XX:AOTCache`) and both `cold` runs.

## Exit criteria (from architecture §17)
- Warm p95 ≤ 50 ms for `/spike/info`, measured at the edge (subtract the client's network RTT to `sin1`).
- Cold starts small and rare enough that NFR-PERF-01/02 p99 (500 / 800 ms) hold at the expected cold-start rate. If not, AOT variant; then GraalVM native (ADR-0002 option 2); then a CR.
- Q6 decides ADR-0003. SSE streams live for ≥ 15 min with tens of streams per instance → SSE from the service is an option. Otherwise the managed pub/sub design stands.

## Results
Paste each command's output here, with the date, the deployment URL (not the bypass secret) and where the client ran. Run the scripts with `node --env-file=.env.spike scripts/measure.mjs …`, where `.env.spike` (ignored by git) holds `VERCEL_AUTOMATION_BYPASS_SECRET`.

Deployed 2026-10-13 to project `liam-36c7/restaurant-s1-spike`, plain JVM image (`Dockerfile.vercel`), production target: `https://restaurant-s1-spike-2g1iyyarl-liam-36c7.vercel.app` (`dpl_6CdhjGEKkoNrRVS5DFHgQ33s4sPz`). The first deploy of a project goes to production, and Standard Protection leaves the production alias `restaurant-s1-spike.vercel.app` public, so that alias was removed (`vercel alias rm`); the deployment URLs stay behind Vercel Authentication. Client: Vietnam (`x-vercel-ip-country: VN`). Further deployments, all preview: plain `restaurant-s1-spike-k20vekvlr-…` (cold tests), AOT `restaurant-s1-spike-2tn99omzj-…` (cold tests), and plain with the SIGTERM probe `restaurant-s1-spike-4en9l1jiv-…` (Q8).

| Question | Result | Meets criterion? |
|---|---|---|
| Q1 info | `sin1`, `VERCEL_ENV=production`; JDK 25.0.4.1, G1; **1 CPU**, container limit 2048 MB, max heap 1536 MB (75%); JVM start to ready 1,650 ms. Proxy headers: `x-forwarded-for`, `x-real-ip`, `x-vercel-forwarded-for` all carry the client IP; `remoteHost` is `localhost`, so trust `x-vercel-forwarded-for` | Yes (inputs to capacity model; 1 CPU per instance noted) |
| Q2 warm | 300 sequential: p50 37 · p95 56 · p99 135 · max 321 ms (1 instance). A first run of 200 had p95 66 and one 7,384 ms outlier (a request routed to a new instance). TCP connect to the edge is ~7 ms, so p95 at the edge ≈ 49 ms | Yes, just (≈ 49 ms against 50 ms) |
| Q3 cold (plain / AOT) | Preview, 45 s idle, every sample cold. **Plain:** p50 6,821 · max 8,683 ms (n=10); rerun p50 6,402 · max 6,586 ms (n=6); JVM start ~1,700 ms. **AOT** (`-XX:AOTCache` confirmed): p50 5,380 · max 5,640 ms (n=10); JVM start ~650 ms. So ~4.7 s of every cold start is the platform (sandbox and image start) before the JVM runs: a native image can't remove it. A new deployment's first request took 10.0 s. **The request right after a cold start often lands on a second new instance** and is cold too: 4/6 (plain rerun, instance IDs checked), 8/10 (plain) and 2/10 (AOT) follow-ups took 5–11 s. **Production** (`2g1iyyarl`, 330 s idle): 6/6 cold, p50 6,542 · max 7,373 ms; follow-up on a second new instance 4/6. Production does scale to zero within 5.5 min | **No.** 5–9 s against p99 500/800 ms; AOT and native can't fix the platform's share. Holds only if cold hits stay under 1% of requests, which needs a load test |
| Q4 concurrency | 50 × 5 s from 1 warm instance: 14 instances, 4 took 9–12 requests each and 10 took 1; wall 12.1 s, so ~10 requests waited ~7 s for new instances; 0 errors. 200 × 5 s straight after (14 instances warm): no queueing (wall 5.8 s), 9–23 requests per instance, max 23 in flight, 0 errors | Yes for concurrency (≥ 23 per instance); **scale-out costs a full cold start for the requests that trigger it** |
| Q5 duration | Server side, Vercel ends any request at **300 s**: `504 Vercel Runtime Timeout Error: Task timed out after 300 seconds` (the free Hobby account's cap; Pro allows up to 800 s with `maxDuration`). 60 s holds return 200. Client side, a middlebox on this client's path drops TCP connections idle for ~40 s (`read ETIMEDOUT` at 39–42 s without keepalive, 200 with 10 s keepalive probes); even with probes, the 290–800 s holds failed client side at ~280 s, so the server logs are the record | 300 s cap (plan dependent). Bounded batch jobs as planned; on Pro, 800 s at most |
| Q6 SSE | Streamed live: 300 events at 1 s, arrival lag p50 6 · p95 79 · max 110 ms. Cut at **300 s** with no end event (logged as the runtime timeout) | **No** (≥ 15 min needed; even Pro's 800 s falls short). The managed pub/sub design in ADR-0003 stands. Clients must reconnect and catch up anyway; heartbeats < 30 s for mobile NATs |
| Q7 CPU | 1 vCPU. `/spike/cpu?ms=2000`, one thread: 4,101,596 iterations on Vercel (JDK 25) against ~3,300,000 on one Apple M1 Pro core (JDK 21) ≈ 1.24× an M1 Pro core. Memory 2048 MB | Input to the capacity model |
| Q8 SIGTERM | Preview: SIGTERM 37–41 s after the instance's last request; the shutdown hook ran on every instance seen. With the hook kept alive, the last tick is **+29 s** on every instance: 30 s grace, then SIGKILL | Yes. 30 s is enough for Ktor's graceful stop |
| Q9 platform features | The spike ran on a free **Hobby** account (the team `liam-36c7` is a personal test account, not the production one); fluid compute on. On Hobby: maxDuration capped at 300 s, no Rolling Releases, no log drains. These need Pro, which a commercial service needs anyway (Hobby is non-commercial only). WAF custom rules and rate limiting not checked | Plan-dependent features not measured; production on Pro (cost input to TBD-11). WAF still to check |

## Not verified locally
- The Docker images weren't built on the development machine (the Docker daemon wasn't running). Vercel builds them anyway. To check locally first: `docker build -f Dockerfile.vercel .`.
- The AOT variant needs JDK 25 (`-XX:AOTCacheOutput`, JEP 514). It is built and trained inside the image; the local JDK is 21.
