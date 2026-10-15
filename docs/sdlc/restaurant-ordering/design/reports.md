# Feature design: reports

| Field | Value |
|---|---|
| Version | 0.2 Draft: the four §13 questions decided by the owner on 2026-10-15; the whole design is approved at G3 |
| Owner decisions | §13, all four accepted 2026-10-15 |
| Change request | [CR-007](../cr/CR-007-csv-export-out-of-release-1.md), approved 2026-10-15: FR-051 Won't in release 1 |
| Date | 2026-10-15 |
| Package | `reports` (architecture §4) |
| Requirements | FR-050 daily sales report, FR-051 CSV export (Could; §13 Q3); NFR-PERF-04 (31 days ≤ 3 s p95), FR-042, NFR-SEC-02; ASSUMPTION-11 (a bill counts on the local day it closed) |
| Builds on | architecture.md v1.3 §4, §6, §9 (Reports), §10 (jobs); S-5 (aggregate tables, key-ordered upserts, nightly rebuild; four design points accepted 2026-10-14); [orders](orders.md) 0.2 §7 (`SalesLedger`, `SalesSource`, corrections); [restaurants](restaurants.md) 0.2 §6 (managers read reports while suspended); [realtime](realtime.md) 0.2 §10 (cron limits on Hobby) |

## 1. Scope
**In:**
- The four daily aggregate tables S-5 measured, adjusted for bill corrections (orders §7.2).
- `SalesLedger`: the port `orders` calls when a bill closes or is corrected, which keeps today's figures current.
- The nightly rebuild that checks and repairs the aggregates from the raw rows.
- The report endpoint for managers.

**Out:**
- FR-051 CSV export: not in release 1 (§13 Q3, CR-007).
- Archiving orders older than 2 years (architecture §6). Reports never need the raw rows of a day that old: they read the aggregates, which are kept.

## 2. How a report is built
**In plain words:** adding up a month of bills line by line every time a manager opens the report is slow (S-5: 0.6–2.4 s for one restaurant, and it gets worse as the database grows). Instead, each bill adds itself to its day's totals at the moment it's closed, the way a cashier keeps a running tally. The report then reads 31 small rows instead of thousands of lines (S-5: 10 ms). Every night a job adds the previous day up again from the bills themselves and corrects the tally if it differs, so a missed update can't stay wrong.

- **Which day:** the bill's `business_date`, the restaurant's local date when it was closed (orders §7.1, ASSUMPTION-11). A correction counts on the local date it was made (orders §7.2). Both dates are stored when the money changes hands, so a later change of the restaurant's time zone doesn't move past days.
- **What counts** (FR-050): closed bills; their orders that aren't `cancelled` or `rejected`; their lines that aren't `rejected`. Bills closed with a total of 0 don't count (§13 Q2).

## 3. Data model
Tenant tables, forced RLS, keys led by `restaurant_id` (ADR-0005). Kept while the restaurant exists (architecture §6), including after the orders behind them are archived.

```sql
CREATE TABLE daily_sales (
  restaurant_id       uuid NOT NULL,
  sales_date          date NOT NULL,                        -- local date (§2)
  sessions            integer NOT NULL DEFAULT 0,           -- bills closed with a payment (§13 Q2)
  orders              integer NOT NULL DEFAULT 0,
  gross               bigint NOT NULL DEFAULT 0,            -- sum of those bills
  corrections         bigint NOT NULL DEFAULT 0,            -- signed sum of corrections made that day
  correction_count    integer NOT NULL DEFAULT 0,
  PRIMARY KEY (restaurant_id, sales_date)
);

CREATE TABLE daily_sales_payment (
  restaurant_id   uuid NOT NULL,
  sales_date      date NOT NULL,
  method          text NOT NULL CHECK (method IN ('cash', 'card', 'bank_transfer')),
  sessions        integer NOT NULL DEFAULT 0,
  amount          bigint NOT NULL DEFAULT 0,                -- bills paid with this method
  corrections     bigint NOT NULL DEFAULT 0,                -- corrections booked to this method
  PRIMARY KEY (restaurant_id, sales_date, method)
);

CREATE TABLE daily_sales_table (
  restaurant_id   uuid NOT NULL,
  sales_date      date NOT NULL,
  table_id        uuid NOT NULL,
  table_number    text NOT NULL,                            -- as it was at the last close that day
  sessions        integer NOT NULL,
  gross           bigint NOT NULL,
  PRIMARY KEY (restaurant_id, sales_date, table_id)
);

CREATE TABLE daily_sales_item (
  restaurant_id   uuid NOT NULL,
  sales_date      date NOT NULL,
  item_id         uuid NOT NULL,
  name_vi         text NOT NULL,                            -- snapshot from the order lines
  name_en         text,
  quantity        integer NOT NULL,
  revenue         bigint NOT NULL,                          -- line totals, options included
  PRIMARY KEY (restaurant_id, sales_date, item_id)
);

-- How far the nightly rebuild has got for each restaurant (§5)
CREATE TABLE sales_reconciliation (
  restaurant_id      uuid PRIMARY KEY,
  reconciled_through date NOT NULL,
  last_run_at        timestamptz NOT NULL,
  last_mismatch_on   date                                   -- the most recent day that had to be repaired
);
```

