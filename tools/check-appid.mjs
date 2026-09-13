#!/usr/bin/env node
/**
 * G2 — the applicationId in the build file and the one recorded in PLAN.md must agree.
 *
 * The id is frozen after first release (PLAN.md §7), so drift between the decision and the
 * code is the expensive kind of drift. Measured from both sources; neither is trusted.
 */
import { readFileSync } from "node:fs";

const gradle = readFileSync("app/build.gradle.kts", "utf8");
const m = gradle.match(/applicationId\s*=\s*"([^"]+)"/);
if (!m) { console.error("FAIL: no applicationId in app/build.gradle.kts"); process.exit(1); }
const fromGradle = m[1];

const plan = readFileSync("PLAN.md", "utf8");
const ids = [...plan.matchAll(/`([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){2,})`/g)].map(x => x[1]);
const recorded = ids.filter(i => i.endsWith(".filet") && !i.endsWith(".debug"));
if (recorded.length === 0) { console.error("FAIL: PLAN.md records no applicationId"); process.exit(1); }

const mismatched = recorded.filter(i => i !== fromGradle);
if (mismatched.length) {
  console.error(`FAIL: build file says "${fromGradle}" but PLAN.md also records: ${[...new Set(mismatched)].join(", ")}`);
  process.exit(1);
}
console.log(`APPID CONSISTENT  (${fromGradle}, ${recorded.length} reference(s) in PLAN.md)`);
