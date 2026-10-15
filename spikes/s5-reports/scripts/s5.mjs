#!/usr/bin/env node
// S-5 measurements: the FR-050 sales report on one year of synthetic data, against the S-2 Neon
// database (Singapore). Node 22+, dependency: pg (npm install). Connection strings come from the
// S-2 env file and are never printed:
//
//   node --env-file=../s2-postgres/.env.spike scripts/s5.mjs <mode> [--option value ...]
//
// Modes (each prints a Markdown block to paste into README.md "Results"):
//   setup     [--last-day 2026-10-13]           drop and rebuild schema s5, generate the year, fill
//                                               daily_sales from it (timed), sizes and row counts (Q5, Q7)
//   ping      [--n 30]                          SELECT 1 round trip from this machine
//   report    --source agg|raw [--days 31] [--n 30]
//                                               the whole report in one query, timed on the client (Q1–Q3)
//   explain   --source agg|raw [--days 31] [--n 10]
//                                               EXPLAIN ANALYZE of the report: time inside PostgreSQL and
//                                               pages read (Q1–Q3)
//   check     [--days 31] [--n 20]              daily_sales report = raw report, for random ranges and
//                                               the last 31 days (Q6)
//   close     [--n 40] [--c 1] [--no-agg]       close a bill, with and without the daily_sales upsert (Q5)
//   reconcile [--day YYYY-MM-DD]                rebuild one day of daily_sales from the raw rows (Q5)
//   cold      [--idle 360] [--rounds 2] [--source raw] [--days 31]
//                                               wait until Neon's free compute suspends, then measure the
//                                               wake-up and the report on an empty cache (Q4)
//
// Every mode except setup runs as `app` through the pooler, inside a transaction that sets the
// tenant with set_config (RLS, ADR-0005), and reports allow 10 s (architecture §10).
// Run from this machine, not a Vercel function: client times include the network round trip to
// Singapore, so `ping` is measured too, and `explain` gives the time inside the database.

import pg from "pg";
import { readFileSync } from "node:fs";
import { performance } from "node:perf_hooks";

const LARGE = "00000000-0000-0000-0005-00000000000a";
const TZ = "Asia/Ho_Chi_Minh";
const METHODS = ["cash", "card", "bank_transfer"];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const [mode, ...rest] = process.argv.slice(2);
const opts = parseOptions(rest);
const modes = { setup, ping, report, explain, check, close, reconcile, cold };
if (!modes[mode]) {
  console.error(`usage: node --env-file=../s2-postgres/.env.spike scripts/s5.mjs <${Object.keys(modes).join("|")}> [--option value]`);
  process.exit(2);
}
for (const name of ["DATABASE_URL_MIGRATOR_DIRECT", "DATABASE_URL_APP_POOLED"]) {
  if (!process.env[name]) {
    console.error(`${name} is missing: run with node --env-file=../s2-postgres/.env.spike`);
    process.exit(2);
  }
}

// ---------------------------------------------------------------------------------------------
// SQL

// The four FR-050 breakdowns per local day, computed from the raw rows. `filter` selects the
// closed sessions: a date range for reports and rebuilds, one session when a bill closes.
// Only closed sessions and non-cancelled, non-rejected orders and lines count (FR-050).
function rawFacts(filter, tz) {
  return `
s AS (
  SELECT ts.id, ts.table_no, ts.payment_method, (ts.closed_at AT TIME ZONE ${tz})::date AS day
  FROM s5.table_sessions ts
  WHERE ts.status = 'closed' AND ${filter}
),
l AS (
  SELECT s.day, s.id AS session_id, o.id AS order_id, ol.item_id, ol.quantity,
         ol.quantity * ol.unit_price_minor AS amount
  FROM s
  JOIN s5.orders o ON o.session_id = s.id AND o.status NOT IN ('cancelled', 'rejected')
  JOIN s5.order_lines ol ON ol.order_id = o.id AND ol.status <> 'rejected'
),
bill AS (
  SELECT session_id, count(DISTINCT order_id) AS orders, sum(amount) AS gross FROM l GROUP BY session_id
),
f_day AS (
  SELECT s.day, count(*)::bigint AS sessions, coalesce(sum(b.orders), 0)::bigint AS orders,
         coalesce(sum(b.gross), 0)::bigint AS gross
  FROM s LEFT JOIN bill b ON b.session_id = s.id GROUP BY s.day
),
f_pay AS (
  SELECT s.day, s.payment_method AS method, count(*)::bigint AS sessions, coalesce(sum(b.gross), 0)::bigint AS amount
  FROM s LEFT JOIN bill b ON b.session_id = s.id GROUP BY s.day, s.payment_method
),
f_table AS (
  SELECT s.day, s.table_no, count(*)::bigint AS sessions, coalesce(sum(b.gross), 0)::bigint AS gross
  FROM s LEFT JOIN bill b ON b.session_id = s.id GROUP BY s.day, s.table_no
),
f_item AS (
  SELECT day, item_id, sum(quantity)::bigint AS quantity, sum(amount)::bigint AS revenue
  FROM l GROUP BY day, item_id
)`;
}

