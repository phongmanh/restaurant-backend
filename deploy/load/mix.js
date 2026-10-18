// Test-plan §3's free load profiles on staging (§9 step 6b): architecture §9's request mix at a
// small rate, from one restaurant (L) whose guests and staff `loadTest` (LoadRun.kt) prepared.
// Never run by hand: the driver passes the fixtures, checks the orders afterwards and closes the
// sessions.
//
// Environment, set by the driver (secrets only here, never in a file or on a command line):
//   LOAD_FIXTURES  JSON: base, restaurant_id, staff_token, item_ids, report_from, report_to,
//                  guests [{token, session_id, qr_token}]
//   BYPASS         the Deployment Protection bypass secret
//   PROFILE        smoke | cold | deploy
//   RATE, MINUTES  requests per second and the run's length
//
// Output: `ACK <order id>` for each order the service acknowledged, `FAIL <time> <op> <status>
// <request id>` for each unexpected answer, `SLOW <time> <op> <ms>` for each request over COLD_MS,
// and the summary as JSON in /out/summary.json. No token or body is ever printed.
import http from "k6/http";
import exec from "k6/execution";
import { Counter, Rate, Trend } from "k6/metrics";

const F = JSON.parse(__ENV.LOAD_FIXTURES);
const PROFILE = __ENV.PROFILE || "smoke";
const RATE = Number(__ENV.RATE || 10);
const MINUTES = Number(__ENV.MINUTES || 10);

// S-1 measured cold starts at 5 to 7 s and warm requests well under a second, so a request over
// 2 s is counted as one that waited for an instance to start, apart from the warm ones (§3).
const COLD_MS = 2000;

// Architecture §9's mix, in percent of requests.
const MIX = [
  ["menu", 23],
  ["session", 23],
  ["catchup", 24],
  ["qr", 5],
  ["order", 3],
  ["status", 13],
  ["lists", 5],
  ["token", 3],
  ["report", 1],
];
const WRITES = new Set(["qr", "order", "status", "token"]);

// One order in five is sent twice with its key, as an app retries after a lost answer (ST-09).
const REPLAY_EVERY = 5;
const ORDER_TRIES = 5;
const NEXT = { placed: "accepted", accepted: "preparing", preparing: "ready", ready: "served" };

const warm = new Trend("warm_ms", true);
const slow = new Counter("slow_requests");
const unexpected = new Rate("unexpected");
const replayMismatch = new Counter("replay_mismatch");
const acked = new Counter("orders_acked");

const scenario =
  PROFILE === "cold"
    ? {
        executor: "ramping-arrival-rate",
        startRate: 0,
        timeUnit: "1s",
        stages: [
          { target: RATE, duration: "60s" },
          { target: RATE, duration: `${MINUTES - 1}m` },
        ],
        preAllocatedVUs: 25,
        maxVUs: 100,
      }
    : {
        executor: "constant-arrival-rate",
        rate: RATE,
        timeUnit: "1s",
        duration: `${MINUTES}m`,
        preAllocatedVUs: 25,
        maxVUs: 100,
      };

export const options = {
  scenarios: { mix: scenario },
  // NFR-PERF-01 (guest reads) and NFR-PERF-02 (writes) on warm requests, and §3's error budget.
  thresholds: {
    "warm_ms{kind:read}": ["p(95)<=200", "p(99)<=500"],
    "warm_ms{kind:write}": ["p(95)<=300", "p(99)<=800"],
    "http_req_duration{kind:read}": ["max>=0"],
    "http_req_duration{kind:write}": ["max>=0"],
    unexpected: ["rate<0.001"],
  },
  summaryTrendStats: ["med", "p(95)", "p(99)", "max", "count"],
  discardResponseBodies: false,
  userAgent: "staging-tools/1.0.0",
};

// Each VU is one guest's phone at one table, with its own orders to move on as the kitchen.
const guest = () => F.guests[(exec.vu.idInTest - 1) % F.guests.length];
const state = { orders: [], menuEtag: null, guestAfter: 0, staffAfter: 0, sessionRead: 0 };

function headers(token, extra) {
  const h = {
    "x-vercel-protection-bypass": __ENV.BYPASS,
    "X-Client": "staging-tools/1.0.0",
    Accept: "application/json",
    "Content-Type": "application/json",
  };
  if (token) h.Authorization = `Bearer ${token}`;
  return Object.assign(h, extra || {});
}

function send(op, method, path, token, body, extra, ok) {
  const res = http.request(method, F.base + path, body === null ? null : JSON.stringify(body), {
    headers: headers(token, extra),
    tags: { op, kind: WRITES.has(op) ? "write" : "read", name: op },
    timeout: "60s",
  });
  const now = new Date().toISOString();
  if (res.timings.duration >= COLD_MS) {
    slow.add(1, { op });
    console.log(`SLOW ${now} ${op} ${Math.round(res.timings.duration)}`);
  } else {
    warm.add(res.timings.duration, { op, kind: WRITES.has(op) ? "write" : "read" });
  }
  const bad = !ok.includes(res.status);
  unexpected.add(bad, { op });
  if (bad) console.log(`FAIL ${now} ${op} ${res.status} ${res.headers["X-Request-Id"] || "-"}`);
  return res;
}

const json = (res) => {
  try {
    return res.json();
  } catch (_) {
    return null;
  }
};

