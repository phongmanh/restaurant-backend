#!/usr/bin/env node
// S-2 measurements against a deployed spike (or http://localhost:8080). Node 18+, no dependencies.
//
//   node scripts/measure.mjs <mode> <baseUrl> [--option value ...]
//
// Modes (each prints a Markdown block to paste into README.md "Results"):
//   info                                      /spike/info: region, startupMs, pool start times
//   ping        --via pooled --n 200          Q1: sequential SELECT 1, timed inside the function
//   order       --via pooled --n 200 --c 1    Q2: order transactions, --c at a time
//               --threshold 0
//   prepared    --n 60 --c 3                  Q3: order mode over the pooler with prepareThreshold
//                                             0, 1 and 5; errors by message
//   rls                                       Q4: the fixed checks, over the pooler and direct
//   burst       --via pooled --n 500 --c 50   Q4: mixed-tenant burst; any request that saw another
//                                             tenant, or a setting left after commit, is a leak
//   lock        --via pooled --c 10           Q5: --c concurrent tries on one key, held --hold ms;
//               --hold 3000                   the holders' intervals must not overlap
//   connections --c 50 --hold 2000            Q7: --c concurrent transactions holding a connection
//                                             --hold ms, while pg_stat_activity is polled
//   cold        --samples 3 --idle 360        Q7/Q8: after --idle s, the first request: instance
//                                             start, pool start (includes a Neon compute wake-up)
//
// Times that matter are the ones the function reports (acquireMs, txMs): the exit criterion is
// measured inside the function, so the client's distance to sin1 doesn't count.
//
// Deployment Protection: set VERCEL_AUTOMATION_BYPASS_SECRET (Project Settings → Deployment
// Protection → Protection Bypass for Automation) and it is sent as x-vercel-protection-bypass.

import http from "node:http";
import https from "node:https";

const [mode, baseUrl, ...rest] = process.argv.slice(2);
const opts = parseOptions(rest);
const bypass = process.env.VERCEL_AUTOMATION_BYPASS_SECRET;
const httpAgent = new http.Agent({ keepAlive: true, keepAliveMsecs: 10_000 });
const httpsAgent = new https.Agent({ keepAlive: true, keepAliveMsecs: 10_000 });

const modes = { info, ping, order, prepared, rls, burst, lock, connections, cold };
if (!modes[mode] || !baseUrl) {
  console.error(`usage: node scripts/measure.mjs <${Object.keys(modes).join("|")}> <baseUrl> [--option value]`);
  process.exit(2);
}
await modes[mode]();

// ---------------------------------------------------------------- modes

async function info() {
  const body = await json("GET", "/spike/info");
  print(`### info (${new Date().toISOString()})`, [
    `- region \`${body.region ?? "?"}\`, instance ${short(body.instanceId)}, startupMs ${body.startupMs}, uptimeMs ${body.uptimeMs}`,
    ...Object.entries(body.pools).map(([name, p]) => `- pool ${name}: opened in ${p.startMs} ms; active ${p.active}, idle ${p.idle}, total ${p.total}`),
  ]);
}

async function ping() {
  const via = opts.via ?? "pooled";
  const n = int("n", 200);
  await json("GET", `/spike/db/ping?via=${via}`); // warm the instance and the pool
  const acquire = [], query = [], client = [];
  for (let i = 0; i < n; i++) {
    const r = await request("GET", `/spike/db/ping?via=${via}`);
    const body = JSON.parse(r.body);
    acquire.push(body.acquireMs);
    query.push(body.queryMs);
    client.push(r.ms);
  }
  print(`### ping via ${via}, ${n} sequential`, [
    `- SELECT 1 inside the function: ${summary(query)}`,
    `- connection acquire: ${summary(acquire)}`,
    `- client round trip (includes the client's distance to sin1): ${summary(client)}`,
  ]);
}