const RANGE = `ts.restaurant_id = $1::uuid
    AND ts.closed_at >= ($2::date::timestamp AT TIME ZONE $4::text)
    AND ts.closed_at < (($3::date + 1)::timestamp AT TIME ZONE $4::text)`;

// The same four breakdowns read from daily_sales. Column names match rawFacts.
const AGG_FACTS = `
f_day AS (
  SELECT sales_date AS day, sessions, orders, gross_minor AS gross FROM s5.daily_sales
  WHERE restaurant_id = $1::uuid AND sales_date BETWEEN $2::date AND $3::date
),
f_pay AS (
  SELECT sales_date AS day, method, sessions, amount_minor AS amount FROM s5.daily_sales_payment
  WHERE restaurant_id = $1::uuid AND sales_date BETWEEN $2::date AND $3::date
),
f_table AS (
  SELECT sales_date AS day, table_no, sessions, gross_minor AS gross FROM s5.daily_sales_table
  WHERE restaurant_id = $1::uuid AND sales_date BETWEEN $2::date AND $3::date
),
f_item AS (
  SELECT sales_date AS day, item_id, quantity, revenue_minor AS revenue FROM s5.daily_sales_item
  WHERE restaurant_id = $1::uuid AND sales_date BETWEEN $2::date AND $3::date
)`;

// One JSON document with everything FR-050 shows, per day: totals, payment methods, tables, and
// the top 10 items by quantity and by revenue. Ties break on item_id, so both sources agree.
const REPORT_SELECT = `
SELECT json_build_object(
  'days',     (SELECT coalesce(json_agg(f ORDER BY f.day), '[]'::json) FROM f_day f),
  'payments', (SELECT coalesce(json_agg(f ORDER BY f.day, f.method), '[]'::json) FROM f_pay f),
  'tables',   (SELECT coalesce(json_agg(f ORDER BY f.day, f.table_no), '[]'::json) FROM f_table f),
  'topItems', (SELECT coalesce(json_agg(t ORDER BY t.day, t.by, t.rank), '[]'::json) FROM (
      SELECT day, 'quantity' AS by, rank, item_id, quantity, revenue FROM (
        SELECT f.*, row_number() OVER (PARTITION BY f.day ORDER BY f.quantity DESC, f.item_id) AS rank FROM f_item f) q
      WHERE rank <= 10
      UNION ALL
      SELECT day, 'revenue', rank, item_id, quantity, revenue FROM (
        SELECT f.*, row_number() OVER (PARTITION BY f.day ORDER BY f.revenue DESC, f.item_id) AS rank FROM f_item f) q
      WHERE rank <= 10) t)
) AS report`;

function reportQuery(source, from, to) {
  return source === "agg"
    ? { text: `WITH ${AGG_FACTS} ${REPORT_SELECT}`, values: [LARGE, from, to] }
    : { text: `WITH ${rawFacts(RANGE, "$4::text")} ${REPORT_SELECT}`, values: [LARGE, from, to, TZ] };
}

// Nightly reconciliation and the initial fill: replace daily_sales for [from, to] with the raw facts.
const DELETE_AGG = ["daily_sales", "daily_sales_payment", "daily_sales_table", "daily_sales_item"].map(
  (t) => `DELETE FROM s5.${t} WHERE restaurant_id = $1::uuid AND sales_date BETWEEN $2::date AND $3::date`,
);
const INSERT_AGG = `WITH ${rawFacts(RANGE, "$4::text")},
i1 AS (INSERT INTO s5.daily_sales (restaurant_id, sales_date, sessions, orders, gross_minor)
       SELECT $1::uuid, day, sessions, orders, gross FROM f_day),
i2 AS (INSERT INTO s5.daily_sales_payment (restaurant_id, sales_date, method, sessions, amount_minor)
       SELECT $1::uuid, day, method, sessions, amount FROM f_pay),
i3 AS (INSERT INTO s5.daily_sales_table (restaurant_id, sales_date, table_no, sessions, gross_minor)
       SELECT $1::uuid, day, table_no, sessions, gross FROM f_table),
i4 AS (INSERT INTO s5.daily_sales_item (restaurant_id, sales_date, item_id, quantity, revenue_minor)
       SELECT $1::uuid, day, item_id, quantity, revenue FROM f_item)
SELECT (SELECT count(*) FROM f_day) AS days`;

