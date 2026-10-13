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

const modes = { info, warm, cold, concurrency, duration, sse, cpu };
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
