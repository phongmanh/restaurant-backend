# Q-19 spike: does Vercel pass a `PATCH` through when the response has no `ETag`?

**Throwaway.** It answers one question for Q-19 (D-1, found by the step 5a system suite) and stays here with its results, as the other spikes do. Nothing here goes into the service.

**D-1 in one line:** on a `PATCH` with `If-Match`, Vercel's edge compares the request's `If-Match` with the `ETag` of the function's *response*, after the function has run. The update always makes a new version, so the two never match: the change is saved and the client gets Vercel's own `412` (`X-Vercel-Error: PRECONDITION_FAILED`). `POST` and `DELETE` pass.

**The fix the owner chose (Q-19 option a, 2026-10-18):** `PATCH` responses stop sending `ETag`; the body's `version` already carries it. This spike checks that Vercel then hands the service's answer to the client unchanged, and doesn't compare against an `ETag` of its own. If it does, a CR takes the header off the seven `PATCH` responses in the contract and the code, before step 6b's load runs.

## How it works
`/probe` answers any method with whatever the query asks for: the status, the `ETag` (or none), `Cache-Control` (`no-store` by default, as the service's `/v1` responses) and a `problem+json` body. Its body echoes the conditional headers it received and says `"source": "service"`, so the script can tell the probe's answer from the edge's. `GET /hits?id=` counts how often the handler ran for a call, so a case also shows whether the "change" was made when the edge replaced the answer (best effort: it counts per instance, and at this load there's one).

## Questions
| # | Question | Cases |
|---|---|---|
| Q1 | Does the spike reproduce D-1 (edge `412`, though the handler ran)? Without that it tests nothing | C1 |
| Q2 | Is the mechanism `If-Match` against the response's `ETag`? (an equal `ETag` should pass) | C2 |
| Q3 | Without an `ETag`, does every answer the service gives a `PATCH` reach the client unchanged: `200`, its own `412` and `428`, `If-Match: *`, `404`, and with no `Cache-Control`? Does the edge add an `ETag` of its own? | F1–F6 |
| Q4 | Does what the service keeps still work: `POST` and `DELETE` with `If-Match`, the menu's `If-None-Match` → `304`? | P1, P2, G1 |
| Q5 | Recorded only: does the edge make its own `304` from a matching `If-None-Match`; does a `PUT` behave like a `PATCH`? (no contract operation needs either today) | G2, X1 |

## Run it
Vercel steps that the auto-mode classifier blocks (production deploy, `vercel env add`) are the **owner's**. Run them from this folder:

```bash
cd spikes/q19-etag
./gradlew test                        # local check
vercel link                           # new project "restaurant-q19-spike" on liam-36c7
vercel git disconnect --yes           # spikes deploy from the CLI only (CLAUDE.md)
vercel env add PORT production        # value 8080
vercel deploy --prod                  # ~3 min (image build); prints the deployment URL
vercel alias rm <the public alias it printed> --yes
```

Then create a **Protection Bypass for Automation** secret (Project Settings → Deployment Protection) and put it in `.env.spike` (ignored by git) as `VERCEL_AUTOMATION_BYPASS_SECRET=…`. Staging is a production deployment behind the same protection, so this matches where D-1 was found. Then (Claude can run this part):

```bash
node --env-file=.env.spike scripts/probe.mjs https://<deployment-url> --repeat 3
```

A dry run against the probe on your machine needs no secret: `PORT=18080 java -jar build/libs/q19-etag-spike-all.jar` and `node scripts/probe.mjs http://localhost:18080`.

When done: `vercel remove restaurant-q19-spike --yes --scope liam-36c7` (or delete the project in the dashboard). The folder stays (owner, 2026-10-18).

## Exit criteria
1. C1 gets the edge's `412` with `X-Vercel-Error: PRECONDITION_FAILED` and the handler ran: D-1 is reproduced.
2. F1–F6 each reach the client as the service's own answer, every time, with no `ETag` added by the edge. Then option (a) works, and the CR follows.
3. P1, P2 and G1 pass.

If 2 fails, option (a) doesn't work: back to the owner with Q-19's options (b) (`POST …/update`) and (c) (CR-001). If 1 fails, the spike doesn't see what staging saw: compare its headers with staging's before concluding anything.

## Results
Dry run, 2026-10-18, against the probe on the developer's machine (no edge): every case answered by the service, all expectations met. It proves the probe and the script, nothing about Vercel.