// Closing a bill (FR-013) adds that one session to its day's rows. Built as literal SQL so the
// whole close goes to the database in one round trip (simple protocol, several statements): the
// row locks on daily_sales are then held for the database's time only, as they would be from a
// function next to the database, not for a round trip from this machine. Every value spliced in
// is a UUID checked against a regex or one of METHODS. Rows are upserted in key order, so two
// bills closing at once lock the shared item and table rows in the same order and can't deadlock.
function closeSql(sessionId, method, withAgg) {
  if (!UUID.test(sessionId) || !METHODS.includes(method)) throw new Error("bad close input");
  const upsert = `WITH ${rawFacts(`ts.id = '${sessionId}'`, `'${TZ}'`)},
u1 AS (INSERT INTO s5.daily_sales AS t (restaurant_id, sales_date, sessions, orders, gross_minor)
       SELECT '${LARGE}', day, sessions, orders, gross FROM f_day
       ON CONFLICT (restaurant_id, sales_date) DO UPDATE SET sessions = t.sessions + excluded.sessions,
         orders = t.orders + excluded.orders, gross_minor = t.gross_minor + excluded.gross_minor),
u2 AS (INSERT INTO s5.daily_sales_payment AS t (restaurant_id, sales_date, method, sessions, amount_minor)
       SELECT '${LARGE}', day, method, sessions, amount FROM f_pay ORDER BY day, method
       ON CONFLICT (restaurant_id, sales_date, method) DO UPDATE SET sessions = t.sessions + excluded.sessions,
         amount_minor = t.amount_minor + excluded.amount_minor),
u3 AS (INSERT INTO s5.daily_sales_table AS t (restaurant_id, sales_date, table_no, sessions, gross_minor)
       SELECT '${LARGE}', day, table_no, sessions, gross FROM f_table ORDER BY day, table_no
       ON CONFLICT (restaurant_id, sales_date, table_no) DO UPDATE SET sessions = t.sessions + excluded.sessions,
         gross_minor = t.gross_minor + excluded.gross_minor),
u4 AS (INSERT INTO s5.daily_sales_item AS t (restaurant_id, sales_date, item_id, quantity, revenue_minor)
       SELECT '${LARGE}', day, item_id, quantity, revenue FROM f_item ORDER BY day, item_id
       ON CONFLICT (restaurant_id, sales_date, item_id) DO UPDATE SET quantity = t.quantity + excluded.quantity,
         revenue_minor = t.revenue_minor + excluded.revenue_minor)
SELECT 1;`;
  return `BEGIN;
SELECT set_config('app.restaurant_id', '${LARGE}', true);
UPDATE s5.table_sessions SET status = 'closed', closed_at = now(), payment_method = '${method}'
  WHERE id = '${sessionId}' AND status = 'open';
${withAgg ? upsert : ""}
COMMIT;`;
}

// ---------------------------------------------------------------------------------------------
// Modes

