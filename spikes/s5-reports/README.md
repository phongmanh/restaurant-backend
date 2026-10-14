# S-5 spike: the sales report on a year of data

**Throwaway.** This code answers the S-5 questions for NFR-PERF-04 and FR-050 (daily sales reports) and the `daily_sales` design in [architecture.md](../../docs/sdlc/restaurant-ordering/architecture.md) §4 and §9, and is then deleted.

Status: **closed 2026-10-14 by the owner. The exit criterion is met** (31-day report p95 10.4 ms inside PostgreSQL, 65 ms on the client, 76 ms on a cold cache; target 3 s). The four design points below were accepted with it and are in architecture §9. The synthetic data (schema `s5`) was dropped from the S-2 database on 2026-10-14; `setup` recreates it in ≈ 30 s.

## What the architecture assumes, and S-5 must prove
- A manager's report (FR-050) reads **daily aggregates per restaurant** (`daily_sales`), not the order rows. The aggregates are written when a bill closes and rebuilt from the raw rows every night.
- Days are the restaurant's local days (Asia/Ho_Chi_Minh), not UTC days.
- Exit criterion (architecture §17): **NFR-PERF-04 met: a 31-day report for a restaurant with 300 bills a day returns in ≤ 3 s at p95**, on about a year of data.

## Questions
| # | Question | How |
|---|---|---|
| Q1 | 31-day report from `daily_sales`: how long (the exit criterion)? | `report` and `explain --source agg --days 31` |
| Q2 | Is `daily_sales` needed at all? The same report computed straight from bills, orders and lines | `report` and `explain --source raw --days 31` |
| Q3 | The longest range FR-050 allows (93 days), both ways | `--days 93` |
| Q4 | The first report after the database was idle (empty cache) | `cold`: wait until Neon's free compute suspends (5 min idle), then report |
| Q5 | What does keeping `daily_sales` current cost? | `close` (closing a bill with and without the upsert, 1 and 10 at once), `reconcile` (rebuild one day), and `setup` (rebuild a whole year) |
| Q6 | Is the report from `daily_sales` exactly the report from the raw rows, including bills that close after local midnight? | `check` over random ranges; `reconcile` reports whether the day matched before it rebuilt it |
| Q7 | How much storage does a year take? | `setup` prints table sizes, bytes per order and per restaurant-day |

## Design
- **Database:** the S-2 Neon project (free plan, Singapore, PostgreSQL 18), in its own schema `s5`, so S-2's tables are left alone. It uses S-2's env file (`../s2-postgres/.env.spike`) as it is: no new account, key or owner step.
- **Data** (`sql/generate.sql`), generated inside PostgreSQL with a fixed seed: 365 local days ending 2026-10-13 for a **large restaurant** (40 tables, ≈ 270 bills on weekdays and ≈ 380 at weekends, so ≈ 300 a day, the NFR-PERF-04 restaurant) and a small one (≈ 30 a day). About 2 orders per bill and 3 lines per order (ASSUMPTION-9). 2% of orders cancelled, 1% rejected, 1% of lines rejected. Lunch and dinner peaks; dinners can close after midnight. Rows are written in time order with UUID v7 IDs, as production would write them.
- **Tables** (`sql/schema.sql`): `table_sessions` (a bill), `orders`, `order_lines`, `menu_items`, and four aggregate tables, one per FR-050 breakdown: `daily_sales` (bills, orders, gross per day), `daily_sales_payment`, `daily_sales_table`, `daily_sales_item`. RLS on every table as in ADR-0005 (`sql/finish.sql`); every measurement runs as `app` through the pooler with the tenant set and a 10 s statement timeout for reports (architecture §10).
- **The report** is one SQL query that returns one JSON document: per local day the totals, the payment methods, the tables, and the top 10 items by quantity and by revenue. `agg` reads the four aggregate tables; `raw` computes the same rows from the bills, orders and lines. Both share the final query, so `check` can compare them byte for byte.
- **Closing a bill** (FR-013) runs the session update and one upsert into the four aggregate tables in one transaction. The script sends it as one round trip, so row locks are held only as long as the database needs them, as they would be from a function next to the database.
- **Run from this Mac, not from a Vercel function.** Every client time includes one round trip to Singapore (≈ 35–45 ms from here; ≈ 1–4 ms from `sin1` in S-2). `explain` gives the time inside PostgreSQL, which doesn't depend on where the client is.
- Code: `scripts/s5.mjs` (all modes and the SQL for the report, the close and the rebuild), `sql/` (schema, generator, indexes and RLS).

## Owner steps
None. The spike reuses the S-2 database and its env file.

