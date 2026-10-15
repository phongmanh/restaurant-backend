#!/usr/bin/env node
// S-1 measurements against a deployed spike. Node 18+, no dependencies.
//
//   node scripts/measure.mjs <mode> <baseUrl> [--option value ...]
//
// Modes (each prints a Markdown block to paste into README.md "Results"):
//   info                                       one /spike/info: region, limits, proxy headers
//   warm         --n 200                       sequential warm requests: latency percentiles
//   cold         --samples 10 --idle 45        wait --idle seconds between requests so the instance
//                                              scales to zero (preview: 30 s, production: 5 min)
//   concurrency  --c 50 --hold 5000            --c parallel requests held --hold ms: instances
//                                              used and requests per instance
//   duration     --steps 60,300,800,1800      one held request per step (seconds): where the
//                                              platform cuts a request off
//   sse          --seconds 300 --interval 1000 one event stream: buffering and maximum length
//   cpu          --ms 2000                     CPU iterations in --ms (compare instance sizes)
//
// Rate limiting (ADR-0008; Q10–Q12). --store is redis or firewall (see /spike/limit):
//   limit-latency --store redis --n 200        sequential checks, a fresh key each: time the store
//                                              adds per request, measured inside the function
//   limit-burst   --store redis --c 50         --c parallel hits on one key with --limit per --window:
//                 --limit 10 --window 60       is the limit exact across instances?
//   limit-down    --n 20 --timeout 200         both broken stores: do requests fail open, how fast?
//   waf-edge      --n 40 --limit 20            sequential requests to /spike/waf under an edge rule
//                 --window 10                  of --limit per --window per IP: who answers 429?
//
// Deployment Protection: set VERCEL_AUTOMATION_BYPASS_SECRET (Project Settings → Deployment
// Protection → Protection Bypass for Automation) and it is sent as x-vercel-protection-bypass.
//
// Node's http/https modules are used instead of fetch on purpose: fetch aborts after 300 s
// without response headers, which would end the duration test early on the client side.

import http from "node:http";
import https from "node:https";

const [mode, baseUrl, ...rest] = process.argv.slice(2);
const opts = parseOptions(rest);
const bypass = process.env.VERCEL_AUTOMATION_BYPASS_SECRET;
const httpAgent = new http.Agent({ keepAlive: true, keepAliveMsecs: 10_000 });
const httpsAgent = new https.Agent({ keepAlive: true, keepAliveMsecs: 10_000 });

const modes = {
  info, warm, cold, concurrency, duration, sse, cpu,
  "limit-latency": limitLatency, "limit-burst": limitBurst, "limit-down": limitDown, "waf-edge": wafEdge,
};
if (!modes[mode] || !baseUrl) {
  console.error(`usage: node scripts/measure.mjs <${Object.keys(modes).join("|")}> <baseUrl> [--option value]`);
  process.exit(2);
}
await modes[mode]();

// ---------------------------------------------------------------- modes

async function info() {
  const r = await get("/spike/info");
  const body = JSON.parse(r.body);
  print(`### info (${new Date().toISOString()})`, [
    `- status ${r.status}, ${r.ms} ms, response x-vercel-id \`${r.headers["x-vercel-id"] ?? "-"}\``,
    `- region \`${body.platform.VERCEL_REGION ?? "?"}\`, env \`${body.platform.VERCEL_ENV ?? "?"}\``,
    `- JVM ${body.runtime.javaVersion}, ${body.runtime.availableProcessors} CPUs, heap ${body.runtime.maxHeapMb} MB, container limit ${body.runtime.containerMemoryLimitMb === null ? "none" : `${body.runtime.containerMemoryLimitMb} MB`}, GC ${body.runtime.garbageCollectors.join(" / ")}`,
    `- JVM args: \`${body.runtime.jvmArguments.join(" ")}\``,
    `- startupMs ${body.startupMs}, uptimeMs ${body.uptimeMs}, firstRequestOnInstance ${body.firstRequestOnInstance}`,
    `- remoteHost seen by Ktor: \`${body.request.remoteHost}\``,
    `- proxy headers: \`${JSON.stringify(body.request.proxyHeaders)}\``,
  ]);
}

async function warm() {
  const n = int("n", 200);
  await get("/spike/info"); // wake it first
  const times = [];
  const instances = new Set();
  for (let i = 0; i < n; i++) {
    const r = await get("/spike/info");
    times.push(r.ms);
    instances.add(JSON.parse(r.body).instanceId);
  }
  print(`### warm, ${n} sequential requests`, [
    `- latency ${summary(times)}`,
    `- instances seen: ${instances.size}`,
  ]);
}

