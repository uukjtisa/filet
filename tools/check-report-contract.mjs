#!/usr/bin/env node
/**
 * The app's report limits are the server's limits.
 *
 * The form decides what it lets somebody send; the server decides what it accepts. They were
 * written separately and disagreed on almost every number - a 200-character title against 120,
 * five 2 MB pictures against three of 1 MB, a megabyte of log against 30,000 characters - so the
 * app let people build reports that could never be filed. This reads both and fails on any gap.
 *
 *   node tools/check-report-contract.mjs              compare the two
 *   node tools/check-report-contract.mjs --selftest   prove a drift is caught
 *
 * Prints "REPORT CONTRACT OK" / "REPORT CONTRACT SELFTEST OK" only after every assertion passes.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const APP = join(ROOT, "app/src/main/java/dev/niccc2007/filet/report/ReportForm.kt");
const SERVER = join(ROOT, "report-api/lib/config.mjs");

/** app constant -> server export */
const PAIRS = [
  ["TITLE_MIN", "TITLE_MIN"],
  ["TITLE_MAX", "TITLE_MAX"],
  ["BODY_MIN", "BODY_MIN"],
  ["BODY_MAX", "BODY_MAX"],
  ["DEVICE_MAX", "DEVICE_MAX"],
  ["IMAGES_MAX", "MAX_IMAGES"],
  ["IMAGE_MAX_BYTES", "MAX_IMAGE_BYTES"],
  ["LOG_MAX_BYTES", "LOG_MAX"],
];

const num = (s) => Number(String(s).replace(/[_L]/g, "").replace(/\s*\*\s*/g, "*").split("*").reduce((a, b) => a * Number(b), 1));

function appValues(text) {
  const out = {};
  for (const m of text.matchAll(/const val (\w+)\s*=\s*([\d_L* ]+)/g)) out[m[1]] = num(m[2]);
  return out;
}

function serverValues(text) {
  const out = {};
  for (const m of text.matchAll(/export const (\w+)\s*=\s*([\d_]+)\s*;/g)) out[m[1]] = num(m[2]);
  return out;
}

function gaps(app, server) {
  const out = [];
  for (const [a, s] of PAIRS) {
    if (!(a in app)) out.push(`app has no ${a}`);
    else if (!(s in server)) out.push(`server has no ${s}`);
    else if (app[a] !== server[s]) out.push(`${a} is ${app[a]} in the app, ${s} is ${server[s]} on the server`);
  }
  return out;
}

if (process.argv.includes("--selftest")) {
  const app = "const val TITLE_MIN = 3\nconst val TITLE_MAX = 200\nconst val BODY_MIN = 10\nconst val BODY_MAX = 10_000\nconst val DEVICE_MAX = 2_000\nconst val IMAGES_MAX = 3\nconst val IMAGE_MAX_BYTES = 1_000_000L\nconst val LOG_MAX_BYTES = 30_000L";
  const server = "export const TITLE_MIN = 3;\nexport const TITLE_MAX = 120;\nexport const BODY_MIN = 10;\nexport const BODY_MAX = 10_000;\nexport const DEVICE_MAX = 2_000;\nexport const MAX_IMAGES = 3;\nexport const MAX_IMAGE_BYTES = 1_000_000;\nexport const LOG_MAX = 30_000;";
  const drift = gaps(appValues(app), serverValues(server));
  const fixed = gaps(appValues(app.replace("200", "120")), serverValues(server));
  const missing = gaps(appValues(app.replace("200", "120").replace(/const val LOG_MAX_BYTES.*/, "")), serverValues(server));
  if (drift.length !== 1 || !drift[0].includes("TITLE_MAX") || fixed.length || missing.length !== 1) {
    console.error("REPORT CONTRACT SELFTEST FAILED", { drift, fixed, missing });
    process.exit(1);
  }
  console.log("REPORT CONTRACT SELFTEST OK  (a drifted limit caught, an agreed set passes, a missing one caught)");
  process.exit(0);
}

const found = gaps(appValues(readFileSync(APP, "utf8")), serverValues(readFileSync(SERVER, "utf8")));
if (found.length) {
  console.error("The report form and the report server disagree:\n");
  for (const g of found) console.error("  " + g);
  process.exit(1);
}
console.log(`REPORT CONTRACT OK  (${PAIRS.length} limits, app and server agree)`);