**Changes from S-5:**
- **Tables by ID, with the number as a snapshot.** S-5 keyed on the table number. A table can be renamed, and a session can move (tables §3.4), so the key is the ID of the table the bill closed on, and the row carries that table's number for display.
- **Item names are stored with the item,** taken from the order-line snapshot, so a report still names an item that has since been renamed or deleted (menu §3).
- **Corrections** (`corrections`, `correction_count`) are new: they come from orders §7.2, which S-5 didn't have.
- **`sessions` counts bills closed with a payment,** not every closed session (§13 Q2).

**Size** (S-5): ≈ 20 KB per restaurant-day, 85 % of it item rows. 500 restaurants ≈ 3.6 GB a year, kept for the life of each restaurant.

## 4. Keeping today current: `SalesLedger`
`orders` calls it inside the close or correction transaction (orders §7.3); S-5 measured ≈ +2 ms per close.

- **`recordClosedBill(restaurantId, businessDate, tableId, tableNumber, paymentMethod, total, orderCount, items)`**, where `items` is one entry per item with its quantity, revenue and names, summed in `orders`:
  - one upsert into each of the four tables, adding to the existing row (`ON CONFLICT ... DO UPDATE SET x = t.x + excluded.x`), and `table_number`, `name_vi`, `name_en` set to the latest values;
  - **in a fixed order**: `daily_sales`, `daily_sales_payment`, `daily_sales_table`, `daily_sales_item`, and within each table by key. Two bills closing at once then lock the shared rows in the same order and queue instead of deadlocking (S-5: p95 22.5 ms at 10 concurrent closes once the order was fixed, max 132 ms).
- **`recordCorrection(restaurantId, businessDate, paymentMethod, amount)`**: adds to `corrections` and `correction_count` in `daily_sales`, and to `corrections` in `daily_sales_payment`.

**Lock order.** These rows are locked after the order rows and before the event counter, so the project's lock order becomes: session → bill → order rows → daily sales rows → event counter → audit chain head (CLAUDE.md, updated by this design).

## 5. The nightly rebuild
**`/internal/jobs/sales-reconcile`**, through Vercel Cron (architecture §9, §10):
- **When:** hourly on Pro; once a day on the free Hobby account (realtime §10). Each run picks the restaurants whose local time is past 03:00 and whose `reconciled_through` is before their local yesterday, so restaurants in different time zones are each rebuilt after their own night.
- **What, per restaurant and day** (yesterday, and the day before as a margin), in one tenant transaction:
  1. `SalesSource.facts(restaurantId, date)` from `orders` computes the four breakdowns and the corrections for that date from the closed bills, orders, lines and corrections (the S-5 `rawFacts` query, with corrections added). `reports` doesn't read the order tables itself (architecture §4).
  2. Compare with the stored rows. If they differ, count it (`reports_reconcile_mismatch_total`), set `last_mismatch_on`, and log the restaurant ID and date (no figures).
  3. Delete the day's rows and insert the computed ones. That's idempotent: running it twice gives the same rows.
  4. Move `reconciled_through` forward.