async function cold() {
  const samples = int("samples", 10);
  const idle = int("idle", 45);
  const rows = ["| # | Latency ms | Cold? | startupMs | Follow-up ms | Follow-up cold? | Same instance? |", "|---|---|---|---|---|---|---|"];
  const coldTimes = [];
  for (let i = 1; i <= samples; i++) {
    process.stderr.write(`sample ${i}/${samples}: idle ${idle} s\n`);
    await sleep(idle * 1000);
    const r = await get("/spike/info");
    const body = JSON.parse(r.body);
    // The follow-up shows whether the platform reuses the instance it just started.
    const follow = await get("/spike/info");
    const followBody = JSON.parse(follow.body);
    if (body.firstRequestOnInstance) coldTimes.push(r.ms);
    rows.push(`| ${i} | ${r.ms} | ${yesNo(body.firstRequestOnInstance)} | ${body.startupMs} | ${follow.ms} | ${yesNo(followBody.firstRequestOnInstance)} | ${yesNo(followBody.instanceId === body.instanceId)} |`);
  }
  print(`### cold, ${samples} samples, ${idle} s idle`, [
    `- cold samples: ${coldTimes.length}/${samples}; cold latency ${summary(coldTimes)}`,
    "",
    ...rows,
  ]);
}

async function concurrency() {
  const c = int("c", 50);
  const hold = int("hold", 5000);
  await get("/spike/info");
  const start = Date.now();
  const results = await Promise.all(
    Array.from({ length: c }, () => get(`/spike/sleep?ms=${hold}`).catch((e) => ({ error: e.message }))),
  );
  const wall = Date.now() - start;
  const perInstance = new Map();
  let errors = 0;
  for (const r of results) {
    if (r.error || r.status !== 200) { errors++; continue; }
    const body = JSON.parse(r.body);
    const entry = perInstance.get(body.instanceId) ?? { count: 0, maxInFlight: 0 };
    entry.count++;
    entry.maxInFlight = Math.max(entry.maxInFlight, body.inFlightAtStart);
    perInstance.set(body.instanceId, entry);
  }
  const counts = [...perInstance.values()];
  print(`### concurrency, ${c} parallel requests holding ${hold} ms`, [
    `- wall time ${wall} ms (≈ ${hold} ms means none queued), errors ${errors}`,
    `- instances used: ${perInstance.size}; requests per instance: ${counts.map((e) => e.count).join(", ")}`,
    `- max in flight on one instance: ${Math.max(0, ...counts.map((e) => e.maxInFlight))}`,
  ]);
}

async function duration() {
  const steps = (opts.steps ?? "60,300,800,1800").split(",").map(Number);
  const rows = ["| Held s | Outcome | Elapsed s |", "|---|---|---|"];
  for (const seconds of steps) {
    process.stderr.write(`holding ${seconds} s\n`);
    const start = Date.now();
    let outcome;
    try {
      const r = await get(`/spike/sleep?ms=${seconds * 1000}`);
      outcome = r.status === 200 ? "200 ok" : `${r.status} ${r.body.slice(0, 80).replace(/\s+/g, " ")}`;
    } catch (e) {
      outcome = `error: ${e.message}`;
    }
    rows.push(`| ${seconds} | ${outcome} | ${Math.round((Date.now() - start) / 1000)} |`);
  }
  print("### duration", rows);
}

async function sse() {
  const seconds = int("seconds", 300);
  const interval = int("interval", 1000);
  const offsets = [];
  let last = null;
  let ended = false;
  const start = Date.now();
  let error = null;
  let buffer = "";
  try {
    await get(`/spike/sse?seconds=${seconds}&intervalMs=${interval}`, (chunk) => {
      const now = Date.now();
      // A chunk may end mid-event: keep the unfinished tail for the next one.
      const events = (buffer + chunk).split("\n\n");
      buffer = events.pop();
      for (const event of events) {
        const data = event.split("\n").find((l) => l.startsWith("data: "));
        if (!data) continue;
        if (event.includes("event: end")) { ended = true; continue; }
        const payload = JSON.parse(data.slice(6));
        // Arrival time minus send time, up to a constant clock offset; the minimum is the baseline.
        offsets.push(now - payload.elapsedMs);
        last = payload;
      }
    });
  } catch (e) {
    error = e.message;
  }
  const open = Math.round((Date.now() - start) / 1000);
  const baseline = Math.min(...offsets);
  const lags = offsets.map((o) => o - baseline);
  print(`### sse, asked for ${seconds} s at ${interval} ms`, [
    `- events received ${lags.length}; last n ${last?.n ?? "-"} at server ${last ? Math.round(last.elapsedMs / 1000) : "-"} s; stream open ${open} s; end event ${ended ? "yes" : "no"}${error ? `; error: ${error}` : ""}`,
    `- arrival lag vs the most punctual event (≈ 0 = streamed live; large = buffered) ${summary(lags)}`,
  ]);
}

