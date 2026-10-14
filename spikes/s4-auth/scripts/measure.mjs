#!/usr/bin/env node
// S-4 measurements against a deployed spike (or http://localhost:8080). Node 18+, no dependencies.
//
//   node --env-file=.env.spike scripts/measure.mjs <mode> <baseUrl> [--option value ...]
//
// Modes (each prints a Markdown block to paste into README.md "Results"):
//   info                                     /spike/info: region, CPUs, heap, memory, startupMs
//   signin   --lib bc --n 100 --c 1          Q1: sign-ins as the waiter, --c at a time; time inside
//                                            the function (wait for a hash slot, hash, total)
//   compare  --n 60                          Q2: signin with bc, then p4j, one at a time
//   burst    --lib bc --n 40 --c 10          Q1: sign-ins 10 at a time: queueing at the hash gate,
//                                            peak memory per instance
//   tokens                                   Q3: every token case against the staff and admin
//                                            routes, incl. the admin TOTP step; pass/fail table
//   cold     --samples 3 --idle 360          Q1: after --idle s, the first sign-in on a fresh
//                                            instance (Argon2id before the JIT has warmed up)
//
// The exit criterion is measured inside the function (spike.totalMs), so the client's distance to
// sin1 doesn't count; the client round trip is printed for reference.
//
// Deployment Protection: set VERCEL_AUTOMATION_BYPASS_SECRET (Project Settings → Deployment
// Protection → Protection Bypass for Automation) and it is sent as x-vercel-protection-bypass.

import { createHmac } from "node:crypto";
import http from "node:http";
import https from "node:https";

// Spike-only accounts (src/main/kotlin/spike/Seeds.kt). Public on purpose: they guard nothing.
const PASSWORD = "spike only: correct horse battery staple";
const ADMIN_TOTP_SECRET_HEX = "5e8a1c3f9b27d4406e1f8ac2b39d75e0c4a18f62";
const WAITER = "waiter@a.test";
const ADMIN = "admin@platform.test";

const [mode, baseUrl, ...rest] = process.argv.slice(2);
const opts = parseOptions(rest);
const bypass = process.env.VERCEL_AUTOMATION_BYPASS_SECRET;
const httpAgent = new http.Agent({ keepAlive: true, keepAliveMsecs: 10_000 });
const httpsAgent = new https.Agent({ keepAlive: true, keepAliveMsecs: 10_000 });

const modes = { info, signin, compare, burst, tokens, cold };
if (!modes[mode] || !baseUrl) {
  console.error(`usage: node scripts/measure.mjs <${Object.keys(modes).join("|")}> <baseUrl> [--option value]`);
  process.exit(2);
}
await modes[mode]();

// ---------------------------------------------------------------- modes

async function info() {
  const b = await json("GET", "/spike/info");
  print(`### info (${new Date().toISOString()})`, [
    `- region \`${b.region ?? "?"}\`, Java ${b.javaVersion}, ${b.cpus} CPU, max heap ${b.maxHeapMb} MB, heap used ${b.heapUsedMb} MB`,
    `- process memory: ${b.memory ? `RSS ${b.memory.rssMb} MB, peak ${b.memory.peakRssMb} MB` : "n/a (not Linux)"}`,
    `- instance ${short(b.instanceId)}, startupMs ${b.startupMs}, uptimeMs ${b.uptimeMs}, requests ${b.requestsServed}`,
  ]);
}

async function signin() {
  const lib = opts.lib ?? "bc";
  const r = await runSignIns(lib, int("n", 100), int("c", 1));
  print(`### signin, ${lib}, ${r.n} sign-ins, ${r.c} at a time`, r.lines);
}

async function compare() {
  const n = int("n", 60);
  for (const lib of ["bc", "p4j"]) {
    const r = await runSignIns(lib, n, 1);
    print(`### compare: ${lib}, ${n} sign-ins, one at a time`, r.lines);
  }
}

async function burst() {
  const lib = opts.lib ?? "bc";
  const r = await runSignIns(lib, int("n", 40), int("c", 10));
  print(`### burst, ${lib}, ${r.n} sign-ins, ${r.c} at a time`, r.lines);
}