- **Cost** (S-5): ≈ 50–60 ms per restaurant-day, so ≈ 1 min for 500 restaurants × 2 days. The job takes batches of 50 restaurants within the function's time limit and continues on the next run; `pg_try_advisory_xact_lock` keeps runs from overlapping.
- **A mismatch means a bug**, because every close updates the tally in its own transaction. The alert (§9) makes sure it gets looked at rather than silently repaired forever.
- **Days are never rebuilt once the raw rows may be archived** (2 years, architecture §6). The job only touches the last 2 days.

## 6. The report: `GET /v1/staff/reports/daily-sales?from=2026-10-01&to=2026-10-31`
Manager only (architecture §7). Allowed when the restaurant is suspended (restaurants §6).

- **Range:** `from` ≤ `to`, at most 93 days (FR-050) → otherwise `400 validation_failed`. Dates are local dates in the restaurant's time zone.
- **Every day in the range is listed**, with zeros on days without sales, so the app needn't fill gaps (§13 Q4). Today shows the bills closed so far.
- **One query** over the four tables (the S-5 report query), returning the JSON document below. S-5: 31 days p95 10.4 ms inside PostgreSQL, 93 days p95 131 ms; NFR-PERF-04 allows 3 s.

```json
{
  "from": "2026-10-01", "to": "2026-10-31", "time_zone": "Asia/Ho_Chi_Minh",
  "totals": {
    "sessions": 9120, "orders": 18350,
    "gross": { "amount": "1824000000", "currency": "VND" },
    "corrections": { "amount": "-450000", "currency": "VND" },
    "net": { "amount": "1823550000", "currency": "VND" },
    "payments": [ { "method": "cash", "sessions": 5100, "amount": { "amount": "990000000", "currency": "VND" }, "corrections": { "amount": "-450000", "currency": "VND" } } ]
  },
  "days": [
    {
      "date": "2026-10-01", "sessions": 290, "orders": 584,
      "gross": { "amount": "58100000", "currency": "VND" },
      "corrections": { "amount": "0", "currency": "VND" }, "correction_count": 0,
      "net": { "amount": "58100000", "currency": "VND" },
      "payments": [ { "method": "cash", "sessions": 160, "amount": { "amount": "31000000", "currency": "VND" }, "corrections": { "amount": "0", "currency": "VND" } } ],
      "tables": [ { "table_id": "0192...", "number": "A-5", "sessions": 9, "gross": { "amount": "2100000", "currency": "VND" } } ],
      "top_items_by_quantity": [ { "item_id": "0192...", "name_vi": "Phở bò", "name_en": "Beef pho", "quantity": 140, "revenue": { "amount": "9100000", "currency": "VND" } } ],
      "top_items_by_revenue": [ ]
    }
  ]
}
```

- **Gross, corrections and net** (§13 Q1): `gross` is the bills closed that day; `corrections` is the signed sum of corrections made that day, whichever bill they belong to; `net` = gross + corrections. Per payment method the same. Tables and items count bills only, because a correction isn't tied to a table or an item.
- **Top items:** 10 by quantity and 10 by revenue per day; ties break on `item_id`, so the order is stable.
- **Size:** at most 93 days × (3 methods + one row per table that sold + 20 items). A 300-table restaurant using every table every day comes to ≈ 2.5 MB, under Vercel's 4.5 MB response cap (CR-004), and responses are compressed (architecture §11).
- **No personal data:** figures, table numbers and item names only. `Cache-Control: no-store` like every staff response (core-and-auth §2).

## 7. Endpoints
| Method and path | Who | Idempotency / If-Match | Allowed when suspended | Success |
|---|---|---|---|---|
| `GET /v1/staff/reports/daily-sales` | Manager | — | Yes | 200 |

The job route `/internal/jobs/sales-reconcile` is in the endpoint registry with the cron-secret group and isn't in the public contract (core-and-auth §2). No new error codes: a bad range is `400 validation_failed` with field codes `range_too_long` and `end_before_start`.

**Rate limit:** `reports.read`, 30 / 1 min per staff member. The report is cheap, so this only stops a runaway client.