async function cpu() {
  const ms = int("ms", 2000);
  await get("/spike/info");
  const r = await get(`/spike/cpu?ms=${ms}`);
  const body = JSON.parse(r.body);
  print(`### cpu, ${ms} ms`, [`- ${body.iterations} iterations on instance ${body.instanceId.slice(0, 8)} (local laptop reference: run it against localhost)`]);
}

// ---------------------------------------------------------------- rate limiting

async function limitLatency() {
  const store = opts.store ?? "redis";
  const n = int("n", 200);
  const run = runId();
  await get("/spike/info"); // wake it first
  const checks = [];
  const client = [];
  const statuses = new Map();
  const instances = new Set();
  let failedOpen = 0;
  let firstCheck;
  for (let i = 0; i < n; i++) {
    // A fresh key per request, so the firewall's fixed limit never trips during a latency run.
    const r = await limit({ store, key: `lat-${run}-${i}`, limit: 1_000_000 });
    statuses.set(r.status, (statuses.get(r.status) ?? 0) + 1);
    client.push(r.ms);
    if (!r.result) continue;
    instances.add(r.result.instanceId);
    if (r.result.failedOpen) { failedOpen++; continue; }
    if (firstCheck === undefined) firstCheck = r.result.checkMs;
    checks.push(r.result.checkMs);
  }
  print(`### limit-latency, store \`${store}\`, ${n} sequential checks`, [
    `- check time inside the function (store round trip): ${summary(checks)}`,
    `- first check (may include opening the connection to the store): ${firstCheck ?? "n/a"} ms`,
    `- client latency (includes the network path to sin1): ${summary(client)}`,
    `- statuses ${formatCounts(statuses)}, failed open ${failedOpen}, instances seen ${instances.size}`,
  ]);
}

async function limitBurst() {
  const store = opts.store ?? "redis";
  const c = int("c", 50);
  const lim = int("limit", 10);
  const window = int("window", 60);
  const timeout = opts.timeout === undefined ? undefined : int("timeout", 200);
  const key = `burst-${runId()}`;
  await get("/spike/info");
  const start = Date.now();
  const results = await Promise.all(
    Array.from({ length: c }, () => limit({ store, key, limit: lim, window, ...(timeout ? { timeoutMs: timeout } : {}) }).catch((e) => ({ error: e.message }))),
  );
  const wall = Date.now() - start;
  const other = await limit({ store, key: `${key}-other`, limit: lim, window });
  const statuses = new Map();
  const perInstance = new Map();
  let failedOpen = 0;
  let fromApp429 = 0;
  const openErrors = new Map();
  const retryAfter = new Set();
  for (const r of results) {
    const label = r.error ? "error" : r.status;
    statuses.set(label, (statuses.get(label) ?? 0) + 1);
    if (!r.result) continue;
    if (r.result.failedOpen) {
      failedOpen++;
      openErrors.set(r.result.error, (openErrors.get(r.result.error) ?? 0) + 1);
    }
    if (r.status === 429 && r.headers["x-spike-limiter"]) fromApp429++;
    if (r.headers?.["retry-after"]) retryAfter.add(r.headers["retry-after"]);
    const entry = perInstance.get(r.result.instanceId) ?? { allowed: 0, denied: 0 };
    r.result.allowed ? entry.allowed++ : entry.denied++;
    perInstance.set(r.result.instanceId, entry);
  }
  const allowed = statuses.get(200) ?? 0;
  const verdict = allowed === lim ? "exact" : allowed > lim ? `**${allowed - lim} over**` : `**${lim - allowed} under**`;
  print(`### limit-burst, store \`${store}\`, ${c} parallel hits on one key, limit ${lim} per ${window} s, timeout ${timeout ?? 200} ms`, [
    `- allowed ${allowed} of ${c} against a limit of ${lim}: ${verdict}; wall time ${wall} ms`,
    `- statuses ${formatCounts(statuses)}; 429s from the app (x-spike-limiter) ${fromApp429}; failed open ${failedOpen}`,
    `- Retry-After values: ${[...retryAfter].join(", ") || "none"}`,
    ...(openErrors.size ? [`- fail-open errors: ${[...openErrors].map(([e, n]) => `${n}× \`${e}\``).join(", ")}`] : []),
    `- instances used: ${perInstance.size}; allowed/denied per instance: ${[...perInstance.values()].map((e) => `${e.allowed}/${e.denied}`).join(", ")}`,
    `- a different key straight after: ${other.status}${other.status === 200 ? " (separate bucket)" : " (**shares the bucket?**)"}`,
  ]);
}

async function limitDown() {
  const n = int("n", 20);
  const timeout = int("timeout", 200);
  await get("/spike/info");
  for (const store of ["redis-down-timeout", "redis-down-error"]) {
    const checks = [];
    const statuses = new Map();
    let failedOpen = 0;
    let sampleError;
    for (let i = 0; i < n; i++) {
      const r = await limit({ store, key: `down-${i}`, timeoutMs: timeout });
      statuses.set(r.status, (statuses.get(r.status) ?? 0) + 1);
      if (!r.result) continue;
      checks.push(r.result.checkMs);
      if (r.result.failedOpen) failedOpen++;
      sampleError ??= r.result.error;
    }
    print(`### limit-down, store \`${store}\`, ${n} sequential checks, timeout ${timeout} ms`, [
      `- statuses ${formatCounts(statuses)}; failed open ${failedOpen} of ${n}`,
      `- time spent on the broken store: ${summary(checks)}`,
      `- error: \`${sampleError ?? "none"}\``,
    ]);
  }
}

