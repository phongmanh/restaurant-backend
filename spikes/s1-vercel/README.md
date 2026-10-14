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
| Q9 | Rolling Releases on our plan; WAF rate-limit rules for container services; log drains | `GET /spike/waf` (WAF part) | Vercel dashboard (manual); `waf-edge` |
| Q10 | Per-key limits in Upstash Redis (ADR-0008 option 2): time added per request from `sin1`, exact across instances? | `GET /spike/limit?store=redis` | `limit-latency`, `limit-burst` |
| Q11 | Per-key limits in Vercel's WAF called from Kotlin (option 4, no Redis): same questions | `GET /spike/limit?store=firewall` | `limit-latency`, `limit-burst` |
| Q12 | When the store fails (no answer, or an error), do requests fail open, and how fast? | `GET /spike/limit?store=redis-down-*` | `limit-down` |

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

## Rate-limit check (Q9–Q12, added 2026-10-14)
Settles [ADR-0008](../../docs/sdlc/restaurant-ordering/adr/0008-rate-limiting.md), which is Proposed until S-1 confirms the Redis provider and the WAF rules. Everything runs on free plans: Vercel Hobby (one WAF rate-limit rule per project, fixed window, keyed by IP or JA4, window 10 s to 10 min) and Upstash's free plan (500k commands a month; this check uses a few thousand).

**What's measured.** A rate limiter counts a client's requests in a time window and answers 429 past the limit. Our limits are per table session, staff user and restaurant (NFR-SEC-04, NFR-SCAL-02), so the count must be shared by every instance; Ktor's in-memory counters can't do that. `GET /spike/limit` does one fixed-window hit on `key` in the chosen store and times only the store's part (`checkMs`), so the client's own network doesn't blur the result. The stores (`RateLimit.kt`):
- `redis`: Upstash over its REST API, one `EVAL` (increment and expiry in one round trip). REST is the slower of Upstash's two protocols; if it is fast enough, the TCP protocol is too.
- `firewall`: Vercel's WAF rate limiting, called from Kotlin. The `@vercel/firewall` package (JS only) is a plain GET to `/.well-known/vercel/rate-limit-api/<id>` on the deployment's own host; `VercelFirewallLimiter` sends the same request (package 1.2.6). The limit and window are set in the WAF rule, not in the request, and the answer is only allowed or limited. This is not a documented API outside the JS package, so it could change.
- `redis-down-timeout` (an address that never answers) and `redis-down-error` (the right URL, a wrong token): the two ways a store fails.

**Owner steps.** The auto-mode classifier blocks production deploys, secrets and firewall publishing, so these are yours. Run them from `spikes/s1-vercel`:
1. Redis: Vercel dashboard → Storage → Create → **Upstash for Redis**, region **Singapore (`ap-southeast-1`)**, **Free** plan, connected to the project `restaurant-s1-spike` for Production. The integration adds `KV_REST_API_URL` and `KV_REST_API_TOKEN` (or `UPSTASH_REDIS_REST_*`); both names work. Check the names only: `vercel env ls`.
2. Deploy: `vercel deploy --prod`, then `vercel alias rm restaurant-s1-spike.vercel.app`. Send me the deployment URL.
3. Edge rule for Q9 (Hobby allows one rate-limit rule, so the two rules take turns):
   ```bash
   vercel firewall rules add "spike-waf-edge" \
     --condition '{"type":"path","op":"pre","value":"/spike/waf"}' \
     --action rate_limit --rate-limit-window 10 --rate-limit-requests 20 --rate-limit-keys ip --yes
   vercel firewall publish --yes
   ```
4. After `waf-edge` has run, swap it for the keyed rule for Q11:
   ```bash
   vercel firewall rules remove "spike-waf-edge" --yes
   vercel firewall rules add "spike-keyed" \
     --condition '{"type":"rate_limit_api_id","op":"eq","value":"spike-keyed"}' \
     --action rate_limit --rate-limit-window 60 --rate-limit-requests 10 --yes
   vercel firewall publish --yes
   ```
5. Afterwards: `vercel firewall rules remove "spike-keyed" --yes && vercel firewall publish --yes`. The Upstash database can stay (free) for the next spike or go.

**Commands** (Claude can run these; `.env.spike` holds the bypass secret):
```bash
E="node --env-file=.env.spike scripts/measure.mjs"
$E limit-latency $URL --store redis --n 200
$E limit-burst   $URL --store redis --c 50 --limit 10 --window 60     # three times
$E limit-down    $URL --n 20 --timeout 200
$E waf-edge      $URL --n 40 --limit 20 --window 10                    # with rule spike-waf-edge
$E limit-latency $URL --store firewall --n 200                         # with rule spike-keyed
$E limit-burst   $URL --store firewall --c 50 --limit 10 --window 60   # three times, ≥ 60 s apart
```

