#!/usr/bin/env node
// S-3 measurements against Ably. Node 22+, dependency: ably (npm install).
//
//   node --env-file=.env.spike scripts/measure.mjs <mode> [--option value ...]
//
// Modes (each prints a Markdown block to paste into README.md "Results"):
//   info                                         connect time, REST round trip, which Ably endpoint
//   delay      --layout realistic|wide           publish → receive delay over --subs subscribers
//              --subs 190 --rate 30 --seconds 120
//              realistic: --restaurants (default subs / 19) staff channels × 4 devices, the rest session channels
//              × 2 devices; one event = publish to its session channel + its restaurant's staff
//              channel, like ADR-0003. wide: one channel, every subscriber on it (worst fan-out).
//   isolation                                    what a subscribe-only token for one channel can't do
//   idle       --minutes 10                      one quiet connection: does it survive the client
//                                                network's ~40 s idle cut, then still receive?
//
// The free plan allows 200 connections, 200 channels and 500 messages/s, counting each publish
// and each delivery. `delay` estimates its rate and total before it starts and refuses to go
// over 450/s or 1M messages unless --force is given.
//
// Publisher and subscribers run in this one process, so every delay is read from the same clock
// (performance.now()); there is no clock skew to correct. The publisher uses the REST API with
// the API key, as the Ktor app will after commit. Subscribers get subscribe-only tokens for
// exactly one channel through an authCallback, as the app's token endpoint will hand them out.
// ABLY_API_KEY comes from .env.spike and is never printed.

import * as Ably from "ably";
import { readFileSync } from "node:fs";

const [mode, ...rest] = process.argv.slice(2);
const opts = parseOptions(rest);
const key = process.env.ABLY_API_KEY;
const modes = { info, delay, isolation, idle };
if (!modes[mode]) {
  console.error(`usage: node --env-file=.env.spike scripts/measure.mjs <${Object.keys(modes).join("|")}> [--option value]`);
  process.exit(2);
}
if (!key) {
  console.error("ABLY_API_KEY is missing: put it in .env.spike and run with node --env-file=.env.spike");
  process.exit(2);
}
const restClient = new Ably.Rest({ key });
const runId = Date.now().toString(36);
await modes[mode]();
process.exit(0);

// ---------------------------------------------------------------- modes

async function info() {
  const rtts = [];
  for (let i = 0; i < 10; i++) {
    const t = performance.now();
    await restClient.time();
    rtts.push(performance.now() - t);
  }
  const connects = [];
  let endpoint = "unknown";
  for (let i = 0; i < 5; i++) {
    const t = performance.now();
    const rt = subscriber(`session.${runId}-info`, `info-${i}`);
    await whenConnected(rt);
    connects.push(performance.now() - t);
    if (i === 0) endpoint = describeEndpoint(rt);
    rt.close();
  }
  print("info", [
    `- ably-js ${JSON.parse(readFileSync(new URL("../node_modules/ably/package.json", import.meta.url))).version}, client time ${new Date().toISOString()}`,
    `- REST round trip (\`time()\`, n 10): ${summary(rtts)}`,
    `- Realtime connect incl. token (n 5): ${summary(connects)}`,
    `- Endpoint: ${endpoint}`,
  ]);
}

