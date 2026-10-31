#!/usr/bin/env node
/**
 * The unbounded crawl, and the foreground service it raises, are started by a person and by
 * nothing else.
 *
 * The defect this exists for: `crawlFully(roots)` was called unconditionally from the
 * browser's `start()`, so every open - including every time another app asked Filet for a
 * photo - walked every volume with no budget, network shares and `/data` included, under a
 * persistent notification. Measured at 46% of a day's battery. The careful decision existed the
 * whole time in `IndexCoordinator.onStart`; a second launch path skipped it.
 *
 * Every test passed throughout, because the call was correct in isolation. What was wrong was
 * WHERE it was called from, and only a rule about call sites can see that.
 *
 * The rules:
 *   1. `crawlFully(` is called from `BrowserViewModel.reindexNow` and nowhere else.
 *   2. `IndexService.start(` appears inside `crawlFully` and nowhere else.
 *
 *   node tools/check-crawl.mjs              check
 *   node tools/check-crawl.mjs --selftest   prove both rules catch their own failure
 *
 * Prints "CRAWL OK" only after every assertion passes.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const ROOTS = ["app/src/main/java", "core-index/src/main/java"];
const COORDINATOR = "app/src/main/java/dev/niccc2007/filet/index/IndexCoordinator.kt";
const VIEWMODEL = "app/src/main/java/dev/niccc2007/filet/browser/BrowserViewModel.kt";

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const e of entries) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith(".kt")) out.push(p);
  }
  return out;
}

function load() {
  const files = {};
  for (const root of ROOTS) {
    for (const p of walk(root)) files[relative(".", p).split(sep).join("/")] = readFileSync(p, "utf8");
  }
  return files;
}

/**
 * The name of the function enclosing each line, by the nearest `fun name(` above it.
 *
 * Deliberately crude - no parser - and that is enough here: both rules are about which named
 * function a call sits in, and a call moved into a lambda inside the right function is still in
 * the right function.
 */
function enclosing(lines, i) {
  for (let j = i; j >= 0; j--) {
    const m = lines[j].match(/\bfun\s+(?:[\w.<>]+\.)?(\w+)\s*\(/);
    if (m) return m[1];
  }
  return null;
}

/** Code only: comments and KDoc mention these calls on purpose, to explain the fault. */
function isComment(line) {
  const t = line.trim();
  return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");
}

function check(files) {
  const out = [];
  for (const [path, text] of Object.entries(files)) {
    const lines = text.split(/\r?\n/);
    lines.forEach((line, i) => {
      if (isComment(line)) return;
      const code = line.replace(/\/\/.*$/, "");
      if (/(?<![\w])crawlFully\(/.test(code) && !/\bfun\s+crawlFully\(/.test(code)) {
        const fn = enclosing(lines, i);
        if (!(path === VIEWMODEL && fn === "reindexNow")) {
          out.push(`${path}:${i + 1}  crawlFully called from ${fn ?? "top level"} - only Index now may start it`);
        }
      }
      if (/IndexService\.start\(/.test(code)) {
        const fn = enclosing(lines, i);
        if (!(path === COORDINATOR && fn === "crawlFully")) {
          out.push(`${path}:${i + 1}  IndexService started from ${fn ?? "top level"} - only crawlFully may raise it`);
        }
      }
    });
  }
  return out;
}

function selftest() {
  const real = load();
  const failures = [];
  const expect = (name, files, wantFinding) => {
    const got = check(files).length > 0;
    if (got !== wantFinding) failures.push(`${name}: expected ${wantFinding ? "a finding" : "none"}`);
  };

  expect("the tree as it stands", real, false);
  expect(
    "the reported defect: an unconditional crawl on open",
    {
      ...real,
      [VIEWMODEL]:
        real[VIEWMODEL] + "\nprivate fun updateIndexOnOpen(v: List<VNode>) {\n    graph.indexCoordinator.crawlFully(roots)\n}\n",
    },
    true,
  );
  expect(
    "an automatic BUILD handed to the unbounded crawl",
    {
      ...real,
      [COORDINATOR]: real[COORDINATOR] + "\nprivate fun decide() {\n    CrawlKind.BUILD -> crawlFully(local)\n}\n",
    },
    true,
  );
  expect(
    "the service raised from somewhere else",
    { ...real, "app/src/main/java/x/Other.kt": "fun warm() {\n    IndexService.start(context)\n}\n" },
    true,
  );
  expect(
    "a comment explaining the old call is prose, not a call",
    { ...real, "app/src/main/java/x/Prose.kt": "fun x() {\n    // this used to be crawlFully(roots)\n}\n" },
    false,
  );

  if (failures.length) {
    console.error("CRAWL SELFTEST FAILED\n  " + failures.join("\n  "));
    process.exit(1);
  }
  console.log("CRAWL SELFTEST OK  (3 negative controls, 2 positive)");
}

if (process.argv.includes("--selftest")) {
  selftest();
} else {
  const findings = check(load());
  if (findings.length) {
    console.error("The unbounded crawl or its service is reachable without a person asking:\n");
    for (const f of findings) console.error("  " + f);
    console.error("\nSee tools/check-crawl.mjs and SEARCH.md §13.9.");
    process.exit(1);
  }
  console.log("CRAWL OK  (crawlFully only from Index now, the service only from crawlFully)");
}