async function setup() {
  const lastDay = opts["last-day"] ?? "2026-10-13";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(lastDay)) throw new Error("--last-day must be YYYY-MM-DD");
  const db = await connect(process.env.DATABASE_URL_MIGRATOR_DIRECT);
  try {
    const version = (await db.query("SHOW server_version_num")).rows[0].server_version_num;
    if (Number(version) < 180000) throw new Error(`PostgreSQL 18+ needed for uuidv7(shift); server is ${version}`);
    const t = {};
    t.schema = await timed(() => db.query(sql("schema.sql")));
    t.generate = await timed(() => db.query(sql("generate.sql").replaceAll("{{LAST_DAY}}", lastDay)));
    const restaurants = (await db.query("SELECT restaurant_id FROM s5.restaurants ORDER BY restaurant_id")).rows;
    t.fill = await timed(async () => {
      for (const { restaurant_id: r } of restaurants) {
        await db.query({ text: INSERT_AGG, values: [r, "2000-01-01", "2100-01-01", TZ] });
      }
    });
    t.finish = await timed(() => db.query(sql("finish.sql")));

    const counts = (await db.query(`
      SELECT r.name,
        (SELECT count(*) FROM s5.table_sessions WHERE restaurant_id = r.restaurant_id) AS sessions,
        (SELECT count(*) FROM s5.orders WHERE restaurant_id = r.restaurant_id) AS orders,
        (SELECT count(*) FROM s5.order_lines WHERE restaurant_id = r.restaurant_id) AS lines,
        (SELECT count(*) FROM s5.daily_sales WHERE restaurant_id = r.restaurant_id) AS days,
        (SELECT min(sales_date) FROM s5.daily_sales WHERE restaurant_id = r.restaurant_id) AS first_day,
        (SELECT max(sales_date) FROM s5.daily_sales WHERE restaurant_id = r.restaurant_id) AS last_day,
        (SELECT round(avg(sessions)) FROM s5.daily_sales WHERE restaurant_id = r.restaurant_id) AS bills_per_day
      FROM s5.restaurants r ORDER BY r.restaurant_id`)).rows;
    const sizes = (await db.query(`
      SELECT c.relname AS name, pg_total_relation_size(c.oid) AS total, pg_relation_size(c.oid) AS heap
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 's5' AND c.relkind = 'r' ORDER BY pg_total_relation_size(c.oid) DESC`)).rows;
    const dbSize = (await db.query("SELECT pg_database_size(current_database()) AS b")).rows[0].b;

    const raw = sizes.filter((s) => ["table_sessions", "orders", "order_lines"].includes(s.name))
      .reduce((a, s) => a + Number(s.total), 0);
    const agg = sizes.filter((s) => s.name.startsWith("daily_sales")).reduce((a, s) => a + Number(s.total), 0);
    const orders = counts.reduce((a, c) => a + Number(c.orders), 0);
    const restaurantDays = counts.reduce((a, c) => a + Number(c.days), 0);
    const perOrder = raw / orders;
    const aggPerDay = agg / restaurantDays;

    out(`### setup ${new Date().toISOString()} (last day ${lastDay})`);
    out(`Timings: schema ${ms(t.schema)}, generate ${ms(t.generate)}, fill daily_sales for the whole year ${ms(t.fill)}, indexes + RLS + ANALYZE ${ms(t.finish)}`);
    out("");
    out("| Restaurant | Bills | Orders | Lines | Days | First | Last | Bills/day |");
    out("|---|---|---|---|---|---|---|---|");
    for (const c of counts) {
      out(`| ${c.name} | ${c.sessions} | ${c.orders} | ${c.lines} | ${c.days} | ${iso(c.first_day)} | ${iso(c.last_day)} | ${c.bills_per_day} |`);
    }
    out("");
    out("| Table | Total incl. indexes | Heap |");
    out("|---|---|---|");
    for (const s of sizes) out(`| ${s.name} | ${mb(s.total)} | ${mb(s.heap)} |`);
    out("");
    out(`Database size: ${mb(dbSize)} (Neon free plan: 0.5 GB per project).`);
    out(`Raw rows (sessions + orders + lines, incl. indexes): ${Math.round(perOrder)} bytes per order → ` +
      `${gb(perOrder * 100_000 * 365)} a year at ASSUMPTION-9's 100k orders/day.`);
    out(`daily_sales (4 tables, incl. indexes): ${Math.round(aggPerDay)} bytes per restaurant-day → ` +
      `${gb(aggPerDay * 500 * 365)} a year at 500 restaurants.`);
  } finally {
    await db.end();
  }
}

async function ping() {
  const n = int("n", 30);
  const db = await connect(process.env.DATABASE_URL_APP_POOLED);
  try {
    const { stats } = await pingStats(db, n);
    out(`### ping ${new Date().toISOString()} (pooled, n=${n})`);
    out(`SELECT 1 round trip: ${fmtStats(stats)}`);
  } finally {
    await db.end();
  }
}