## 8. Code layout
```
<base>.reports
  domain/        SalesDay, PaymentBreakdown, TableBreakdown, ItemBreakdown, ReportRange (≤ 93 days)
  application/   ReportService (read), SalesLedger (port provided to orders; upserts in key order),
                 ReconcileJob; SalesSource (port implemented by orders)
  persistence/   DailySalesRepository (every method takes RestaurantId), ReconciliationRepository
  http/          StaffReportRoutes, JobRoutes (paths from Endpoints.kt)
```

## 9. Observability
- `reports_reconcile_mismatch_total` by restaurant: alert on any increase in production, with a runbook (find the close that didn't update the tally; the rebuild has already repaired the figures).
- `reports_reconcile_lag_days`: today minus the oldest `reconciled_through`. Alert above 2 on Pro.
- The report endpoint's latency is in the standard RED metrics (NFR-OBS-01), checked against NFR-PERF-04.

## 10. Tests
| ID | Case | Req |
|---|---|---|
| IT-REP-01 | A closed bill appears in all four tables on its local day, in the same transaction as the close; a rolled-back close leaves them unchanged | FR-050, FR-013 |
| IT-REP-02 | A bill opened at 23:40 and closed at 00:20 local counts on the new day; in a restaurant in another time zone, by that zone | FR-050, ASSUMPTION-11 |
| IT-REP-03 | Cancelled and rejected orders and rejected lines don't count; a bill closed with total 0 isn't a session | FR-050 |
| IT-REP-04 | A correction counts on the day it's made, in `corrections` and its payment method, not in tables or items; net = gross + corrections | FR-050, FR-013 |
| IT-REP-05 | 10 bills sharing items and tables close at once (concurrency test): no deadlock, figures equal the sum | FR-050 |
| IT-REP-06 | Rebuild: tallies deliberately broken for a day are found, counted as a mismatch and repaired; running it again changes nothing | FR-050 |
| IT-REP-07 | Rebuild per time zone: a restaurant in UTC+7 and one in UTC−5 are each rebuilt after their own 03:00 | FR-050 |
| IT-REP-08 | Report = the same figures computed from the raw rows, for random ranges (the S-5 `check`) | FR-050 |
| IT-REP-09 | Range: 94 days or `to` before `from` → `400`; every day in range listed, zeros where nothing sold | FR-050 |
| IT-REP-10 | Waiter or kitchen → `403`; another restaurant's figures never appear; suspended restaurant's manager → `200` | FR-050, FR-042, FR-043 |
| IT-REP-11 | A renamed table and a renamed item still show in past days with the names they had | FR-050 |
| IT-REP-12 | Load (Phase 5): 31 days for a 300-bills/day restaurant on production-sized data, p95 ≤ 3 s | NFR-PERF-04 |

## 11. Changes this design makes elsewhere
- **orders §7.3:** `recordAdjustment` is named `recordCorrection`; `recordClosedBill` takes the table number and per-item names (summed in `orders`), so the ledger needs no other port. `SalesSource.facts` returns the same shape for one date.
- **CLAUDE.md and orders §3.1:** the lock order gains the daily sales rows between the order rows and the event counter (§4).
- **architecture §4's table list:** adds `sales_reconciliation`, as §4 leaves table names to Phase 3.

## 12. Traceability
The RTM's design column for FR-050, FR-051 and NFR-PERF-04 points here; FR-042 and NFR-SEC-02 add this design.

## 13. Owner decisions (2026-10-15)
The owner accepted all four recommendations. They're recorded in STATUS.md (Decisions; Q-8 resolved). CR-007 (approved 2026-10-15) wrote 3 into requirements v1.6.

1. **Corrections are shown, not hidden:** each day and each payment method shows `gross` (bills closed that day), `corrections` (made that day) and `net`. Tables and items show bills only.
2. **A "session" in the report is a bill closed with a payment.** Visits that ordered nothing, or whose orders were all cancelled, aren't sales and aren't counted.
3. **FR-051 CSV export (Could) not in release 1.** A 93-day export of a busy restaurant is ≈ 15 MB, over Vercel's 4.5 MB response cap. It would need an asynchronous job writing to object storage, and that store (TBD-13) isn't chosen yet. CR-007 marks FR-051 Won't for release 1, as CR-006 did for FR-016.
4. **Every day of the range is listed** (zeros where nothing sold), today shows the bills closed so far, and the answer includes totals for the whole range.
