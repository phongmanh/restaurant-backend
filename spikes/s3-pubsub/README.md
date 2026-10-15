# S-3 spike: live updates through Ably

**Throwaway.** This code answers the S-3 questions for [ADR-0003](../../docs/sdlc/restaurant-ordering/adr/0003-live-updates.md) (live updates through managed pub/sub, with a PostgreSQL event log for catch-up), and is then deleted.

Status: **closed 2026-10-15 by the owner** (run 2026-10-14, exit criterion met). ADR-0003 accepted with Ably; R-13 accepted as a free-plan residual risk.

## What ADR-0003 assumes, and S-3 must prove
- A managed pub/sub service holds the long-lived connections, so the Ktor functions on Vercel never do.
- After commit, the app publishes a thin notification `{seq, type, id, version}` to `restaurant.<id>.staff` and `session.<id>`. Clients fetch the actual change from the catch-up endpoint. If a notification is lost, the event log fills the gap, so the transport only has to be fast, not perfect.
- The API mints **subscribe-only tokens for exactly one channel**. A guest at table 5 must not be able to listen to table 6 or to the kitchen.
- Exit criterion (architecture §17): **p95 ≤ 1 s from publish to receive, and per-channel isolation verified.**

## Scope decision: the free plan, extrapolated
The owner chose on 2026-10-13 to run S-3 on **Ably's free plan**: 200 concurrent connections, 200 channels and 500 messages/s, where every publish and every delivery counts as a message. The spike therefore measures delay with up to **190** subscribers, not the 1,000–5,000 in architecture §17. Like the Vercel Hobby account in S-1, **the free plan's limits are not production findings.**

What that means for the result:
- Delay, isolation, token behaviour and idle survival are measured for real. They don't depend on the number of connections.
- Delay at 1,000–5,000 is **inferred** from how delay changes from 50 to 100 to 190 subscribers. A flat curve supports the inference, and a rising one is a warning. Either way it is recorded as a residual risk, to be closed by the Phase 5 load test on a paid plan before launch.
- Cost at 17,500 and 52,500 connections (Q5) comes from Ably's published prices, not from a bill.

## Questions
| # | Question | How |
|---|---|---|
| Q1 | Publish → receive delay with the production channel shape (exit criterion) | `delay --layout realistic` at 50, 100 and 190 subscribers. Staff channels have 4 devices and session channels 2, about 1 restaurant per 19 subscribers. One event publishes to its session channel and its restaurant's staff channel |
| Q2 | Delay at the widest fan-out | `delay --layout wide`: every subscriber on one channel |
| Q3 | Does a one-channel, subscribe-only token isolate? | `isolation`: the token for session A can't attach session B or a staff channel, publish, enter presence, read history, list channels, or get a wider token on re-auth. A bare token stops working when it expires |
| Q4 | Does an idle connection survive the client network's ~40 s idle cut? | `idle --minutes 10`, then one message must still arrive. Ably sends its own heartbeats, but this network has cut idle connections before (S-1) |
| Q5 | Cost at 17,500 and 52,500 connections; region | Ably's pricing and limits pages. `info` shows which endpoint the client reaches |
| Q6 | How long does the app's request spend publishing after commit? | The REST publish call time, measured in Q1. In production this time adds to every state-changing request, unless the outbox sweep does the publishing |

## Design
- Node, no server. The **publisher and the subscribers run in one process**, so each delay is measured on one clock (`performance.now()`), with no clock skew between machines.
- The publisher uses Ably's **REST** API with the API key, the way the Ktor app will. Each subscriber is a **Realtime** (WebSocket) client whose `authCallback` returns a token request for `{"<its channel>": ["subscribe"]}`. In production that callback calls the app's token endpoint. The capability always comes from the server, never from the client's request.
- Where it runs: on the laptop in Vietnam, so a message travels laptop → Ably → laptop. In production the first leg starts in `sin1` (Singapore) and the second ends on a phone in the restaurant. Both are short legs to the nearest Ably region, so the laptop result is a fair estimate. `info` records the REST round trip so the network's share can be subtracted.
- Budget guard: `delay` estimates its message rate and total before it connects. It refuses runs over 450 messages/s or 1M messages unless `--force` is given, so a run can't exhaust the free plan's 6M messages a month.
- Channel names follow ADR-0003 with a run ID: `session.<run>-<n>`, `restaurant.<run>-<n>.staff`.
- Code: `scripts/measure.mjs` (all modes; options are in its header).

## Provider
ADR-0003 names Ably, Pusher Channels and a hosted Centrifugo. On 2026-10-13:
- **Ably**: Free (200 connections), Standard ($29/month + usage, 10k connections), Pro ($399/month + usage, 50k), Enterprise (custom). Usage: $2.50 per million messages and $1.00 per million connection-minutes, with volume discounts down to $0.50 and $0.20. Tokens take a per-channel capability such as `{"session.123": ["subscribe"]}`.
- **Pusher Channels**: priced by tier, not usage. Sandbox (100 connections) free, Business $299 (5k), Plus $899 (20k), Growth Plus $1,199 (30k), then Enterprise. Private channels need an auth signature from the server for each channel.
- **Centrifugo**: no official hosted plan. PRO is a self-hosted licence priced by company size. Self-hosting means running and patching the server ourselves, which is the operational work ADR-0003 chose to avoid.

