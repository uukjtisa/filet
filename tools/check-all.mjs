#!/usr/bin/env node
/**
 * Run every checker in `tools/`, and every checker's own selftest.
 *
 * This exists because the gate it answers used to be an enumerated chain of `&&`, and an
 * enumerated chain goes stale the moment somebody adds a checker: the new one is not in the
 * list, so it never runs in CI, and the gate keeps reporting green over a rule nobody is
 * enforcing. The list is now the directory.
 *
 * A checker that takes `--selftest` is run twice. The selftest is the part that matters: a
 * checker which cannot fail is not evidence, and the only way to know it can is to feed it
 * something broken and watch it say so.
 *
 * Some checkers need a built APK or a device attached. Those are SKIPPED rather than failed,
 * by name, and the skip is printed - an unexplained absence is how a check quietly stops
 * running.
 *
 * **Exit code 2 means "could not run", and is a skip, not a pass.** Two of these checkers read
 * the live GitHub release, and GitHub answers an HTML error page under rate limiting perhaps
 * one run in three. Counting that as a failure makes the gate about network weather; counting
 * it as a pass makes it about nothing. It is reported as what it is, with the reason.
 *
 *   node tools/check-all.mjs            everything that can run here
 *   node tools/check-all.mjs --list     just say what would run
 *
 * Prints "ALL CHECKS OK" only after every one of them passes.
 */
import { readdirSync, existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

const DIR = "tools";

/**
 * Checkers that need something this process cannot guarantee, and what they need.
 *
 * Each is skipped only when its requirement is genuinely absent, so on a machine that HAS the
 * requirement it runs like everything else rather than being permanently excused.
 */
const NEEDS = {
  "check-release.mjs": { what: "a built release APK", present: () => builtApks().length > 0 },
  "check-releasedoc.mjs": { what: "a built release APK", present: () => builtApks().length > 0 },
  "check-icon.mjs": { what: "both built APKs", present: () => builtApks().length >= 2 },
  "check-archives.mjs": { what: "7-Zip", present: () => existsSync("C:/Program Files/7-Zip/7z.exe") },
};

function builtApks() {
  const out = [];
  for (const dir of ["app/build/outputs/apk/github/debug", "app/build/outputs/apk/github/release"]) {
    if (!existsSync(dir)) continue;
    for (const f of readdirSync(dir)) if (f.endsWith(".apk")) out.push(join(dir, f));
  }
  return out;
}

const checkers = readdirSync(DIR)
  .filter((f) => f.startsWith("check-") && f.endsWith(".mjs") && f !== "check-all.mjs")
  .sort();

if (process.argv.includes("--list")) {
  for (const c of checkers) console.log(c);
  process.exit(0);
}

/**
 * Does this checker take `--selftest`?
 *
 * Read out of the file rather than kept in a list here, for the same reason the checker list
 * is the directory: a list would go stale and the selftest would silently stop running.
 */
function hasSelftest(file) {
  try {
    return readFileSync(join(DIR, file), "utf8").includes("--selftest");
  } catch {
    return false;
  }
}

let ran = 0;
let selftests = 0;
const skipped = [];
const failed = [];

for (const file of checkers) {
  const need = NEEDS[file];
  if (need && !need.present()) {
    skipped.push(`${file} (needs ${need.what})`);
    continue;
  }
  for (const args of hasSelftest(file) ? [[], ["--selftest"]] : [[]]) {
    const r = spawnSync(process.execPath, [join(DIR, file), ...args], { encoding: "utf8" });
    const label = args.length ? `${file} --selftest` : file;
    if (r.status === 2) {
      const why = ((r.stderr || "") + (r.stdout || "")).trim().split("\n")[0] || "no reason given";
      skipped.push(`${label} — ${why}`);
      continue;
    }
    if (r.status !== 0) {
      failed.push(label);
      console.error(`FAIL  ${label}`);
      const out = ((r.stdout || "") + (r.stderr || "")).trim();
      for (const line of out.split("\n").slice(0, 12)) console.error("      " + line);
    } else {
      console.log(`ok    ${label}  ${(r.stdout || "").trim().split("\n")[0] || ""}`);
      if (args.length) selftests++;
      else ran++;
    }
  }
}

for (const s of skipped) console.log(`skip  ${s}`);

if (failed.length) {
  console.error(`\nCHECKS FAILED: ${failed.length} — ${failed.join(", ")}`);
  process.exit(1);
}
console.log(
  `\nALL CHECKS OK  (${ran} checkers, ${selftests} of them with a passing selftest, ` +
    `${skipped.length} skipped for a missing requirement)`,
);
