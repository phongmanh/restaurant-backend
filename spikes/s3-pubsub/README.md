# S-3 spike: live updates through Ably

**Throwaway.** This code answers the S-3 questions for [ADR-0003](../../docs/sdlc/restaurant-ordering/adr/0003-live-updates.md) (live updates through managed pub/sub, with a PostgreSQL event log for catch-up), and is then deleted.

Status: **deferred by the owner, 2026-10-14.** The spike is ready to run: the script compiles and refuses to start without a key, and nothing has run against Ably yet. To resume, do owner step 1.

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
Not run yet.

| Question | Result | Meets criterion? |
|---|---|---|
| Q1 delay, realistic | | |
| Q2 delay, wide | | |
| Q3 isolation | | |
| Q4 idle | | |
| Q5 cost and region | | |
| Q6 publish call | | |
