# Monthly cost estimate (TBD-11)

| Version | Date | Status | Inputs |
|---|---|---|---|
| 0.1 | 2026-10-14 | Draft for G2. The pub/sub line is a placeholder until S-3 | Architecture §9 capacity model, S-1, S-2, S-4 results, vendor price pages checked 2026-10-14 |

All prices are USD per month, list price, before tax. This is a paper estimate: it answers "what would a real launch cost?" for the G2 capacity model. The project itself stays on free plans (decision 2026-10-14), so nothing here means buying a plan.

## 1. Summary

| | Launch (500 restaurants) | 3× growth (1,500 restaurants) |
|---|---|---|
| Vercel Pro (functions, CDN, firewall) | 437 – 562 | 1,278 – 1,652 |
| Neon Launch (PostgreSQL) | 101 – 179 | 255 – 528 |
| Upstash Redis (rate limits) | 10 | 10 |
| Ably (pub/sub), **placeholder until S-3** | ≈ 970 | ≈ 2,100 + Enterprise base (quote) |
| Grafana Cloud Pro (observability, TBD-12) | 19 | 42 |
| Other (off-site backup copy, domain) | 3 | 3 |
| **Total without menu images** | **≈ 1,540 – 1,740** | **≈ 3,700 – 4,350** |
| Menu images (FR-022, if Must; TBD-5) | + 473 | + 1,419 |
| **Total with menu images** | **≈ 2,010 – 2,220** | **≈ 5,120 – 5,770** |
| Per restaurant, without images | ≈ 3.1 – 3.5 | ≈ 2.5 – 2.9 |

The ranges come from two things not yet measured: how long Vercel keeps instances alive (memory billing) and whether the database runs at a fixed size or autoscales.

What drives the bill, largest first:
1. **Pub/sub (≈ 55–60%).** 17,500 concurrent connections at launch rule out Ably's Standard plan (10k), so the Pro base fee ($399) applies, plus connection minutes and messages. At 3× (52,500) it needs Enterprise pricing. S-3 replaces this placeholder.
2. **Request count at the CDN.** Vercel bills every request three times: as a CDN request ($2.60/M in `sin1`), as an invocation ($0.60/M) and as origin transfer. The event catch-up endpoint (75 req/s) is the largest single group.
3. **Menu images**, if FR-022 is a Must. ≈ 90 M image requests and ≈ 4.5 TB a month at launch.
4. **The database**, which is cheap next to these: Neon bills compute by the hour, and the data is small.

## 2. Today, on free plans: $0