Vercel run, 2026-10-18, deployment `restaurant-q19-spike-i96rpl74r-liam-36c7.vercel.app` (`dpl_3q7KRS8tkq1ETaWZHrMnR7jipU5T`), production target on the free test account, `sin1`, behind Deployment Protection with the automation bypass (as staging); public alias removed. Three calls a case, every call the same:

Run mvd86z7r against https://restaurant-q19-spike-i96rpl74r-liam-36c7.vercel.app at 2026-10-18T02:48:09.677Z, 3 calls a case.

| Case | Request and the service's answer | Expected | Received | X-Vercel-Error | ETag received | Type | Handler ran | Result |
|---|---|---|---|---|---|---|---|---|
| C1 | D-1 today: PATCH, stale If-Match, 200 with the new ETag | – | edge 412 | PRECONDITION_FAILED | none | text/plain | 1/1/1 | recorded |
| C2 | PATCH, If-Match equal to the response ETag | – | service 200 |  | "2" | application/json | 1/1/1 | recorded |
| F1 | fix: PATCH, If-Match, 200 without ETag | service 200 | edge 412 | PRECONDITION_FAILED | none | text/plain | 1/1/1 | FAIL |
| F2 | fix: PATCH, stale If-Match, the service's 412 problem | service 412 | service 412 |  | none | application/problem+json | 1/1/1 | pass |
| F3 | fix: PATCH without If-Match, the service's 428 problem | service 428 | service 428 |  | none | application/problem+json | 1/1/1 | pass |
| F4 | fix: PATCH, If-Match *, the service's 412 problem | service 412 | service 412 |  | none | application/problem+json | 1/1/1 | pass |
| F5 | fix: PATCH, If-Match, 200 without ETag or Cache-Control | service 200 | edge 412 | PRECONDITION_FAILED | none | text/plain | 1/1/1 | FAIL |
| F6 | fix: PATCH, If-Match, the service's 404 problem | service 404 | service 404 |  | none | application/problem+json | 1/1/1 | pass |
| P1 | POST, stale If-Match, 200 with the new ETag (move, status, close…) | service 200 | service 200 |  | "2" | application/json | 1/1/1 | pass |
| P2 | DELETE, If-Match, 204 | service 204 | service 204 |  | none |  | 1/1/1 | pass |
| G1 | GET, If-None-Match, the service's 304 | service 304 | edge 304 |  | "m1" |  | 1/1/1 | FAIL |
| G2 | GET, If-None-Match equal, the service's 200 with that ETag | – | edge 304 |  | "m1" |  | 1/1/1 | recorded |
| X1 | PUT, stale If-Match, 200 with the new ETag | – | service 200 |  | "2" | application/json | 1/1/1 | recorded |

3 case(s) failed.

**Exit criteria**
1. **Met.** C1 is D-1: the handler ran every time, and the client got the edge's `412` (`X-Vercel-Error: PRECONDITION_FAILED`, `text/plain`).
2. **Not met: option (a) doesn't work.** Without an `ETag` on the response, a `PATCH` with `If-Match` still gets the edge's `412` after the handler ran (F1, F5, with or without `Cache-Control`). The edge treats a missing `ETag` as one that doesn't match. It leaves non-2xx answers alone: the service's own `412`, `428` and `404` pass (F2, F3, F4, F6), and it adds no `ETag` of its own anywhere.
3. **P1 and P2 met; G1 met in effect.** `POST` and `DELETE` with `If-Match` pass. G1's client gets a `304` with the `ETag`, which is what the menu needs, but without the probe's `X-Probe-Id` header, so the script can't tell whether the edge passed the service's `304` (dropping that header) or made its own. G2 shows the edge does make its own `304` when a `200`'s `ETag` matches `If-None-Match`; the handler still runs.

**What it means for Q-19.** The mechanism (Q2) holds: the edge compares `If-Match` with the response's `ETag` on a `2xx` `PATCH` answer, and a missing `ETag` fails the comparison. Nothing the service can put in a `PATCH` `200` passes it honestly (C2 passes only because the response's `ETag` equals the stale `If-Match`). `POST` (P1) and `PUT` (X1) with a stale `If-Match` and a new `ETag` pass, so option (b), `POST …/update`, would get through; the spike shows that directly. Back to the owner with (b) and (c).