async function order() {
  const via = opts.via ?? "pooled";
  const threshold = int("threshold", 0);
  const r = await runOrders(via, threshold, int("n", 200), int("c", 1));
  print(`### order via ${via}, prepareThreshold ${threshold}, ${r.n} transactions, ${r.c} at a time`, [
    `- transaction (set tenant → commit) inside the function: ${summary(r.tx)}`,
    `- connection acquire: ${summary(r.acquire)}`,
    `- acquire + transaction: ${summary(r.total)}`,
    `- instances: ${r.instances.size}; errors: ${r.errorCount}${errorLines(r.errors)}`,
  ]);
}

async function prepared() {
  const n = int("n", 60);
  const c = int("c", 3);
  const rows = ["| prepareThreshold | OK | Errors | Max prepared statements on a server backend | Error messages |", "|---|---|---|---|---|"];
  for (const threshold of [0, 1, 5]) {
    const r = await runOrders("pooled", threshold, n, c);
    const messages = [...r.errors.entries()].map(([m, k]) => `${k}× ${m.slice(0, 90)}`).join("; ") || "-";
    const prepared = threshold === 0 ? "not checked" : Math.max(0, ...r.serverPrepared);
    rows.push(`| ${threshold} | ${r.n - r.errorCount} | ${r.errorCount} | ${prepared} | ${messages} |`);
  }
  print(`### prepared statements over the pooler, ${n} orders per threshold, ${c} at a time`, rows);
}

async function rls() {
  const lines = [];
  for (const via of ["pooled", "direct"]) {
    const body = await json("GET", `/spike/db/rls?via=${via}`);
    lines.push(`- via ${via}: **${body.allPass ? "all pass" : "FAIL"}**`);
    for (const c of body.checks) lines.push(`  - ${c.pass ? "pass" : "FAIL"}: ${c.name} (${c.detail})`);
  }
  print("### rls checks", lines);
}

async function burst() {
  const via = opts.via ?? "pooled";
  const n = int("n", 500);
  const c = int("c", 50);
  const probes = await pool(n, c, (i) => request("GET", `/spike/db/tenant?via=${via}&restaurant=${i % 2 === 0 ? "a" : "b"}`));
  let wrongSetting = 0, sawOther = 0, leaked = 0, errors = 0;
  const instances = new Set(), pids = new Set(), errorMessages = new Map();
  let samePid = 0;
  for (const r of probes) {
    if (r.error || r.status !== 200) { errors++; count(errorMessages, r.error ?? safeMessage(r.body)); continue; }
    const p = JSON.parse(r.body);
    instances.add(p.instanceId);
    pids.add(p.backendPid);
    if (p.setting !== p.requested) wrongSetting++;
    if (p.visibleRestaurants.some((id) => id !== p.requested)) sawOther++;
    if (p.settingAfterCommit || p.visibleAfterCommit.length > 0) leaked++;
    if (p.backendPid === p.backendPidAfterCommit) samePid++;
  }
  const leak = wrongSetting + sawOther + leaked > 0;
  print(`### mixed-tenant burst via ${via}, ${n} requests, ${c} at a time: **${leak ? "LEAK" : "no leak"}${errors ? `, ${errors} errors` : ""}**`, [
    `- setting ≠ requested tenant: ${wrongSetting}; saw another tenant's rows: ${sawOther}; setting or rows left after commit: ${leaked}; errors: ${errors}${errorLines(errorMessages)}`,
    `- instances: ${instances.size}; server backends (pids) seen: ${pids.size}; follow-up transaction on the same backend: ${samePid}/${n - errors}`,
  ]);
}

