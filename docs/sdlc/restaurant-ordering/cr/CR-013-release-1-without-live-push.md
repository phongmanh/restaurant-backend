# CR-013: Release 1 without Ably; screens poll the event log

| ID | Status | Raised | Raised by | Decides | Baseline affected |
|---|---|---|---|---|---|
| CR-013 | Approved, with option (a): staff 15 s, guests 60 s | 2026-10-18 | Manh Nguyen (product owner), at Phase 5 step 6c: "Ably will be decided later. First release without it" | Manh Nguyen (product owner) | requirements.md v1.6 (KPI-2, NFR-PERF-03, NFR-AVAIL-02, §6); architecture.md v1.6 (§3, §9, risks); ADR-0003; design/realtime.md v1.1; `docs/api/openapi.yaml` 1.1.0 (descriptions only); test-plan.md v0.5 (ST-03, ST-05, ST-10, §9); rtm.md; cost-estimate.md v0.2 |

## Summary
Release 1 ships without a live push channel. Staff and guest screens learn about changes by reading the event log (`GET /v1/{staff,guest}/events?after=<seq>`) on a timer, which realtime §7 already defines as the fallback for when Ably is down. The event log, the catch-up reads and the Ably adapter all stay in the code. A setting turns live push off, and turning it on later is a configuration change plus a CR that restores NFR-PERF-03's 2 s. Which push provider to use, if any, is decided later (ADR-0003 deferred).

## Why
- **Cost.** Ably is the largest line of the cost estimate: about $970 of $1,540–1,740 a month at launch (55–60%), because 17,500 connections rule out its cheaper plan. At 3× growth it needs an Enterprise quote (cost-estimate.md).
- **The fallback is already built and tested.** Every client must already poll when Ably is down (realtime §7), and CI tests the catch-up reads (IT-RT-*). Release 1 makes that mode the normal one.
- **What's lost is speed, not data.** The event log stays the source of truth: a polling screen still gets every change, in order, with no gaps (NFR-REL-03 holds). A change shows within one poll interval instead of within 2 s.
- **The decision can wait.** Live push is a provider integration behind one setting, and adding it back changes no endpoint.

## Proposed change
1. **Requirements (v1.6 → v1.7)**
   - **NFR-PERF-03** gets a release-1 target: "Release 1 has no live push (CR-013): a change reaches staff screens within 15 s at p95 (20 s at p99) and guest screens within 60 s at p95 (75 s at p99) after the write is acknowledged. A screen shows its own change at once. The 2 s target (p95; 5 s p99) applies once live push is added." The intervals are the owner's decision below.
   - **KPI-2:** "within 2 s for 99% of orders" → "within NFR-PERF-03's target for 99% of orders (release 1: 20 s)".
   - **NFR-AVAIL-02:** unchanged in text (it cites NFR-PERF-03). Its SLO query changes: in release 1 it counts failed order placements only, because there's no publish to be late.
   - FR-005, FR-008, FR-011 and FR-015 keep their text: they cite NFR-PERF-03. FR-005's and FR-011's "without reloading" still holds, because the app refreshes itself on the timer.
   - **§6 integrations, live-update transport row:** "None in release 1 (CR-013); clients read the event log on a timer. Provider decided later (ADR-0003)".
2. **ADR-0003:** status "Accepted; deferred for release 1 (CR-013)". The event log and outbox design stand. Choosing Ably, or another provider, comes back as a decision before the release that adds live push.
3. **Architecture (v1.6 → v1.7)**
   - §3 technology table, live-updates row: "PostgreSQL event log, read by clients on a timer (release 1, CR-013); managed pub/sub later (ADR-0003, deferred)".
   - §9 capacity: realtime §7's "Ably down for everyone" load becomes the normal peak. 2,500 staff screens every 15 s ≈ 167 req/s and 15,000 guest devices every 60 s ≈ 250 req/s, so the peak is ≈ 600 req/s instead of ≈ 310 (the mix's normal catch-up share is replaced by the polls). The pub/sub line goes.
   - Risks: R-13 (Ably beyond 190 subscribers) closes for release 1. New R-19, below.
4. **design/realtime.md (v1.1 → v1.2)**
   - §2 and §4.2: a setting `LIVE_PUSH` (`off` | `ably`). With `off`, `EventLog.append` writes the event but no outbox row, so there's nothing to publish, no sweep work and no publish-failure metrics. With `ably`, everything works as today, and `ABLY_API_KEY` is required.
   - The setting has no default outside dev. Production and staging must say `off` or `ably`, so a missing key can never quietly switch push off. Today a missing key outside dev stops the service from starting; that stays true for `ably`.
   - §7 client contract, a new "Release 1" rule: don't subscribe; poll `GET …/events?after=<cursor>` every 15 s (staff) or 60 s (guests), with ±20 % jitter, as in the "live delivery is down" mode. Also catch up at once after the screen's own write and when the app comes to the foreground. Clients written to the full §7 rules still work, because a `503` from the token endpoint already sends them to polling.
   - §8: with `off`, both token routes answer `503 service_unavailable` (a shared code, so no contract change). Release-1 clients don't call them.
   - §11 observability: the order-flow SLO and the late-publish and outbox alerts apply only with `ably`. With `off` the SLO counts placements alone.