Ably was chosen for the spike because its free plan is the largest and its tokens are scoped per channel, which is the model ADR-0003 already describes.

## Owner steps (things Claude can't do)
1. Sign up at ably.com (free plan) and create an app named `restaurant-s3-spike`. Under **API Keys**, use the root key (it has every capability, which the spike needs to mint tokens and to publish). Put it in `spikes/s3-pubsub/.env.spike` as one line. Don't paste it into chat:
   ```
   ABLY_API_KEY=xxxxxx.yyyyyy:zzzzzzzz
   ```
   Then tell Claude.
2. After the spike: delete the app, or at least revoke the key.

## How to run
From `spikes/s3-pubsub`:
```
npm install
node --env-file=.env.spike scripts/measure.mjs info
node --env-file=.env.spike scripts/measure.mjs isolation
node --env-file=.env.spike scripts/measure.mjs delay --layout realistic --subs 50  --rate 30 --seconds 120
node --env-file=.env.spike scripts/measure.mjs delay --layout realistic --subs 100 --rate 30 --seconds 120
node --env-file=.env.spike scripts/measure.mjs delay --layout realistic --subs 190 --rate 30 --seconds 120
node --env-file=.env.spike scripts/measure.mjs delay --layout wide --subs 190 --rate 1 --seconds 120
node --env-file=.env.spike scripts/measure.mjs idle --minutes 10
```
About 110,000 Ably messages in total, under 2 % of the free month (a realistic event costs 8: 2 publishes and 6 deliveries). Run `delay` and `idle` in the background, and read only the Markdown block each one prints at the end.

## Results
Run 2026-10-14 from the laptop in Vietnam against Ably's free plan (ably-js 2.29.0). Raw output is in `results/`.

| Question | Result | Meets criterion? |
|---|---|---|
| Q1 delay, realistic | Publish → receive p95 **47 ms** at 50 subscribers, **47 ms** at 100, **49 ms** at 190 (p99 86–119 ms, max 421 ms); 100 % within 1 s. Rerun at 190 after the accounting fix: p95 42 ms, **0 of 18,646 deliveries lost**, 0 duplicates | **Yes** (target p95 ≤ 1 s). The curve is flat from 50 to 190, which supports extrapolating to 1k–5k; not measured above 190 (residual risk R-13) |
| Q2 delay, wide | 190 subscribers on one channel: p95 **97 ms**, p99 119 ms, 22,800 of 22,800 delivered | Yes |
| Q3 isolation | 9 of 9 pass. A subscribe-only token for session A is refused (40160) on another session's channel, on the staff channel, for publish, presence, history and channel listing, and when it asks for another channel on re-auth. A token without a renewal path stops working when it expires | **Yes** |
| Q4 idle | Connected after 10 min with no traffic, 0 state changes; the next message arrived in 141 ms. Ably's own heartbeats survive the network's ~40 s idle cut. One subscriber in the 190 rerun dropped once (80003) and resumed without losing a message | Yes |
| Q5 cost and region | Endpoint `ap-southeast-1` (Singapore, next to `sin1`); REST round trip from the laptop p50 73 ms; connect + token + attach p50 ≈ 220 ms. Prices checked again 2026-10-14, unchanged: Pro $399 + $2.50/M messages + $1/M connection-minutes + $1/M channel-minutes, 50k connections. Launch (17,500) ≈ **$970/month** on Pro; 3× (52,500) is over Pro's 50k, so Enterprise (quote) or fewer connections. Details in cost-estimate.md | Yes (priced) |
| Q6 publish call | REST publish p50 35 ms, p95 43–50 ms, p99 75–138 ms from the laptop | Informational: ≈ 45 ms added to a state-changing request if it publishes inline (see finding 2) |

**Findings for the design**
1. **The first run's "lost" deliveries were refused publishes, not losses.** The free plan allows **50 REST requests/s per account**. The realistic runs sent 60 (30 events × 2 channels), so Ably refused ≈ 14 % of them with 42918 (HTTP 429), as its limits page describes. The first script counted those as lost deliveries. After counting only accepted publishes, nothing was lost. Paid plans allow 362/s (Standard) and 1,447/s (Pro). At the launch peak (≈ 300 messages/s, ≈ 37 events/s at 8 messages each) the app makes ≈ 75 publish requests/s, and ≈ 225 at 3×, so Pro has headroom. Ably's batch publish (one request for both channels of an event) would halve that.
2. **Publish failures must not fail the request.** ADR-0003's outbox already covers a refused publish: the row stays, and the sweep retries. Phase 3 should also retry 42918 once or twice with a short backoff in the request, so a brief burst doesn't wait for the next sweep, and should publish after the response is sent, or at least with a tight timeout, so the ≈ 45 ms publish call doesn't add to the request's latency.
3. **Delay doesn't depend on subscriber count in this range.** p95 47 → 47 → 49 ms from 50 to 190. The only sizeable cost is fan-out on one channel (97 ms at 190 on one channel), which production avoids: a channel has 2 (session) or 4–5 (staff) subscribers.

**Not measured (free plan):** delay at 1,000–5,000 subscribers and at 17,500 connections; behaviour at Pro's rate limits. These are residual risk R-13, to be closed by a load test on a paid plan before launch.

**Owner step 2 is still open:** delete the Ably app `restaurant-s3-spike`, or revoke its key, once S-3 is closed.