async function wafEdge() {
  const n = int("n", 40);
  const lim = int("limit", 20);
  const window = int("window", 10);
  const statuses = [];
  let first429;
  for (let i = 0; i < n; i++) {
    const r = await get("/spike/waf");
    statuses.push(r.status);
    if (r.status === 429 && !first429) first429 = { index: i + 1, ...r };
  }
  process.stderr.write(`waiting ${window + 1} s for the window to end\n`);
  await sleep((window + 1) * 1000);
  const after = await get("/spike/waf");
  const counts = new Map();
  for (const s of statuses) counts.set(s, (counts.get(s) ?? 0) + 1);
  const passed = statuses.indexOf(429) === -1 ? n : statuses.indexOf(429);
  const shown = ["retry-after", "x-vercel-mitigated", "x-vercel-id", "content-type", "server", "cache-control"];
  print(`### waf-edge, ${n} sequential requests to /spike/waf, rule ${lim} per ${window} s per IP`, [
    `- statuses ${formatCounts(counts)}; requests before the first 429: ${passed} (rule: ${lim})`,
    first429
      ? `- first 429 (request ${first429.index}): headers \`${JSON.stringify(Object.fromEntries(shown.filter((h) => first429.headers[h]).map((h) => [h, first429.headers[h]])))}\`; body \`${first429.body.slice(0, 160).replace(/\s+/g, " ")}\``
      : "- no 429 at all: the rule didn't apply to these requests",
    `- after waiting ${window + 1} s: ${after.status}`,
  ]);
}

function limit(params) {
  const query = new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)]));
  return get(`/spike/limit?${query}`).then((r) => {
    let result;
    try { result = JSON.parse(r.body); } catch { result = undefined; }
    return { ...r, result };
  });
}

function runId() {
  return Date.now().toString(36);
}

function formatCounts(map) {
  return [...map.entries()].map(([k, v]) => `${k}×${v}`).join(", ");
}

// ---------------------------------------------------------------- helpers

function get(path, onChunk) {
  const url = new URL(path, baseUrl);
  const client = url.protocol === "https:" ? https : http;
  const headers = { "user-agent": "s1-spike-measure" };
  if (bypass) headers["x-vercel-protection-bypass"] = bypass;
  return new Promise((resolve, reject) => {
    const start = performance.now();
    // Keep-alive agents, as a mobile client reuses its connection; the first request pays TLS.
    const req = client.get(url, { headers, agent: url.protocol === "https:" ? httpsAgent : httpAgent }, (res) => {
      res.setEncoding("utf8");
      let body = "";
      res.on("data", (chunk) => (onChunk ? onChunk(chunk) : (body += chunk)));
      res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, body, ms: Math.round(performance.now() - start) }));
      res.on("error", reject);
    });
    // A middlebox on the client's path drops TCP connections idle for ~40 s (found in S-1); keepalive
    // probes every 10 s keep a held /spike/sleep request open so the platform's limit is what's measured.
    req.on("socket", (socket) => socket.setKeepAlive(true, 10_000));
    req.on("error", reject);
  });
}

function summary(values) {
  if (values.length === 0) return "n/a";
  const sorted = [...values].sort((a, b) => a - b);
  const p = (q) => sorted[Math.min(sorted.length - 1, Math.ceil((q / 100) * sorted.length) - 1)];
  return `p50 ${p(50)} · p95 ${p(95)} · p99 ${p(99)} · max ${sorted.at(-1)} ms (n=${values.length})`;
}

function parseOptions(args) {
  const out = {};
  for (let i = 0; i < args.length; i += 2) out[args[i].replace(/^--/, "")] = args[i + 1];
  return out;
}

function int(name, fallback) {
  return opts[name] === undefined ? fallback : Number.parseInt(opts[name], 10);
}

function yesNo(value) {
  return value ? "yes" : "no";
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function print(title, lines) {
  console.log([title, ...lines, ""].join("\n"));
}