**Exit criteria** (proposed; the owner confirms):
- Q9: past 20 requests in 10 s from one IP, the WAF answers 429 on the deployment URL before the request reaches the function, and the next window lets requests through again.
- Q10 and Q11, each store: check time p95 ≤ **10 ms** inside the function (5% of the 200 ms guest-read budget; S-2's whole order transaction is p95 29 ms). A burst of 50 hits on one key with limit 10, spread over ≥ 2 instances, allows exactly 10 (Redis) or at most 10 (WAF; any overshoot recorded). Another key straight after is allowed (separate buckets).
- Q12: every request answers 200 with `failedOpen`; the time lost is at most the timeout + 20 ms.

**What the results decide for ADR-0008:**
- WAF meets Q11 → keyed limits in the WAF too, and **no Redis**: one vendor, secret and cost line fewer. Costs to weigh: the API is undocumented outside JS, Pro allows 40 rate-limit rules per project, each rule has one fixed limit (no per-restaurant limits changed at runtime), and counters are per region (fine with one region, `sin1`).
- Only Redis meets Q10 → ADR-0008 option 2 as written, Upstash in Singapore.
- Neither → counters in PostgreSQL (option 3) or a CR.

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
| Q9 platform features | The spike ran on a free **Hobby** account (the team `liam-36c7` is a personal test account, not the production one); fluid compute on. On Hobby: maxDuration capped at 300 s, no Rolling Releases, no log drains. These need Pro, which a commercial service needs anyway (Hobby is non-commercial only). **WAF per-IP rate limit (2026-10-14)**: rule `spike-waf-edge` (path starts with `/spike/waf`, keyed by IP, fixed window 20 per 10 s, made in the dashboard), 40 sequential requests: 200 × 20, then 429 × 20 from request 21 exactly; the 429s came from the edge (`server: Vercel`, `x-vercel-mitigated: deny`, plain-text body, none of the app's headers), so they never reached the function; after 11 s the next request got 200. The edge's 429 carries **no `Retry-After`**. Rules made with `vercel firewall rules add` + `publish` left no rule twice; the dashboard worked | Plan-dependent features not measured; production on Pro (cost input to TBD-11). **WAF per-IP limit: yes** |
| Q10 Redis limits | 2026-10-14, Upstash for Redis (free, `ap-southeast-1`) over REST, deployment `restaurant-s1-spike-5rk3nqgzg-liam-36c7.vercel.app`, client in Vietnam. **Check time** (200 sequential): p50 5.7 · p95 10.9 · p99 19.3 · max 33.6 ms inside the function; client p95 80 ms. **Bursts** (50 parallel, limit 10/60 s, 10–22 instances each): exact in 4 of 6. Two bursts allowed 20: the 10 extra were each the only request on a brand-new instance, and each **failed open** (first call to Upstash from a fresh instance not done within the 200 ms timeout; likely the new TLS connection on a cold JVM, the error text was not captured). One burst allowed 11 with no fail-open: it crossed a minute boundary (`Retry-After: 60`), which a fixed window allows (up to 2× the limit at a boundary). The same burst repeated with a 1,000 ms timeout and a cold start had no fail-open. A different key straight after was always allowed (separate buckets) | **Just misses** the proposed 10 ms p95 (10.9 ms). Counting is exact across instances. Fix for the overshoot: open the Upstash connection at startup (one `PING` before serving), so the first real check is warm |
| Q11 WAF limits from Kotlin | 2026-10-14, same deployment; rule `spike-keyed` made in the dashboard (the CLI `rules add` left no rule twice), fixed window 10 per 60 s, keyed by `header:x-vercel-rate-limit-key`. **Check time** (200 sequential, 1 instance): p50 10.3 · p95 28.8 · p99 57.3 · max 128.5 ms inside the function; client p95 84 ms. **Bursts** (50 parallel, 11 instances each, 200 ms timeout): allowed 20, 11, 10. Every overshoot was a fail-open with `no answer within 200 ms`: 10 on brand-new instances in burst 1 (confirms the Q10 cause), 1 on a warm instance in burst 2. Counting was otherwise exact. A different key straight after was always allowed. `Retry-After` is always the full window (60), because the API answers only allowed or limited | **No**: p95 28.8 ms, about 3× the proposed 10 ms and 2.6× Upstash. The slow tail also fails open on warm instances |
| Q12 fail open | 2026-10-14 on Vercel, 20 each, 200 ms timeout. `redis-down-timeout` (no answer): 200 × 20, failed open 20/20, time lost p95 201.6 · max 202.0 ms. `redis-down-error` (wrong token, Upstash 401): 200 × 20, failed open 20/20, time lost p50 57.7 · p95 106.7 ms. (Locally: 5/5 at 203–207 ms) | Yes (≤ timeout + 20 ms). Note: a store error costs a full round trip, a dead store the full timeout |

## Not verified locally
- The Docker images weren't built on the development machine (the Docker daemon wasn't running). Vercel builds them anyway. To check locally first: `docker build -f Dockerfile.vercel .`.
- The AOT variant needs JDK 25 (`-XX:AOTCacheOutput`, JEP 514). It is built and trained inside the image; the local JDK is 21.