5. **Contract (1.1.0 → 1.1.1, descriptions only):** the two `realtime-token` operations and the `realtime` tag description say "Not used in release 1 (CR-013): answers `503 service_unavailable` while live push is off". No path, schema or status changes, so `oasdiff` reports nothing.
6. **Test plan (v0.5 → v0.6)**
   - **ST-03** re-scoped: polling screens under the smoke load. Each screen times when a change first shows in its catch-up read, against the write's response; target: the new NFR-PERF-03. The 2 s run waits for live push (deferred, not residual).
   - **ST-10** re-scoped: the "Ably off" half goes, because there is nothing to turn off. Screens that pause at random (a phone asleep, a closed tab), then catch up, must end with exactly the database's event list: no gaps, no duplicates (NFR-REL-03).
   - **ST-05:** "17,500 subscribers" becomes the ≈ 600 req/s polling peak. It stays residual (R-09, R-16).
   - §9: step 6 drops "Ably credentials off". The owner's step "turning the Ably key off and on" goes.
   - **System suite:** the two scenarios that subscribe through Ably (KitchenAndBillTest, GuestOrderingTest: FR-005, FR-011) poll instead while staging runs `off`. `Live.kt` stays for when push returns.
7. **RTM:** FR-005, FR-011 and NFR-PERF-03 trace to CR-013 and the re-scoped ST-03.
8. **Cost estimate (v0.2 → v0.3):** the Ably line is $0 in release 1. Vercel's request line rises with the polls (≈ 600 req/s at peak instead of ≈ 310). Redone on approval; TBD-11 stays deferred.

## Impact
| Item | Change | When |
|---|---|---|
| requirements.md | v1.7: NFR-PERF-03, KPI-2, §6 row; version-history entry citing CR-013 | On approval |
| ADR-0003 | Status note: deferred for release 1 | On approval |
| architecture.md | v1.7: §3, §9, risks R-13 and R-19 | On approval |
| design/realtime.md | v1.2: §2, §4.2, §7, §8, §11 | On approval |
| `docs/api/openapi.yaml` | 1.1.1: two operation descriptions and the tag; lint and `contract-breaking.sh` pass | On approval, before the code |
| Code | `LIVE_PUSH` in `AppConfig` / `RealtimeSettings` (no default outside dev; `ably` requires the key); `OutboxEventLog` skips the outbox row with `off`; config and IT-RT tests for both modes | One PR, after the contract |
| Observability | The order-flow SLO rule and its promtool tests handle `off` (placements only) | Same PR |
| System suite | FR-005 / FR-011 scenarios poll when staging runs `off` | Same PR |
| Staging (owner steps) | Set `LIVE_PUSH=off`, remove `ABLY_API_KEY` from the Vercel project, deploy; then delete or keep the Ably app `restaurant-staging` (and the S-3 spike app, still open) | After the PR |
| test-plan.md, rtm.md | v0.6: ST-03, ST-05, ST-10, §9; RTM rows | On approval |
| cost-estimate.md | v0.3: Ably $0, Vercel requests up | On approval |
| Phase 5 step 6c | Runs the re-scoped ST-03 and ST-10 (polling, 55 screens on restaurant L, one run under the smoke load) | After staging runs `off` |

## What stays the same
- The event log, its gap-free numbering, the catch-up endpoints and their rate limits (`events.read`: 240 a minute for staff, 120 for a guest session; a 15 s or 60 s poll stays far below).
- NFR-REL-03 and its guarantee: no lost or repeated changes.
- Every `/v1` path and schema. The token routes stay in the contract for when push returns.
- The Ably adapter, `Live.kt` and `AblyLiveTest` (it runs only when `ABLY_API_KEY` is set in the shell).

## Risks this adds
- **R-19 (new):** if polling at the release-1 intervals is too slow for the kitchen in practice (a new order up to ~18 s late), then service feels sluggish at busy times. Mitigation: the pilot measures it; the interval is a client setting, and live push is a configuration change once a provider is chosen.
- **Load and cost move to Vercel.** The polling peak (≈ 600 req/s) is about twice the request mix the capacity model was built on. The 2× headroom test of realtime §7 (≈ 1,200 req/s) isn't run on free plans, so it's carried in R-16.
- **Battery and data on guests' phones:** one small request a minute while the app is open. Negligible, and the app doesn't poll in the background.

## Owner decisions needed
1. **Poll intervals, and with them the release-1 NFR-PERF-03:**
   - **(a) Staff 15 s, guests 60 s** (recommended): the intervals realtime §7 already sizes, with a peak of ≈ 600 req/s.
   - (b) Staff 5 s, guests 30 s: about 3× fresher for the kitchen, with a peak of ≈ 1,180 req/s, about 4× the current mix.
   - (c) Other values. Every halving of an interval doubles that group's requests.
2. **Approve, reject or defer this CR.**

## Cost and schedule
- **Cost:** saves ≈ $970 a month at launch (the Ably line). Vercel's request cost rises by an amount the v0.3 estimate will give.
- **Schedule:** documents within the session; the code PR is small (one setting, one branch in `OutboxEventLog`, tests). Step 6c then runs with polling screens only.

## Decision
| Date | Decision (Approved / Rejected / Deferred) | By | Notes |
|---|---|---|---|
| 2026-10-18 | Approved, with option (a): staff poll every 15 s, guests every 60 s | Manh Nguyen (product owner) | Documents applied the same day: requirements v1.7, architecture v1.7, ADR-0003, design/realtime.md v1.2, contract 1.1.1, test-plan v0.6, RTM, cost-estimate v0.3. Two figures refined while applying: the polling peak is ≈ 640 req/s (310 − 85 + 417; this CR and realtime §7 round it to ≈ 600), and the net saving is ≈ $440–510 a month at launch, because ≈ $460–520 of the Ably line moves to Vercel as polls (cost-estimate §1.1). Code follows in one PR |