async function tokens() {
  const rows = ["| Case | Route | Expected | Got | Pass |", "|---|---|---|---|---|"];
  let failures = 0;
  const check = async (name, path, token, expected) => {
    const r = await request("GET", path, undefined, token);
    const pass = r.status === expected;
    if (!pass) failures++;
    rows.push(`| ${name} | \`${path}\` | ${expected} | ${r.status} | ${pass ? "yes" : "**no**"} |`);
  };

  const staff = await signIn(WAITER, PASSWORD, "bc");
  if (staff.status !== 200) throw new Error(`waiter sign-in → ${staff.status} ${staff.body.slice(0, 200)}`);
  const staffToken = JSON.parse(staff.body).access_token;
  const cases = await json("GET", "/spike/test-tokens");

  await check("valid staff token", "/v1/staff/me", staffToken, 200);
  await check("no token", "/v1/staff/me", undefined, 401);
  await check("not a JWT", "/v1/staff/me", "not-a-jwt", 401);
  await check("expired (15 min ago)", "/v1/staff/me", cases.expired, 401);
  await check("signed with the previous staff key (rotation overlap)", "/v1/staff/me", cases.previousKey, 200);
  await check("signed with the guest key", "/v1/staff/me", cases.guestKey, 401);
  await check("wrong issuer", "/v1/staff/me", cases.wrongIssuer, 401);
  await check("MFA token used as an access token", "/v1/staff/me", cases.mfaTokenAsAccess, 401);
  await check("payload changed, signature kept", "/v1/staff/me", forgeSubject(staffToken), 401);
  await check("`alg: none`", "/v1/staff/me", algNone(staffToken), 401);
  await check("`alg: HS256` with a guessed secret", "/v1/staff/me", hs256(staffToken), 401);
  await check("staff token on the admin route (wrong audience)", "/v1/admin/me", staffToken, 401);
  await check("admin audience without `otp` in `amr`", "/v1/admin/me", cases.adminWithoutOtp, 403);

  // The admin's two steps: password, then a TOTP code made here from the same secret.
  const first = await signIn(ADMIN, PASSWORD, "bc");
  const mfaToken = JSON.parse(first.body).mfa_token;
  rows.push(`| admin sign-in returns \`mfa_required\` | \`/v1/auth/sign-in\` | 200 + mfa_token | ${first.status}${mfaToken ? " + mfa_token" : ""} | ${first.status === 200 && mfaToken ? "yes" : "**no**"} |`);
  if (!(first.status === 200 && mfaToken)) failures++;
  const wrong = await post("/v1/auth/mfa", { mfa_token: mfaToken, code: "000000" });
  rows.push(`| wrong TOTP code | \`/v1/auth/mfa\` | 401 | ${wrong.status} | ${wrong.status === 401 ? "yes" : "**no**"} |`);
  if (wrong.status !== 401) failures++;
  const second = JSON.parse((await signIn(ADMIN, PASSWORD, "bc")).body).mfa_token;
  const done = await post("/v1/auth/mfa", { mfa_token: second, code: totp(ADMIN_TOTP_SECRET_HEX) });
  rows.push(`| right TOTP code | \`/v1/auth/mfa\` | 200 | ${done.status} | ${done.status === 200 ? "yes" : "**no**"} |`);
  if (done.status !== 200) failures++;
  if (done.status === 200) {
    const adminToken = JSON.parse(done.body).access_token;
    await check("admin token with `otp`", "/v1/admin/me", adminToken, 200);
    await check("admin token on the staff route (wrong audience)", "/v1/staff/me", adminToken, 401);
  }

  print(`### tokens (${new Date().toISOString()}): ${failures === 0 ? "all pass" : `**${failures} failed**`}`, rows);
  if (failures > 0) process.exitCode = 1;
}

async function cold() {
  const samples = int("samples", 3);
  const idle = int("idle", 360);
  const rows = ["| # | Fresh instance? | startupMs | First sign-in: hash ms | total ms | client ms |", "|---|---|---|---|---|---|"];
  for (let i = 1; i <= samples; i++) {
    process.stderr.write(`sample ${i}/${samples}: idle ${idle} s\n`);
    await sleep(idle * 1000);
    const r = await signIn(WAITER, PASSWORD, opts.lib ?? "bc");
    const s = JSON.parse(r.body).spike;
    const info = await json("GET", "/spike/info");
    const same = info.instanceId === s.instanceId;
    rows.push(`| ${i} | ${s.firstRequestOnInstance ? "yes" : "no"} | ${same ? info.startupMs : "other instance"} | ${s.hashMs.toFixed(1)} | ${s.totalMs.toFixed(1)} | ${r.ms} |`);
  }
  print(`### cold, ${samples} samples, ${idle} s idle`, rows);
}

// ---------------------------------------------------------------- helpers