async function delay() {
  const layout = opts.layout ?? "realistic";
  const subs = num("subs", 190);
  const seconds = num("seconds", 120);
  const rate = num("rate", layout === "wide" ? 1 : 30);
  const channels = layout === "wide" ? wideLayout(subs) : realisticLayout(subs, num("restaurants", Math.max(1, Math.round(subs / 19))));
  const perEvent = channels.events[0].reduce((n, ch) => n + 1 + channels.members.get(ch), 0);
  const perSecond = perEvent * rate;
  const total = perSecond * seconds;
  console.error(`${subs} subscribers on ${channels.members.size} channels; ≈ ${perSecond} messages/s, ≈ ${total} in total`);
  if ((perSecond > 450 || total > 1_000_000) && !opts.force) {
    console.error("over the free plan's budget (450/s or 1M per run); lower --rate/--seconds or pass --force");
    process.exit(2);
  }

  // Connect in batches so the connection rate stays polite.
  const clients = [];
  const received = new Map(); // client index → { channel, seqs: Map(seq → delay) }
  const stateChanges = [];
  const connectTimes = [];
  const batch = num("connectRate", 20);
  for (let i = 0; i < subs; i += batch) {
    await Promise.all(channels.assign.slice(i, i + batch).map(async (ch, j) => {
      const idx = i + j;
      const t = performance.now();
      const rt = subscriber(ch, `sub-${idx}`);
      const rec = { channel: ch, seqs: new Map(), dups: 0 };
      received.set(idx, rec);
      rt.connection.on((c) => { if (c.previous === "connected") stateChanges.push(`${idx}: ${c.previous} → ${c.current} ${c.reason?.code ?? ""}`); });
      await whenConnected(rt);
      await rt.channels.get(ch).subscribe((msg) => {
        const d = performance.now() - msg.data.t0;
        if (rec.seqs.has(msg.data.seq)) rec.dups++; else rec.seqs.set(msg.data.seq, d);
      });
      connectTimes.push(performance.now() - t);
      clients.push(rt);
    }));
    await sleep(1000);
  }
  console.error(`${clients.length} connected and attached; publishing for ${seconds} s`);

  // Publish: each event goes to its channels in parallel, timed per REST call.
  const published = new Map(); // channel → [seq]
  const publishTimes = [];
  const publishErrors = [];
  const pending = [];
  let seq = 0;
  const start = performance.now();
  const interval = 1000 / rate;
  while (performance.now() - start < seconds * 1000) {
    const event = channels.events[seq % channels.events.length];
    const s = seq++;
    for (const ch of event) {
      (published.get(ch) ?? published.set(ch, []).get(ch)).push(s);
      const t0 = performance.now();
      pending.push(restClient.channels.get(ch).publish("evt", { seq: s, t0 })
        .then(() => publishTimes.push(performance.now() - t0))
        .catch((e) => publishErrors.push(`${e.code ?? ""} ${e.message?.slice(0, 80)}`)));
    }
    const next = start + seq * interval;
    await sleep(Math.max(0, next - performance.now()));
  }
  await Promise.all(pending);
  await sleep(5000); // drain

  const delays = [];
  let expected = 0, lost = 0, dups = 0;
  for (const rec of received.values()) {
    const sent = published.get(rec.channel) ?? [];
    expected += sent.length;
    for (const s of sent) if (!rec.seqs.has(s)) lost++;
    delays.push(...rec.seqs.values());
    dups += rec.dups;
  }
  for (const rt of clients) rt.close();
  const within1s = delays.filter((d) => d <= 1000).length;
  print(`delay --layout ${layout} --subs ${subs} --rate ${rate} --seconds ${seconds}`, [
    `- ${clients.length} subscribers on ${channels.members.size} channels, ${seq} events, ${[...published.values()].reduce((n, a) => n + a.length, 0)} publishes; ≈ ${perSecond} Ably messages/s`,
    `- **Publish → receive: ${summary(delays)}**; ${(100 * within1s / Math.max(1, delays.length)).toFixed(2)} % within 1 s`,
    `- Deliveries: ${delays.length} of ${expected} expected, **${lost} lost**, ${dups} duplicates`,
    `- REST publish call (the time the app's request would spend publishing): ${summary(publishTimes)}; ${publishErrors.length} errors${publishErrors.length ? ` (first: ${publishErrors[0]})` : ""}`,
    `- Connect + attach per subscriber: ${summary(connectTimes)}`,
    `- Connection drops during the run: ${stateChanges.length}${stateChanges.length ? ` (${stateChanges.slice(0, 5).join("; ")})` : ""}`,
  ]);
}