| Service | Free plan limit | Launch needs | NFR that the free plan can't meet |
|---|---|---|---|
| Vercel Hobby | Non-commercial only; 1 M invocations, 4 CPU-hours, 360 GB-hours a month | 76 M invocations | All (commercial use isn't allowed); cold starts (S-1) |
| Neon Free | 100 CU-hours and 1 GB per project; 6 hours of history | ≈ 730 CU-hours, 50 GB | NFR-REL-01 (7-day point-in-time restore) |
| Upstash Free | 500 K commands a month | ≈ 76 M | Rate limits (ADR-0008) |
| Ably Free | 200 connections, 6 M messages a month | 17,500 connections, 90 M messages | NFR-PERF-03, NFR-SCAL-01 |
| Grafana Cloud Free | 14 days of metrics | 28-day SLO windows | SLO reporting (requirements §4) |

These are residual risks for a real launch, not to-dos.

## 3. Assumptions

| ID | Assumption | Source |
|---|---|---|
| C-1 | Daily traffic = peak req/s × 10,000 s. 100k orders a day at a peak of 10 orders/s means ≈ 2.8 hours of peak-equivalent traffic a day | ASSUMPTION-1, ASSUMPTION-9, architecture §9 |
| C-2 | 93 M CDN requests and 76 M function invocations a month at launch (310 and 255 req/s × 10,000 s × 30 days) | Architecture §9 |
| C-3 | 5 ms of CPU per request on average | Not measured. S-1 measured CPU speed only; S-4 sign-ins (≈ 80 ms) are too rare to matter |
| C-4 | 2 GB memory per instance; a request keeps its instance billed for ≈ 50 ms; Pro keeps one production instance warm all month | S-1 Q7, S-2 (transactions ≈ 17–29 ms), decision 2026-10-13 |
| C-5 | Memory, upper bound: ≈ 8 instances alive 12 hours a day | S-1 Q4 and S-2 Q7 (50 concurrent requests used 9–25 instances) |
| C-6 | 3 KB per API request (request + response, with headers) | Estimate |
| C-7 | Database: 2 CU (2 vCPU, 8 GB) at peak, 0.5 CU at night, never suspended; 50 GB at year end; ≈ 1.5 GB of WAL a day | Architecture §6 and §9, S-2 |
| C-8 | Pub/sub: 100k guest device sessions a day, each connected ≈ 60 min; 5 staff devices per restaurant for 12 h; a channel per table session and 2 per restaurant; 300 billed messages/s at peak | ASSUMPTION-1, ADR-0003, architecture §9 |
| C-9 | Images: each guest device loads ≈ 30 thumbnails of ≈ 50 KB once per session | Estimate; FR-022 |
| C-10 | Logs ≈ 400 bytes per request; 10% of requests traced at ≈ 1.5 KB | Estimate; ADR-0009 |

3× growth multiplies traffic, connections, data and database peak size by 3.

## 4. Line items at launch

### Vercel Pro, region `sin1`
| Item | Formula | $/month |
|---|---|---|
| Developer seat | 1 × $20 | 20 |
| Usage credit included with Pro | | −20 |
| Invocations | 76 M × $0.60/M | 46 |
| Active CPU | 76 M × 5 ms = 106 CPU-hours × $0.160 | 17 |
| Provisioned memory | 76 M × 50 ms × 2 GB = 2,120 GB-h, up to 5,760 GB-h (C-5); + 1,460 GB-h for the warm instance; × $0.0133 | 48 – 96 |
| CDN requests | (93 M − 1 M included) × $2.60/M | 239 |
| Fast Data Transfer | 93 M × 3 KB = 279 GB, inside the 1 TB included | 0 |
| Fast Origin Transfer | 76 M × 3 KB = 229 GB × $0.27/GB | 62 |
| Firewall rate-limit rules | QR open, place order, sign-in: 7.8 M × $0.65/M | 5 |
| Firewall OWASP managed rules | Write requests only (19.5 M) to every request (93 M) × $1.04/M | 20 – 97 |

Flat Rate CDN (a fixed tier instead of per-request billing) costs $300 for up to 150 M requests: more than the $239 on-demand price at launch, and too small at 3×. Stay on on-demand pricing.

### Neon, Launch plan, Singapore
| Item | Formula | $/month |
|---|---|---|
| Compute | Autoscaling 0.5–2 CU: (12 h × 1.5 + 12 h × 0.5) × 30 = 720 CU-h; fixed 2 CU: 1,460 CU-h; × $0.106 | 76 – 155 |
| Storage | 50 GB × $0.35 | 18 |
| History for 7-day restore | 10.5 GB × $0.20 | 2 |
| Staging database (scales to zero) | ≈ 50 CU-h | 5 |
| Egress | ≈ 240 GB, inside the 500 GB included | 0 |

The Launch plan keeps up to 7 days of history, which is exactly NFR-REL-01's 7 days. Scale ($0.222/CU-h, up to 30 days) roughly doubles compute. Neon has no hot standby: storage is replicated across zones, and a failed compute is restarted. Check that this restart time fits RTO ≤ 1 h before G2.

A daily `pg_dump` off-site (architecture §6) leaves Neon as egress. A full 10 GB dump every day would use 300 of the 500 included GB, so prefer weekly full dumps plus point-in-time restore.

### Upstash Redis
Fixed 250 MB plan: $10. Commands aren't billed on fixed plans (limit 10,000 a second). Pay-as-you-go would cost ≈ $150 at ≈ 76 M commands. The price page doesn't list regions: confirm Singapore in the console.

### Ably, placeholder until S-3
| Item | Formula | $/month |
|---|---|---|
| Pro base (up to 50k connections) | | 399 |
| Connection minutes | 234 M × $1/M | 234 |
| Channel minutes | 112 M × $1/M | 112 |
| Messages | 90 M × $2.50/M | 225 |

Ably lists volume discounts down to $0.50/M for messages and $0.20/M for minutes, which a quote could bring in at 3×.

### Grafana Cloud Pro (if chosen for TBD-12)
$19 platform fee covers 10k metric series, 50 GB of logs and 50 GB of traces. Launch uses ≈ 31 GB of logs and 11 GB of traces. Pro rather than Free, because SLOs are measured over 28 days. The app pushes OTLP itself (ADR-0009), so Vercel log drains ($0.50/GB) aren't needed.

### Menu images (FR-022)
90 M image requests × $2.60/M = $234, plus 4.5 TB of Blob data transfer × $0.053/GB = $238. Storage is under $1. This line depends on TBD-5 (Must or Should) and on image size. A store with no egress fee (such as Cloudflare R2) could remove most of it; that is a design choice for Phase 3, not priced here.

## 5. Not included
- Tax, and any Ably or Vercel volume discount.
- People: on-call, support (R-07).
- GitHub: CI fits the free private-repo minutes at ≈ 100 runs a month.
- Email: none needed. Invitation and reset links are passed on by hand (ADR-0011).

## 6. What changes the estimate
| When | Update |
|---|---|
| S-3 runs | Replace the Ably lines with the chosen provider's price at 17,500 and 52,500 connections |
| TBD-5 is decided | Keep or drop the menu-image line |
| TBD-12 is decided | Replace the Grafana line |
| TBD-10 is decided | Regions may change; prices are regional at Vercel |
| Year 2 onward (TBD-4 working values, 2026-10-14) | Database storage grows to ≈ 100 GB by the end of year 2 (≈ $35/month at $0.35/GB) and stays there, because orders and bills older than 2 years move to an archive in object storage. Compressed, the archive is a few GB a year: under $2/month even after 10 years. If legal requires a different period, scale these figures |
| Phase 5 load test | Replace C-3 to C-5 (CPU and memory per request) with measured values |
