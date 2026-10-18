#!/usr/bin/env node
// Q-19 spike: what Vercel's edge does with conditional requests, case by case.
//
//   node --env-file=.env.spike scripts/probe.mjs <deployment-url> [--repeat 3]
//
// .env.spike (ignored by git) holds VERCEL_AUTOMATION_BYPASS_SECRET, sent as
// x-vercel-protection-bypass. Against http://localhost:8080 it needs none: a dry run of the cases.
// Prints a Markdown table for the README's Results and exits 1 if any case with an expectation
// fails. "edge" means the answer didn't come from the probe (an X-Vercel-Error header, or a body
// without source=service).

const args = process.argv.slice(2);
const base = args.find((a) => !a.startsWith("--"))?.replace(/\/$/, "");
const repeatAt = args.indexOf("--repeat");
const repeat = repeatAt >= 0 ? Number(args[repeatAt + 1]) : 3;
if (!base) {
  console.error("usage: probe.mjs <deployment-url> [--repeat 3]");
  process.exit(2);
}
const bypass = process.env.VERCEL_AUTOMATION_BYPASS_SECRET;
const run = Date.now().toString(36);

// expect: "service <status>" = the probe's own answer must arrive unchanged; null = recorded only.
const cases = [
  // D-1 as the service sends it today, and the mechanism behind it.
  { id: "C1", what: "D-1 today: PATCH, stale If-Match, 200 with the new ETag", method: "PATCH", ifMatch: '"1"', query: { etag: '"2"' }, expect: null },
  { id: "C2", what: "PATCH, If-Match equal to the response ETag", method: "PATCH", ifMatch: '"2"', query: { etag: '"2"' }, expect: null },
  // Option (a): PATCH responses without an ETag.
  { id: "F1", what: "fix: PATCH, If-Match, 200 without ETag", method: "PATCH", ifMatch: '"1"', query: {}, expect: "service 200" },
  { id: "F2", what: "fix: PATCH, stale If-Match, the service's 412 problem", method: "PATCH", ifMatch: '"1"', query: { status: 412, problem: true }, expect: "service 412" },
  { id: "F3", what: "fix: PATCH without If-Match, the service's 428 problem", method: "PATCH", ifMatch: null, query: { status: 428, problem: true }, expect: "service 428" },
  { id: "F4", what: "fix: PATCH, If-Match *, the service's 412 problem", method: "PATCH", ifMatch: "*", query: { status: 412, problem: true }, expect: "service 412" },
  { id: "F5", what: "fix: PATCH, If-Match, 200 without ETag or Cache-Control", method: "PATCH", ifMatch: '"1"', query: { cache: "none" }, expect: "service 200" },
  { id: "F6", what: "fix: PATCH, If-Match, the service's 404 problem", method: "PATCH", ifMatch: '"1"', query: { status: 404, problem: true }, expect: "service 404" },
  // What the service keeps: POST and DELETE with If-Match, and the menu's revalidation.
  { id: "P1", what: "POST, stale If-Match, 200 with the new ETag (move, status, close…)", method: "POST", ifMatch: '"1"', query: { etag: '"2"' }, expect: "service 200" },
  { id: "P2", what: "DELETE, If-Match, 204", method: "DELETE", ifMatch: '"1"', query: { status: 204 }, expect: "service 204" },
  { id: "G1", what: "GET, If-None-Match, the service's 304", method: "GET", ifNoneMatch: '"m1"', query: { status: 304, etag: '"m1"', cache: "private, no-cache" }, expect: "service 304" },
  { id: "G2", what: "GET, If-None-Match equal, the service's 200 with that ETag", method: "GET", ifNoneMatch: '"m1"', query: { etag: '"m1"', cache: "private, no-cache" }, expect: null },
  // Not used by the contract; recorded so a future endpoint doesn't need another spike.
  { id: "X1", what: "PUT, stale If-Match, 200 with the new ETag", method: "PUT", ifMatch: '"1"', query: { etag: '"2"' }, expect: null },
];

function headers(extra = {}) {
  const h = { ...extra };
  if (bypass) h["x-vercel-protection-bypass"] = bypass;
  return h;
}

async function once(c, n) {
  const id = `${run}-${c.id}-${n}`;
  const q = new URLSearchParams({ id, ...Object.fromEntries(Object.entries(c.query).map(([k, v]) => [k, String(v)])) });
  const h = {};
  if (c.ifMatch) h["If-Match"] = c.ifMatch;
  if (c.ifNoneMatch) h["If-None-Match"] = c.ifNoneMatch;
  const hasBody = c.method === "PATCH" || c.method === "POST" || c.method === "PUT";
  if (hasBody) h["Content-Type"] = "application/json";
  const res = await fetch(`${base}/probe?${q}`, { method: c.method, headers: headers(h), body: hasBody ? "{}" : undefined });
  const text = await res.text();
  let body = null;
  try { body = JSON.parse(text); } catch { /* the edge's own body, or none */ }
  const vercelError = res.headers.get("x-vercel-error");
  const bodyless = res.status === 204 || res.status === 304;
  const fromService = !vercelError && (bodyless ? res.headers.get("x-probe-id") === id : body?.source === "service");
  const hitsRes = await fetch(`${base}/hits?id=${encodeURIComponent(id)}`, { headers: headers() });
  const hits = hitsRes.ok ? (await hitsRes.json()).hits : `? (${hitsRes.status})`;
  return {
    status: res.status,
    from: fromService ? "service" : "edge",
    vercelError,
    etag: res.headers.get("etag"),
    type: res.headers.get("content-type")?.split(";")[0] ?? "",
    forwardedIfMatch: body?.if_match ?? null,
    hits,
  };
}

const rows = [];
let failed = 0;
for (const c of cases) {
  const results = [];
  for (let n = 0; n < repeat; n++) results.push(await once(c, n));
  const seen = [...new Set(results.map((r) => `${r.from} ${r.status}`))];
  const pass = c.expect === null ? "recorded" : seen.length === 1 && seen[0] === c.expect ? "pass" : "FAIL";
  if (pass === "FAIL") failed++;
  const r = results[0];
  rows.push(
    `| ${c.id} | ${c.what} | ${c.expect ?? "–"} | ${seen.join(", ")} | ${r.vercelError ?? ""} | ${r.etag ?? "none"} | ${r.type} | ${results.map((x) => x.hits).join("/")} | ${pass} |`,
  );
}

console.log(`Run ${run} against ${base} at ${new Date().toISOString()}, ${repeat} calls a case.\n`);
console.log("| Case | Request and the service's answer | Expected | Received | X-Vercel-Error | ETag received | Type | Handler ran | Result |");
console.log("|---|---|---|---|---|---|---|---|---|");
rows.forEach((r) => console.log(r));
console.log(`\n${failed === 0 ? "All expectations met." : `${failed} case(s) failed.`}`);
process.exit(failed === 0 ? 0 : 1);