async function isolation() {
  const a = `session.${runId}-a`;
  const b = `session.${runId}-b`;
  const results = [];
  const check = (name, ok, detail) => results.push(`| ${name} | ${ok ? "pass" : "**FAIL**"} | ${detail} |`);

  // Like the app's token endpoint: the capability comes from the server's session, never from
  // what the client asks for.
  const rt = subscriber(a, "guest-a");
  await whenConnected(rt);

  const chA = rt.channels.get(a);
  const got = new Promise((res) => chA.subscribe((m) => res(m)));
  await chA.attach();
  await restClient.channels.get(a).publish("evt", { seq: 1, t0: performance.now() });
  const m = await withTimeout(got, 10_000).catch(() => null);
  check("Subscribe to its own channel", !!m, m ? "attached and received" : "nothing received in 10 s");

  for (const [name, fn] of [
    ["Attach another session's channel", () => rt.channels.get(b).attach()],
    ["Attach the restaurant's staff channel", () => rt.channels.get(`restaurant.${runId}.staff`).attach()],
    ["Publish on its own channel", () => chA.publish("evt", { forged: true })],
    ["Enter presence on its own channel", () => chA.presence.enter()],
    ["Read its channel's history", () => chA.history()],
    ["List channels (REST `/channels`)", () => checkedRequest(rt, "get", "/channels")],
  ]) {
    const r = await expectRefused(fn);
    check(name, r.refused, r.detail);
  }

  // Asking for more on re-auth: the callback ignores the request, so B must stay closed.
  await rt.auth.authorize({ capability: JSON.stringify({ [b]: ["subscribe"] }) }).catch(() => {});
  const esc = await expectRefused(() => rt.channels.get(b).attach());
  check("Ask for another channel on re-auth, then attach it", esc.refused, esc.detail);
  rt.close();

  // A bare token with no way to renew must stop working when it expires.
  const td = await restClient.auth.requestToken({ capability: JSON.stringify({ [a]: ["subscribe"] }), clientId: "guest-exp", ttl: 20_000 });
  const bare = new Ably.Realtime({ tokenDetails: td, echoMessages: false });
  await whenConnected(bare);
  await sleep(40_000);
  const state = bare.connection.state;
  const reason = bare.connection.errorReason;
  check("Token expires (ttl 20 s, checked at 40 s, no renewal)", state !== "connected", `state ${state}${reason ? `, ${reason.code} ${reason.message?.slice(0, 60)}` : ""}`);
  bare.close();

  print("isolation", ["| Check | Result | Detail |", "|---|---|---|", ...results]);
}

async function idle() {
  const minutes = num("minutes", 10);
  const ch = `session.${runId}-idle`;
  const changes = [];
  const rt = subscriber(ch, "idle");
  rt.connection.on((c) => changes.push(`${((performance.now() - t0) / 1000).toFixed(0)} s ${c.previous} → ${c.current}${c.reason ? ` ${c.reason.code}` : ""}`));
  const t0 = performance.now();
  await whenConnected(rt);
  const got = new Promise((res) => rt.channels.get(ch).subscribe((m) => res(performance.now() - m.data.t0)));
  await rt.channels.get(ch).attach();
  console.error(`connected; idle for ${minutes} min`);
  await sleep(minutes * 60_000);
  const stateBefore = rt.connection.state;
  await restClient.channels.get(ch).publish("evt", { seq: 1, t0: performance.now() });
  const d = await withTimeout(got, 15_000).catch(() => null);
  rt.close();
  print(`idle --minutes ${minutes}`, [
    `- State after ${minutes} min idle: **${stateBefore}**; message after the idle period: ${d == null ? "**not received in 15 s**" : `received in ${d.toFixed(0)} ms`}`,
    `- State changes: ${changes.length ? changes.join("; ") : "none"}`,
  ]);
}

// ---------------------------------------------------------------- Ably helpers

// A subscriber whose token is scoped, subscribe-only, to exactly one channel.
function subscriber(channel, clientId) {
  return new Ably.Realtime({
    clientId,
    echoMessages: false,
    authCallback: (_params, cb) => {
      restClient.auth.createTokenRequest({ clientId, ttl: 3_600_000, capability: JSON.stringify({ [channel]: ["subscribe"] }) })
        .then((tr) => cb(null, tr), (e) => cb(e, null));
    },
  });
}