function pick() {
  let roll = Math.random() * 100;
  for (const [op, weight] of MIX) {
    roll -= weight;
    if (roll < 0) return op;
  }
  return MIX[0][0];
}

const ops = {
  menu() {
    const g = guest();
    const extra = state.menuEtag ? { "If-None-Match": state.menuEtag } : {};
    const res = send("menu", "GET", "/v1/guest/menu", g.token, null, extra, [200, 304]);
    if (res.status === 200) state.menuEtag = res.headers.Etag || res.headers.ETag || null;
  },
  session() {
    const paths = ["/v1/guest/session", "/v1/guest/bill", "/v1/guest/orders"];
    send("session", "GET", paths[state.sessionRead++ % paths.length], guest().token, null, {}, [200]);
  },
  catchup() {
    if (Math.random() < 0.5) {
      const body = json(send("catchup", "GET", `/v1/guest/events?after=${state.guestAfter}`, guest().token, null, {}, [200]));
      if (body && body.cursor !== undefined && body.cursor !== null) state.guestAfter = body.cursor;
    } else {
      const body = json(send("catchup", "GET", `/v1/staff/events?after=${state.staffAfter}`, F.staff_token, null, {}, [200]));
      if (body && body.cursor !== undefined && body.cursor !== null) state.staffAfter = body.cursor;
    }
  },
  qr() {
    // Another phone at the table: it joins the open session.
    send("qr", "POST", "/v1/guest/sessions", null, { qr_token: guest().qr_token, device_id: crypto.randomUUID() }, {}, [200]);
  },
  order() {
    const items = F.item_ids;
    const lines = [{ item_id: items[Math.floor(Math.random() * items.length)], quantity: 1 + Math.floor(Math.random() * 2) }];
    const key = crypto.randomUUID();
    const replay = exec.scenario.iterationInTest % REPLAY_EVERY === 0;
    let first = null;
    // A lost answer or a 5xx is retried with the same key until the service acknowledges it.
    for (let attempt = 0; attempt < ORDER_TRIES && first === null; attempt++) {
      const res = send("order", "POST", "/v1/guest/orders", guest().token, { lines }, { "Idempotency-Key": key }, [201]);
      if (res.status === 201) first = json(res);
      else if (res.status >= 400 && res.status < 500) return;
    }
    if (first === null) return;
    acked.add(1);
    console.log(`ACK ${first.id}`);
    state.orders.push({ id: first.id, version: first.version, status: first.status });
    if (replay) {
      const again = json(send("order", "POST", "/v1/guest/orders", guest().token, { lines }, { "Idempotency-Key": key }, [201]));
      if (!again || again.id !== first.id) replayMismatch.add(1);
    }
  },
  status() {
    const order = state.orders[0];
    if (!order) return ops.lists();
    const res = send(
      "status",
      "POST",
      `/v1/staff/orders/${order.id}/status`,
      F.staff_token,
      { status: NEXT[order.status] },
      { "If-Match": `"${order.version}"` },
      [200],
    );
    const body = res.status === 200 ? json(res) : null;
    if (!body) return state.orders.shift();
    order.version = body.version;
    order.status = body.status;
    if (!NEXT[order.status]) state.orders.shift();
  },
  lists() {
    const paths = ["/v1/staff/tables", "/v1/staff/orders?status=placed&limit=50"];
    send("lists", "GET", paths[Math.floor(Math.random() * paths.length)], F.staff_token, null, {}, [200]);
  },
  token() {
    // Guests' only: staff tokens are limited to 60 an hour per member (realtime §9), and L has one.
    send("token", "POST", "/v1/guest/realtime-token", guest().token, null, {}, [200]);
  },
  report() {
    send("report", "GET", `/v1/staff/reports/daily-sales?from=${F.report_from}&to=${F.report_to}`, F.staff_token, null, {}, [200]);
  },
};

export default function () {
  ops[pick()]();
}

function stats(metric) {
  if (!metric) return null;
  const v = metric.values;
  return { count: v.count, med: v.med, p95: v["p(95)"], p99: v["p(99)"], max: v.max };
}

export function handleSummary(data) {
  const m = data.metrics;
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const summary = {
    profile: PROFILE,
    rate: RATE,
    minutes: MINUTES,
    requests: count("http_reqs"),
    dropped_iterations: count("dropped_iterations"),
    unexpected_rate: m.unexpected ? m.unexpected.values.rate : 0,
    unexpected: m.unexpected ? m.unexpected.values.passes : 0,
    slow_requests: count("slow_requests"),
    orders_acked: count("orders_acked"),
    replay_mismatch: count("replay_mismatch"),
    warm_read: stats(m["warm_ms{kind:read}"]),
    warm_write: stats(m["warm_ms{kind:write}"]),
    all_read: stats(m["http_req_duration{kind:read}"]),
    all_write: stats(m["http_req_duration{kind:write}"]),
    thresholds: Object.fromEntries(
      Object.entries(m)
        .filter(([, metric]) => metric.thresholds)
        .map(([name, metric]) => [name, Object.values(metric.thresholds).every((t) => t.ok)]),
    ),
  };
  return { "/out/summary.json": JSON.stringify(summary, null, 2), stdout: `SUMMARY ${JSON.stringify(summary)}\n` };
}