async function report() {
  const source = sourceOpt();
  const days = int("days", 31);
  const n = int("n", 30);
  const db = await connect(process.env.DATABASE_URL_APP_POOLED);
  try {
    const { stats: rtt } = await pingStats(db, 10);
    const bounds = await dataBounds(db);
    await runReport(db, source, ...randomRange(bounds, days)); // warm-up, not counted
    const times = [];
    let bytes = 0;
    for (let i = 0; i < n; i++) {
      const [from, to] = randomRange(bounds, days);
      const { elapsed, result } = await runReport(db, source, from, to);
      times.push(elapsed);
      bytes = JSON.stringify(result).length;
    }
    const st = stats(times);
    out(`### report --source ${source} --days ${days} ${new Date().toISOString()} (pooled, n=${n}, random ranges)`);
    out(`Report query on the client: ${fmtStats(st)}; minus one round trip (ping p50 ${rtt.p50.toFixed(1)} ms): ` +
      `p50 ${(st.p50 - rtt.p50).toFixed(1)} ms, p95 ${(st.p95 - rtt.p50).toFixed(1)} ms. Response ≈ ${Math.round(bytes / 1024)} KiB of JSON.`);
  } finally {
    await db.end();
  }
}

async function explain() {
  const source = sourceOpt();
  const days = int("days", 31);
  const n = int("n", 10);
  const db = await connect(process.env.DATABASE_URL_APP_POOLED);
  try {
    const bounds = await dataBounds(db);
    await explainReport(db, source, ...randomRange(bounds, days)); // warm-up
    const runs = [];
    for (let i = 0; i < n; i++) runs.push(await explainReport(db, source, ...randomRange(bounds, days)));
    out(`### explain --source ${source} --days ${days} ${new Date().toISOString()} (n=${n}, random ranges)`);
    out(`Execution time in PostgreSQL: ${fmtStats(stats(runs.map((r) => r.exec)))}; planning p50 ${stats(runs.map((r) => r.plan)).p50.toFixed(1)} ms`);
    out(`Pages per report (8 KiB): hit p50 ${stats(runs.map((r) => r.hit)).p50}, read from storage p50 ${stats(runs.map((r) => r.read)).p50}`);
  } finally {
    await db.end();
  }
}

async function check() {
  const days = int("days", 31);
  const n = int("n", 20);
  const db = await connect(process.env.DATABASE_URL_APP_POOLED);
  try {
    const bounds = await dataBounds(db);
    const ranges = [[addDays(bounds.last, -(days - 1)), bounds.last]];
    for (let i = 0; i < n; i++) ranges.push(randomRange(bounds, days));
    let equal = 0;
    const diffs = [];
    for (const [from, to] of ranges) {
      const a = JSON.stringify((await runReport(db, "agg", from, to)).result);
      const r = JSON.stringify((await runReport(db, "raw", from, to)).result);
      if (a === r) equal++;
      else diffs.push(`${from}..${to}`);
    }
    const crossings = await inTenant(db, LARGE, () => db.query(`
      SELECT count(*) AS n FROM s5.table_sessions
      WHERE status = 'closed' AND (closed_at AT TIME ZONE $1)::date > (opened_at AT TIME ZONE $1)::date`, [TZ]));
    out(`### check --days ${days} ${new Date().toISOString()}`);
    out(`daily_sales report equals the raw report for ${equal} of ${ranges.length} ranges (the last ${days} days, then ${n} random)` +
      `${diffs.length ? `; differ: ${diffs.join(", ")}` : ""}. ` +
      `${crossings.rows[0].n} bills of the large restaurant close after local midnight and count on the next day.`);
  } finally {
    await db.end();
  }
}

async function close() {
  const n = int("n", 40);
  const c = int("c", 1);
  const withAgg = !opts["no-agg"];
  const workers = await Promise.all(Array.from({ length: c }, () => connect(process.env.DATABASE_URL_APP_POOLED)));
  try {
    const { stats: rtt } = await pingStats(workers[0], 10);
    const per = Math.ceil(n / c);
    // Open the bills first (not timed): a session with 2 orders of 3 lines each.
    const sessions = [];
    for (let i = 0; i < per * c; i++) sessions.push(await openBill(workers[0]));
    const times = [];
    await Promise.all(workers.map(async (db, w) => {
      for (let i = 0; i < per; i++) {
        const sid = sessions[w * per + i];
        const sqlText = closeSql(sid, METHODS[i % METHODS.length], withAgg);
        const t0 = performance.now();
        await db.query(sqlText);
        times.push(performance.now() - t0);
      }
    }));
    const st = stats(times);
    out(`### close ${withAgg ? "with" : "without"} the daily_sales upsert, c=${c} ${new Date().toISOString()} (n=${times.length}, one round trip each)`);
    out(`Close transaction on the client: ${fmtStats(st)}; minus one round trip (ping p50 ${rtt.p50.toFixed(1)} ms): ` +
      `p50 ${(st.p50 - rtt.p50).toFixed(1)} ms, p95 ${(st.p95 - rtt.p50).toFixed(1)} ms`);
  } finally {
    await Promise.all(workers.map((db) => db.end()));
  }
}