async function runSignIns(lib, n, c) {
  await signIn(WAITER, PASSWORD, lib); // warm an instance
  const results = await pool(n, c, () => signIn(WAITER, PASSWORD, lib));
  const total = [], hash = [], wait = [], client = [], instances = new Map(), errors = new Map();
  for (const r of results) {
    if (r.error || r.status !== 200) {
      count(errors, r.error ?? `${r.status} ${r.body.slice(0, 80)}`);
      continue;
    }
    const s = JSON.parse(r.body).spike;
    total.push(round(s.totalMs));
    hash.push(round(s.hashMs));
    wait.push(round(s.waitMs));
    client.push(r.ms);
    const peak = s.memory?.peakRssMb ?? null;
    instances.set(s.instanceId, Math.max(instances.get(s.instanceId) ?? 0, peak ?? 0));
  }
  const peaks = [...instances.values()].filter((v) => v > 0);
  return {
    n,
    c,
    lines: [
      `- sign-in inside the function (total): ${summary(total)}`,
      `- of which Argon2id: ${summary(hash)}; waiting for a hash slot: ${summary(wait)}`,
      `- client round trip (includes the client's distance to sin1): ${summary(client)}`,
      `- instances: ${instances.size}${peaks.length ? `; peak RSS per instance ${Math.min(...peaks)}–${Math.max(...peaks)} MB` : ""}`,
      ...(errors.size ? [`- errors: ${[...errors.entries()].map(([m, k]) => `${k}× ${m}`).join("; ")}`] : []),
    ],
  };
}

function signIn(email, password, lib) {
  return post(`/v1/auth/sign-in?lib=${lib}`, { email, password });
}

function post(path, body) {
  return request("POST", path, body);
}

/** RFC 6238: HMAC-SHA1 over the 30 s step, 6 digits. */
function totp(secretHex, nowMs = Date.now()) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(nowMs / 1000 / 30)));
  const h = createHmac("sha1", Buffer.from(secretHex, "hex")).update(counter).digest();
  const offset = h[h.length - 1] & 0x0f;
  return String((h.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, "0");
}

function forgeSubject(token) {
  const [header, payload, signature] = token.split(".");
  const claims = JSON.parse(Buffer.from(payload, "base64url").toString());
  claims.sub = "00000000-0000-0000-0000-0000000000a9";
  return `${header}.${Buffer.from(JSON.stringify(claims)).toString("base64url")}.${signature}`;
}

function algNone(token) {
  const payload = token.split(".")[1];
  return `${Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url")}.${payload}.`;
}

function hs256(token) {
  const [header, payload] = token.split(".");
  const kid = JSON.parse(Buffer.from(header, "base64url").toString()).kid;
  const h = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT", kid })).toString("base64url");
  const signature = createHmac("sha256", "secret").update(`${h}.${payload}`).digest("base64url");
  return `${h}.${payload}.${signature}`;
}

/** Runs task(i) for i in 0..n-1, at most c at a time. Failures become { error }. */
async function pool(n, c, task) {
  const results = new Array(n);
  let next = 0;
  const worker = async () => {
    while (next < n) {
      const i = next++;
      try {
        results[i] = await task(i);
      } catch (e) {
        results[i] = { error: e.message };
      }
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

function request(method, path, body, bearer) {
  const url = new URL(path, baseUrl);
  const client = url.protocol === "https:" ? https : http;
  const payload = body === undefined ? "" : JSON.stringify(body);
  const headers = { "user-agent": "s4-spike-measure", "content-length": Buffer.byteLength(payload) };
  if (body !== undefined) headers["content-type"] = "application/json";
  if (bearer !== undefined) headers.authorization = `Bearer ${bearer}`;
  if (bypass) headers["x-vercel-protection-bypass"] = bypass;
  return new Promise((resolve, reject) => {
    const start = performance.now();
    const req = client.request(url, { method, headers, agent: url.protocol === "https:" ? httpsAgent : httpAgent }, (res) => {
      res.setEncoding("utf8");
      let text = "";
      res.on("data", (chunk) => (text += chunk));
      res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, body: text, ms: Math.round(performance.now() - start) }));
      res.on("error", reject);
    });
    // The client network drops TCP connections idle for ~40 s (found in S-1).
    req.on("socket", (socket) => socket.setKeepAlive(true, 10_000));
    req.on("error", reject);
    req.end(payload);
  });
}

function count(map, key) {
  map.set(key, (map.get(key) ?? 0) + 1);
}

function round(v) {
  return Math.round(v * 10) / 10;
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
