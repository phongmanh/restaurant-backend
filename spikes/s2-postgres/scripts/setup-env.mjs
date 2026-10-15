#!/usr/bin/env node
// Adds the `app` role's two connection strings to .env.spike, derived from the migrator's.
//
//   node --env-file=.env.spike scripts/setup-env.mjs
//
// Needs DATABASE_URL_MIGRATOR_DIRECT, or DATABASE_URL_UNPOOLED from the block Vercel's Neon
// integration generates (its owner role then acts as `migrator`, and is copied to
// DATABASE_URL_MIGRATOR_DIRECT). Generates a random password for `app` and appends
// DATABASE_URL_APP_DIRECT and DATABASE_URL_APP_POOLED (Neon's pooler host is the endpoint id plus
// "-pooler"). `./gradlew migrate` then creates the role with that password.
// Refuses a database outside Singapore. Prints host names only, never a password.

import { randomBytes } from "node:crypto";
import { appendFileSync } from "node:fs";

const ENV_FILE = new URL("../.env.spike", import.meta.url);
const migrator = process.env.DATABASE_URL_MIGRATOR_DIRECT ?? process.env.DATABASE_URL_UNPOOLED;
const REGION = "ap-southeast-1"; // the AWS region of Vercel's sin1

if (!migrator) fail("neither DATABASE_URL_MIGRATOR_DIRECT nor DATABASE_URL_UNPOOLED is set: run with --env-file=.env.spike");
if (process.env.DATABASE_URL_APP_DIRECT || process.env.DATABASE_URL_APP_POOLED) {
  console.log("app URLs already in .env.spike; nothing to do");
  process.exit(0);
}

const url = new URL(migrator);
const [endpoint, ...domain] = url.hostname.split(".");
if (!endpoint.startsWith("ep-") || !url.hostname.endsWith(".neon.tech")) fail(`not a Neon host: ${url.hostname}`);
if (endpoint.endsWith("-pooler")) fail("the migrator URL must be the direct (unpooled) string, without -pooler");
if (!domain.includes(REGION)) fail(`the database is in ${domain.find((d) => /^[a-z]{2}-[a-z]+-\d$/.test(d)) ?? "an unknown region"}, not ${REGION}: S-2 needs it next to sin1`);

// Hex, so it needs no escaping in a URL or in SQL; 192 bits clears Neon's 60-bit minimum.
const password = randomBytes(24).toString("hex");
const direct = withCredentials(url, "app", password);
const pooled = withCredentials(url, "app", password);
pooled.hostname = [`${endpoint}-pooler`, ...domain].join(".");

const migratorLine = process.env.DATABASE_URL_MIGRATOR_DIRECT ? "" : `DATABASE_URL_MIGRATOR_DIRECT=${migrator}\n`;
appendFileSync(ENV_FILE, `\n${migratorLine}DATABASE_URL_APP_DIRECT=${direct}\nDATABASE_URL_APP_POOLED=${pooled}\n`);
if (migratorLine) console.log(`copied DATABASE_URL_UNPOOLED to DATABASE_URL_MIGRATOR_DIRECT (role ${url.username})`);
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