async function reconcile() {
  const db = await connect(process.env.DATABASE_URL_APP_POOLED);
  try {
    const bounds = await dataBounds(db);
    const day = opts.day ?? bounds.last;
    const before = JSON.stringify((await runReport(db, "agg", day, day)).result);
    const raw = JSON.stringify((await runReport(db, "raw", day, day)).result);
    let tDelete = 0, tInsert = 0, total = 0;
    total = await timed(() => inTenant(db, LARGE, async () => {
      await db.query("SELECT set_config('statement_timeout', '10s', true)");
      tDelete = await timed(async () => { for (const d of DELETE_AGG) await db.query(d, [LARGE, day, day]); });
      tInsert = await timed(() => db.query(INSERT_AGG, [LARGE, day, day, TZ]));
    }));
    const after = JSON.stringify((await runReport(db, "agg", day, day)).result);
    out(`### reconcile --day ${day} ${new Date().toISOString()}`);
    out(`Before: daily_sales ${before === raw ? "matched" : "did not match"} the raw rows; after: ${after === raw ? "matches" : "DOES NOT MATCH"}. ` +
      `Transaction ${ms(total)} on the client (4 deletes ${ms(tDelete)}, 1 insert from the raw rows ${ms(tInsert)}; ~8 round trips).`);
  } finally {
    await db.end();
  }
}