function whenConnected(rt, ms = 20_000) {
  return new Promise((resolve, reject) => {
    if (rt.connection.state === "connected") return resolve();
    const timer = setTimeout(() => reject(new Error(`connect timeout, state ${rt.connection.state}`)), ms);
    rt.connection.on((c) => {
      if (c.current === "connected") { clearTimeout(timer); resolve(); }
      if (c.current === "failed" || c.current === "suspended") { clearTimeout(timer); reject(c.reason ?? new Error(c.current)); }
    });
  });
}

// `request` resolves with success=false instead of rejecting.
async function checkedRequest(rt, method, path) {
  const res = await rt.request(method, path, 3, {}, null, {});
  if (!res.success) throw Object.assign(new Error(res.errorMessage ?? `HTTP ${res.statusCode}`), { code: res.errorCode ?? res.statusCode });
  return res;
}

async function expectRefused(fn) {
  try {
    await withTimeout(Promise.resolve().then(fn), 10_000);
    return { refused: false, detail: "allowed" };
  } catch (e) {
    if (e.code === "TIMEOUT") return { refused: false, detail: "no answer in 10 s (inconclusive)" };
    return { refused: true, detail: `refused: ${e.code ?? ""} ${String(e.message ?? e).slice(0, 80)}` };
  }
}

// Best effort: ably-js doesn't expose the server in its public API.
function describeEndpoint(rt) {
  try {
    const cm = rt.connection.connectionManager;
    const host = cm?.activeProtocol?.transport?.params?.host ?? cm?.activeProtocol?.getTransport?.()?.params?.host;
    const server = cm?.connectionDetails?.serverId;
    return [host && `host ${host}`, server && `serverId ${server}`].filter(Boolean).join(", ") || "not exposed by this ably-js version";
  } catch {
    return "not exposed by this ably-js version";
  }
}

// ---------------------------------------------------------------- layouts

function realisticLayout(subs, restaurants) {
  const staffPer = 4, sessionPer = 2;
  const assign = [];
  const staff = [];
  for (let r = 0; r < restaurants; r++) {
    staff.push(`restaurant.${runId}-${r}.staff`);
    for (let k = 0; k < staffPer; k++) assign.push(staff[r]);
  }
  const events = [];
  const sessions = Math.floor((subs - assign.length) / sessionPer);
  if (sessions < 1) throw new Error(`--subs ${subs} leaves no session channels after ${assign.length} staff devices`);
  for (let s = 0; s < sessions; s++) {
    const ch = `session.${runId}-${s}`;
    for (let k = 0; k < sessionPer; k++) assign.push(ch);
    events.push([ch, staff[s % restaurants]]);
  }
  return withMembers(assign, events);
}

function wideLayout(subs) {
  const ch = `restaurant.${runId}-wide.staff`;
  return withMembers(Array(subs).fill(ch), [[ch]]);
}

function withMembers(assign, events) {
  const members = new Map();
  for (const ch of assign) members.set(ch, (members.get(ch) ?? 0) + 1);
  return { assign, events, members };
}

// ---------------------------------------------------------------- utilities

function summary(xs) {
  if (!xs.length) return "no samples";
  const s = [...xs].sort((a, b) => a - b);
  const p = (q) => s[Math.min(s.length - 1, Math.ceil(q * s.length) - 1)].toFixed(0);
  return `n ${s.length}, p50 ${p(0.5)} · p95 ${p(0.95)} · p99 ${p(0.99)} · max ${s.at(-1).toFixed(0)} ms`;
}

function print(title, lines) {
  console.log(`\n#### \`${title}\` (${new Date().toISOString()})\n${lines.join("\n")}\n`);
}

function withTimeout(p, ms) {
  let timer;
  return Promise.race([p, new Promise((_, rej) => { timer = setTimeout(() => rej(Object.assign(new Error("timeout"), { code: "TIMEOUT" })), ms); })])
    .finally(() => clearTimeout(timer));
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

function num(name, dflt) { return opts[name] == null ? dflt : Number(opts[name]); }

function parseOptions(args) {
  const o = {};
  for (let i = 0; i < args.length; i++) {
    if (!args[i].startsWith("--")) continue;
    const k = args[i].slice(2);
    const v = args[i + 1];
    if (v == null || v.startsWith("--")) o[k] = true; else { o[k] = v; i++; }
  }
  return o;
}