async function lock() {
  const via = opts.via ?? "pooled";
  const c = int("c", 10);
  const hold = int("hold", 3000);
  const key = Date.now();
  const results = await Promise.all(
    Array.from({ length: c }, () => request("POST", `/spike/db/lock?via=${via}&key=${key}&holdMs=${hold}`).catch((e) => ({ error: e.message }))),
  );
  const ok = results.filter((r) => !r.error && r.status === 200).map((r) => JSON.parse(r.body));
  const holders = ok.filter((r) => r.acquired).sort((a, b) => a.heldFromMs - b.heldFromMs);
  let overlaps = 0;
  for (let i = 1; i < holders.length; i++) if (holders[i].heldFromMs < holders[i - 1].heldUntilMs) overlaps++;
  print(`### advisory lock via ${via}, ${c} concurrent on one key, held ${hold} ms: **${overlaps === 0 && holders.length > 0 ? "mutually exclusive" : "FAIL"}**`, [
    `- acquired: ${holders.length} of ${ok.length} (more than 1 is fine only if their hold intervals don't overlap; a request that waited for a connection may take it after the first commits)`,
    `- overlapping holds: ${overlaps}; errors: ${c - ok.length}`,
    `- free again right after the holder's commit: ${holders.map((h) => (h.reacquiredAfterCommit ? "yes" : "no")).join(", ") || "-"}`,
    `- instances: ${new Set(ok.map((r) => r.instanceId)).size}`,
  ]);
}

async function connections() {
  const c = int("c", 50);
  const hold = int("hold", 2000);
  const before = await json("GET", "/spike/db/connections");
  let polling = true, maxApp = 0, maxTotal = 0;
  const poller = (async () => {
    while (polling) {
      try {
        const s = await json("GET", "/spike/db/connections");
        maxApp = Math.max(maxApp, appCount(s));
        maxTotal = Math.max(maxTotal, s.byRoleAndState.reduce((n, r) => n + r.count, 0));
      } catch { /* a poll failing under load is itself data, but keep polling */ }
      await sleep(250);
    }
  })();
  const start = Date.now();
  // Distinct keys: every request takes its own lock and holds a connection for `hold` ms.
  const results = await Promise.all(
    Array.from({ length: c }, (_, i) => request("POST", `/spike/db/lock?via=pooled&key=${start + i}&holdMs=${hold}`).catch((e) => ({ error: e.message }))),
  );
  const wall = Date.now() - start;
  polling = false;
  await poller;
  const after = await json("GET", "/spike/db/connections");
  const ok = results.filter((r) => !r.error && r.status === 200);
  const errorMessages = new Map();
  for (const r of results) if (r.error || r.status !== 200) count(errorMessages, r.error ?? safeMessage(r.body));
  print(`### connections: ${c} concurrent transactions holding ${hold} ms over the pooler`, [
    `- max_connections ${before.maxConnections}; client backends before: ${JSON.stringify(before.byRoleAndState)}`,
    `- during: max \`app\` backends ${maxApp}, max client backends in total ${maxTotal}`,
    `- after: ${JSON.stringify(after.byRoleAndState)}`,
    `- wall time ${wall} ms (≈ ${hold} ms means no request waited for a connection); instances: ${new Set(ok.map((r) => JSON.parse(r.body).instanceId)).size}; errors: ${c - ok.length}${errorLines(errorMessages)}`,
  ]);
}

async function cold() {
  const samples = int("samples", 3);
  const idle = int("idle", 360);
  const rows = ["| # | Instance cold? | startupMs | Pooled pool opened ms | Direct pool opened ms | First ping: acquire / query ms |", "|---|---|---|---|---|---|"];
  for (let i = 1; i <= samples; i++) {
    process.stderr.write(`sample ${i}/${samples}: idle ${idle} s\n`);
    await sleep(idle * 1000);
    const info = await json("GET", "/spike/info");
    const p = await json("GET", "/spike/db/ping?via=pooled");
    rows.push(`| ${i} | ${info.firstRequestOnInstance ? "yes" : "no"} | ${info.startupMs} | ${info.pools.pooled.startMs} | ${info.pools.direct.startMs} | ${p.acquireMs} / ${p.queryMs} |`);
  }
  print(`### cold, ${samples} samples, ${idle} s idle`, rows);
}

// ---------------------------------------------------------------- helpers