async function cold() {
  const idle = int("idle", 360);
  const rounds = int("rounds", 2);
  const source = opts.source ?? "raw";
  const days = int("days", 31);
  out(`### cold --source ${source} --days ${days} --idle ${idle} ${new Date().toISOString()}`);
  out("| Round | Connect + first SELECT 1 | Report in PostgreSQL, cold | Pages read from storage | Same report again, warm | Pages read |");
  out("|---|---|---|---|---|---|");
  for (let round = 1; round <= rounds; round++) {
    console.error(`round ${round}: idle ${idle} s so the compute suspends …`);
    await new Promise((r) => setTimeout(r, idle * 1000));
    const t0 = performance.now();
    const db = await connect(process.env.DATABASE_URL_APP_POOLED);
    await db.query("SELECT 1");
    const wake = performance.now() - t0;
    try {
      const bounds = await dataBounds(db);
      const [from, to] = randomRange(bounds, days);
      const first = await explainReport(db, source, from, to);
      const second = await explainReport(db, source, from, to);
      out(`| ${round} | ${ms(wake)} | ${ms(first.exec)} | ${first.read} | ${ms(second.exec)} | ${second.read} |`);
    } finally {
      await db.end();
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Helpers

async function connect(url) {
  const db = new pg.Client({ connectionString: url, keepAlive: true, keepAliveInitialDelayMillis: 10_000 });
  await db.connect();
  return db;
}

async function inTenant(db, restaurant, fn) {
  await db.query("BEGIN");
  try {
    await db.query("SELECT set_config('app.restaurant_id', $1, true)", [restaurant]);
    const result = await fn();
    await db.query("COMMIT");
    return result;
  } catch (e) {
    await db.query("ROLLBACK").catch(() => {});
    throw e;
  }
}

// One report: BEGIN, tenant and 10 s timeout in one statement, the report query (timed), COMMIT.
async function runReport(db, source, from, to) {
  return inTenant(db, LARGE, async () => {
    await db.query("SELECT set_config('statement_timeout', '10s', true)");
    const q = reportQuery(source, from, to);
    const t0 = performance.now();
    const res = await db.query(q);
    return { elapsed: performance.now() - t0, result: res.rows[0].report };
  });
}

async function explainReport(db, source, from, to) {
  return inTenant(db, LARGE, async () => {
    await db.query("SELECT set_config('statement_timeout', '10s', true)");
    const q = reportQuery(source, from, to);
    const res = await db.query({ text: `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ${q.text}`, values: q.values });
    const planDoc = res.rows[0]["QUERY PLAN"][0];
    return {
      exec: planDoc["Execution Time"],
      plan: planDoc["Planning Time"],
      hit: planDoc.Plan["Shared Hit Blocks"],
      read: planDoc.Plan["Shared Read Blocks"],
    };
  });
}

async function dataBounds(db) {
  const r = await inTenant(db, LARGE, () =>
    db.query("SELECT min(sales_date) AS first, max(sales_date) AS last FROM s5.daily_sales WHERE restaurant_id = $1", [LARGE]));
  if (!r.rows[0].first) throw new Error("no data: run setup first");
  return { first: iso(r.rows[0].first), last: iso(r.rows[0].last) };
}

async function openBill(db) {
  return inTenant(db, LARGE, async () => {
    const s = await db.query(`
      INSERT INTO s5.table_sessions (id, restaurant_id, table_no, status, opened_at)
      VALUES (uuidv7(), $1::uuid, 1 + floor(random() * 40)::int, 'open', now() - interval '1 hour') RETURNING id`, [LARGE]);
    const sid = s.rows[0].id;
    await db.query(`
      WITH o AS (
        INSERT INTO s5.orders (id, restaurant_id, session_id, status, placed_at)
        SELECT uuidv7(), $1::uuid, $2::uuid, 'served', now() - interval '30 minutes' FROM generate_series(1, 2)
        RETURNING id)
      INSERT INTO s5.order_lines (id, restaurant_id, order_id, item_id, quantity, unit_price_minor, status)
      SELECT uuidv7(), $1::uuid, o.id, m.id, 1 + (random() < 0.3)::int, m.price_minor, 'served'
      FROM o CROSS JOIN LATERAL (
        SELECT id, price_minor FROM s5.menu_items WHERE restaurant_id = $1::uuid
        ORDER BY random() + 0 * length(o.id::text) LIMIT 3) m`, [LARGE, sid]);
    return sid;
  });
}

async function pingStats(db, n) {
  await db.query("SELECT 1");
  const times = [];
  for (let i = 0; i < n; i++) {
    const t0 = performance.now();
    await db.query("SELECT 1");
    times.push(performance.now() - t0);
  }
  return { stats: stats(times) };
}

function randomRange({ first, last }, days) {
  const span = daysBetween(first, last) - (days - 1);
  if (span < 0) throw new Error(`only ${daysBetween(first, last) + 1} days of data`);
  const from = addDays(first, Math.floor(Math.random() * (span + 1)));
  return [from, addDays(from, days - 1)];
}

function sourceOpt() {
  const s = opts.source;
  if (s !== "agg" && s !== "raw") throw new Error("--source agg|raw is required");
  return s;
}

function sql(name) {
  return readFileSync(new URL(`../sql/${name}`, import.meta.url), "utf8");
}

async function timed(fn) {
  const t0 = performance.now();
  await fn();
  return performance.now() - t0;
}

function stats(values) {
  const v = [...values].sort((a, b) => a - b);
  const pct = (p) => v[Math.min(v.length - 1, Math.ceil((p / 100) * v.length) - 1)];
  return { n: v.length, p50: pct(50), p95: pct(95), max: v[v.length - 1] };
}

function fmtStats(s) {
  return `p50 ${s.p50.toFixed(1)} ms, p95 ${s.p95.toFixed(1)} ms, max ${s.max.toFixed(1)} ms`;
}

function iso(d) {
  if (typeof d === "string") return d.slice(0, 10);
  // pg returns DATE as a local-midnight Date; format it back in local time.
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function addDays(isoDay, n) {
  const d = new Date(`${isoDay}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

function daysBetween(a, b) {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000);
}

function int(name, dflt) {
  return opts[name] === undefined ? dflt : Number.parseInt(opts[name], 10);
}

function ms(x) {
  return x >= 10_000 ? `${(x / 1000).toFixed(1)} s` : `${Math.round(x)} ms`;
}

function mb(b) {
  return `${(Number(b) / 1024 / 1024).toFixed(1)} MB`;
}

function gb(b) {
  return `${(b / 1024 ** 3).toFixed(1)} GB`;
}

function out(line) {
  console.log(line);
}

function parseOptions(args) {
  const o = {};
  for (let i = 0; i < args.length; i++) {
    if (!args[i].startsWith("--")) continue;
    const key = args[i].slice(2);
    if (i + 1 < args.length && !args[i + 1].startsWith("--")) o[key] = args[++i];
    else o[key] = true;
  }
  return o;
}

await modes[mode]();
