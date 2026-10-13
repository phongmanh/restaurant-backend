#!/usr/bin/env node
// Adds the `app` role's two connection strings to .env.spike, derived from the migrator's.
//
//   node --env-file=.env.spike scripts/setup-env.mjs
//
// Needs DATABASE_URL_MIGRATOR_DIRECT (from the Neon console). Generates a random password for
// `app` and appends DATABASE_URL_APP_DIRECT and DATABASE_URL_APP_POOLED (Neon's pooler host is the
// endpoint id plus "-pooler"). `./gradlew migrate` then creates the role with that password.
// Prints host names only, never a password.

import { randomBytes } from "node:crypto";
import { appendFileSync } from "node:fs";

const ENV_FILE = new URL("../.env.spike", import.meta.url);
const migrator = process.env.DATABASE_URL_MIGRATOR_DIRECT;

if (!migrator) fail("DATABASE_URL_MIGRATOR_DIRECT is not set: run with --env-file=.env.spike");
if (process.env.DATABASE_URL_APP_DIRECT || process.env.DATABASE_URL_APP_POOLED) {
  console.log("app URLs already in .env.spike; nothing to do");
  process.exit(0);
}

const url = new URL(migrator);
const [endpoint, ...domain] = url.hostname.split(".");
if (!endpoint.startsWith("ep-") || !url.hostname.endsWith(".neon.tech")) fail(`not a Neon host: ${url.hostname}`);
if (endpoint.endsWith("-pooler")) fail("DATABASE_URL_MIGRATOR_DIRECT must be the direct (unpooled) string, without -pooler");

// Hex, so it needs no escaping in a URL or in SQL; 192 bits clears Neon's 60-bit minimum.
const password = randomBytes(24).toString("hex");
const direct = withCredentials(url, "app", password);
const pooled = withCredentials(url, "app", password);
pooled.hostname = [`${endpoint}-pooler`, ...domain].join(".");

appendFileSync(ENV_FILE, `\nDATABASE_URL_APP_DIRECT=${direct}\nDATABASE_URL_APP_POOLED=${pooled}\n`);
console.log(`added DATABASE_URL_APP_DIRECT (${direct.hostname}) and DATABASE_URL_APP_POOLED (${pooled.hostname})`);

function withCredentials(base, user, pass) {
  const u = new URL(base);
  u.username = user;
  u.password = pass;
  return u;
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