async function runOrders(via, threshold, n, c) {
  await json("GET", `/spike/db/ping?via=${via}`);
  const results = await pool(n, c, () => request("POST", `/spike/db/order?via=${via}&prepareThreshold=${threshold}&restaurant=a`));
  const tx = [], acquire = [], total = [], serverPrepared = [], errors = new Map(), instances = new Set();
  let errorCount = 0;
  for (const r of results) {
    if (r.error || r.status !== 200) {
      errorCount++;
      count(errors, r.error ?? safeMessage(r.body));
      continue;
    }
    const body = JSON.parse(r.body);
    tx.push(body.txMs);
    acquire.push(body.acquireMs);
    total.push(Math.round((body.acquireMs + body.txMs) * 100) / 100);
    instances.add(body.instanceId);
    if (body.serverPreparedStatements != null) serverPrepared.push(body.serverPreparedStatements);
  }
  return { n, c, tx, acquire, total, serverPrepared, errors, errorCount, instances };
}

/** Runs task(i) for i in 0..n-1, at most c at a time. Failures become { error }. */
async function pool(n, c, task) {
  const results = new Array(n);
  let next = 0;
  const worker = async () => {
    while (next < n) {
      const i = next++;
      results[i] = await task(i).catch((e) => ({ error: e.message }));
    }
  };
  await Promise.all(Array.from({ length: Math.min(c, n) }, worker));
  return results;
}

async function json(method, path) {
  const r = await request(method, path);
  if (r.status !== 200) throw new Error(`${method} ${path} → ${r.status} ${r.body.slice(0, 200)}`);
  return JSON.parse(r.body);
}

function request(method, path) {
  const url = new URL(path, baseUrl);
  const client = url.protocol === "https:" ? https : http;
  const headers = { "user-agent": "s2-spike-measure", "content-length": "0" };
  if (bypass) headers["x-vercel-protection-bypass"] = bypass;
  return new Promise((resolve, reject) => {
    const start = performance.now();
    const req = client.request(url, { method, headers, agent: url.protocol === "https:" ? httpsAgent : httpAgent }, (res) => {
      res.setEncoding("utf8");
      let body = "";
      res.on("data", (chunk) => (body += chunk));
      res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, body, ms: Math.round(performance.now() - start) }));
      res.on("error", reject);
    });
    // The client network drops TCP connections idle for ~40 s (found in S-1).
    req.on("socket", (socket) => socket.setKeepAlive(true, 10_000));
    req.on("error", reject);
    req.end();
  });
}

/** The error message from a SqlError body, or the body's start, with numbers blanked so alike errors group. */
function safeMessage(body) {
  let message;
  try {
    const e = JSON.parse(body);
    message = `${e.sqlState ?? "?"} ${e.message}`;
  } catch {
    message = body.slice(0, 120);
  }
  return message.replace(/\d+/g, "#");
}

function appCount(s) {
  return s.byRoleAndState.filter((r) => r.role === "app").reduce((n, r) => n + r.count, 0);
}

function count(map, key) {
  map.set(key, (map.get(key) ?? 0) + 1);
}

function errorLines(errors) {
  return errors.size === 0 ? "" : `\n${[...errors.entries()].map(([m, k]) => `  - ${k}× ${m}`).join("\n")}`;
}

function summary(values) {
  if (values.length === 0) return "n/a";
  const sorted = [...values].sort((a, b) => a - b);
  const p = (q) => sorted[Math.min(sorted.length - 1, Math.ceil((q / 100) * sorted.length) - 1)];
  return `p50 ${p(50)} · p95 ${p(95)} · p99 ${p(99)} · max ${sorted.at(-1)} ms (n=${values.length})`;
}

function short(id) {
  return id.slice(0, 8);
}

function parseOptions(args) {
  const out = {};
  for (let i = 0; i < args.length; i += 2) out[args[i].replace(/^--/, "")] = args[i + 1];
  return out;
}

function int(name, fallback) {
  return opts[name] === undefined ? fallback : Number.parseInt(opts[name], 10);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function print(title, lines) {
  console.log([title, ...lines, ""].join("\n"));
}
