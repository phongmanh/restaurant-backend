#!/usr/bin/env node
// Generates the spike's ES256 (P-256) signing keys and appends them to .env.spike as SPIKE_KEYS.
//
//   node --env-file=.env.spike scripts/setup-env.mjs     (or plain `node scripts/setup-env.mjs`
//                                                         before .env.spike exists)
//
// Every Vercel instance must hold the same keys, or a token signed by one instance fails on the
// next, so they go into one environment variable: Base64 of
// {"staffCurrent":{kid,privateKey,publicKey},"staffPrevious":{…},"guest":{…}}, keys as Base64 DER
// (PKCS#8 private, SPKI public). Prints key IDs only, never a key.

import { generateKeyPairSync } from "node:crypto";
import { appendFileSync, existsSync, readFileSync } from "node:fs";

const ENV_FILE = new URL("../.env.spike", import.meta.url);
if (process.env.SPIKE_KEYS || (existsSync(ENV_FILE) && /^SPIKE_KEYS=/m.test(readFileSync(ENV_FILE, "utf8")))) {
  console.log("SPIKE_KEYS already in .env.spike; nothing to do");
  process.exit(0);
}

const stamp = new Date().toISOString().slice(0, 10);
const keys = {
  staffCurrent: key(`staff-${stamp}-b`),
  staffPrevious: key(`staff-${stamp}-a`),
  guest: key(`guest-${stamp}-a`),
};
appendFileSync(ENV_FILE, `\nSPIKE_KEYS=${Buffer.from(JSON.stringify(keys)).toString("base64")}\n`);
console.log(`appended SPIKE_KEYS to .env.spike: ${Object.values(keys).map((k) => k.kid).join(", ")}`);

function key(kid) {
  const { privateKey, publicKey } = generateKeyPairSync("ec", {
    namedCurve: "P-256",
    privateKeyEncoding: { type: "pkcs8", format: "der" },
    publicKeyEncoding: { type: "spki", format: "der" },
  });
  return { kid, privateKey: privateKey.toString("base64"), publicKey: publicKey.toString("base64") };
}