## How to run
From `spikes/s5-reports` (`npm install` once):
```
R() { node --env-file=../s2-postgres/.env.spike --no-warnings scripts/s5.mjs "$@"; }
R setup                                   # drop and rebuild schema s5, ≈ 30 s
R ping
R check; R check --days 93 --n 10         # Q6
R report --source agg --days 31; R explain --source agg --days 31      # Q1, then raw and 93 days for Q2, Q3
R close --no-agg; R close; R close --c 10 --n 200                      # Q5
R reconcile                               # Q5, Q6: rebuild today; says whether it matched
R cold --source raw --rounds 2            # Q4, ≈ 6 min idle per round; run in the background
```
`close --no-agg` closes bills without updating `daily_sales` on purpose, so the next `reconcile` of today reports a mismatch and repairs it. Run `setup` again to start clean. `--no-warnings` hides pg's notice that `sslmode=require` is treated as `verify-full`, which is the stricter mode.

## Results
Run 2026-10-14 against Neon Singapore (free plan, PostgreSQL 18) from this Mac; ping p50 33–47 ms. Outputs are pasted below the table.

| # | Result | Meets? |
|---|---|---|
| Q1 | **31 days from `daily_sales`: p95 10.4 ms inside PostgreSQL** (p50 9.5 ms, ≈ 520 cached pages); on the client p95 64.9 ms, of which ≈ 33 ms is the round trip. Response ≈ 164 KiB of JSON | **Yes**, ≈ 50× under the 3 s target even from here |
| Q2 | Straight from the raw rows: 31 days p95 582 ms inside PostgreSQL (p50 357 ms), client p95 516 ms. It also meets 3 s **here**, but it touches ≈ 75,700 pages (≈ 590 MB of page visits) per report because it walks every bill, order and line of the month. In production those tables hold 500 restaurants (≈ 25 GB a year, Q7), so those pages won't all be in memory, and every page that isn't is a storage read (see Q4) | Yes here; **not safe at production size**: keep `daily_sales` |
| Q3 | 93 days: `daily_sales` p95 131 ms inside PostgreSQL (p50 27 ms), client p95 80 ms. Raw p95 1.16 s inside PostgreSQL, client p95 1.5 s; the planner switched to reading the **whole** tables (13,700 pages), which only works because they hold 2 restaurants. At production size that is a scan of tens of GB | `daily_sales`: yes. Raw: no at production size |
| Q4 | After 6 min idle (Neon's free compute suspends and comes back with an empty cache). Waking it: connect + first `SELECT 1` 1.1–1.8 s from here (a first number for R-11, which was never measured). **`daily_sales` cold: 76 ms** inside PostgreSQL (515 pages read from storage), then 10 ms warm. **Raw cold: 1.2 s and 2.4 s** (≈ 4,000 pages read from storage each time), then 340–360 ms warm. Here the month's rows sit on ≈ 4,000 pages because only 2 restaurants share the tables. With 500 restaurants writing at the same time, each restaurant's rows are spread out, so a month of one restaurant's ≈ 84,000 bills, orders and lines could each sit on a different page: ≈ 20× more storage reads, roughly 25–50 s cold (an estimate, not measured), over the 10 s report timeout | `daily_sales`: **yes**, even cold. Raw: no at production size |
| Q5 | Closing a bill: + ≈ 1.5–2 ms at p50 and + 3–6 ms at p95 for the upsert (without: p50 0.3 ms, p95 1.3 ms; with: p50 1.8–2.2 ms, p95 3.9–7.0 ms). 10 bills of one restaurant closing at the same moment: p50 5.4 ms, p95 22.5 ms (they queue for the same day's rows; a restaurant closes ≈ 300 bills a day, so this is rare). No errors or deadlocks in 300 concurrent closes (100 before the upserts were put in key order, 200 after; the earlier run's p95 of 83 ms was not repeated). Rebuilding one day from the raw rows: ≈ 50–60 ms; the whole year of both restaurants: 3.1 s | Yes: cheap on the write path; a nightly rebuild of 500 restaurants is ≈ 500 × 0.1 s |
| Q6 | The `daily_sales` report equals the raw report for **38 of 38** ranges (31 and 93 days, random and the latest). After 240 closes with the upsert (200 of them 10 at a time), the rebuild of today found it **already matching**. After the `--no-agg` closes it found a mismatch and repaired it, which is the nightly reconciliation's job. 3,075 bills of the large restaurant close after local midnight and count on the next local day | Yes |
| Q7 | Bills + orders + lines: **738 bytes per order** with indexes → ≈ 25 GB a year at ASSUMPTION-9's 100k orders a day (architecture §6 says ≈ 50 GB a year with the event log and other tables: consistent). `daily_sales`: ≈ 19.5 KB per restaurant-day for the large restaurant, 85% of it the per-item table → ≤ 3.3 GB a year if all 500 restaurants were this large. 1 year of both restaurants: 195 MB, within the free plan's 0.5 GB | Fits; the item table is the one to watch |

What this means for the design (accepted by the owner 2026-10-14):
1. **Keep `daily_sales`.** Reading it is ≈ 50× under the target and doesn't depend on how big the order tables grow. Computing the report from the raw rows meets the target only because this database holds 2 restaurants.
2. **Write it in the close transaction, in key order.** It adds about 2 ms per bill. Upserting rows sorted by their key means two bills closing at once take the row locks in the same order, so they wait for each other instead of deadlocking.
3. **Rebuild each restaurant's previous day every night** by deleting the day and inserting it again from the raw rows. Running it twice gives the same result, and it repairs anything the close path missed (Q6 showed both).
4. **A bill counts on the local day it was closed (paid)**, even when the guests sat down the day before. The requirements don't say which timestamp decides the day; this spike assumed the closing time.

### Raw outputs
```
### setup 2026-10-14T07:22:49.202Z (last day 2026-10-13)
Timings: schema 158 ms, generate 24.9 s, fill daily_sales for the whole year 3130 ms, indexes + RLS + ANALYZE 921 ms
| Large restaurant | 110130 bills | 220165 orders | 660785 lines | 366 days | 2025-10-14 .. 2026-10-14 | 301 bills/day |
| Small restaurant | 10986 | 21993 | 65833 | 366 | 2025-10-14 .. 2026-10-14 | 30 |
order_lines 115.9 MB (heap 81.1) · orders 34.3 MB (21.5) · table_sessions 20.2 MB (11.8) · daily_sales_item 11.2 MB (5.9)
daily_sales_table 1.9 MB · daily_sales_payment 0.3 MB · daily_sales 0.1 MB · database 195.3 MB
Raw rows: 738 bytes per order → 25.1 GB a year at 100k orders/day. daily_sales: 19506 bytes per restaurant-day → 3.3 GB a year at 500 restaurants.
(366 days: dinners on 2026-10-13 that close after midnight fall on 2026-10-14.)

### ping: SELECT 1 round trip p50 34.1 ms, p95 36.7 ms
### check --days 31: equal for 21 of 21 ranges. 3075 bills close after local midnight
### check --days 93: equal for 11 of 11 ranges
### report agg 31: client p50 50.1, p95 64.9, max 713.9 ms (ping p50 33.2) · explain: exec p50 9.5, p95 10.4 ms; 517 pages hit, 0 read
### report raw 31: client p50 404.1, p95 516.0, max 791.2 ms (ping p50 41.8) · explain: exec p50 357.3, p95 581.7 ms; 75660 pages hit, 0 read
### report agg 93: client p50 73.5, p95 80.1, max 92.9 ms (ping p50 35.4) · explain: exec p50 26.7, p95 130.7 ms; 622 pages hit
### report raw 93: client p50 1056.3, p95 1500.5, max 1613.8 ms (ping p50 42.5) · explain: exec p50 1058.6, p95 1156.1 ms; 13662 pages hit
### close without upsert c=1 (n=40): client p50 47.7, p95 48.6 ms; minus ping p50 47.4 → p50 0.3, p95 1.3 ms
### close with upsert c=1 (n=40): client p50 44.0, p95 46.1 ms; minus ping p50 42.3 → p50 1.8, p95 3.9 ms
### close without upsert c=10 (n=100): minus ping → p50 2.4, p95 13.4 ms
### close with upsert c=10 (n=100, before key-ordered upserts): minus ping → p50 16.9, p95 83.2, max 491.5 ms (client)
### reconcile 2026-10-14 (after the --no-agg closes): did not match before, matches after; insert from raw 54 ms
### reconcile 2026-10-01: matched before and after; insert from raw 49 ms
### close with upsert c=10 (n=200, key-ordered): minus ping → p50 5.4, p95 22.5 ms; max 132.6 ms (client)
### close with upsert c=1 (n=40, key-ordered): minus ping → p50 2.2, p95 7.0 ms
### reconcile 2026-10-14 (after 240 closes with the upsert): matched before, matches after; insert from raw 58 ms

### cold, 360 s idle before each round (results/cold.md)
| Source | Round | Connect + first SELECT 1 | Report in PostgreSQL, cold | Pages read from storage | Same report again, warm | Pages read |
|---|---|---|---|---|---|---|
| raw | 1 | 1770 ms | 1242 ms | 3995 | 362 ms | 0 |
| raw | 2 | 1121 ms | 2436 ms | 3981 | 342 ms | 0 |
| agg | 1 | 1738 ms | 76 ms | 515 | 10 ms | 0 |
```
