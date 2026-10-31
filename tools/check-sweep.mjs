#!/usr/bin/env node
/**
 * A crawl pass deletes only what it walked.
 *
 * The defect this exists for: the end-of-pass sweep was `DELETE FROM node WHERE gen < ?` over
 * the whole database, and two callers hand `crawl()` a FOLDER rather than a volume - the pass
 * that runs when a folder is walked into, and the hot-folder watcher. The folder was registered
 * as a volume of its own, walked in well under its budget, and then every row on every other
 * drive was deleted for not carrying the folder pass's generation. The index presented as
 * strict, fast and inexplicably incomplete.
 *
 * Every test passed throughout. `SqliteIndex` runs only on a device, and the sweep is correct in
 * isolation - what was wrong was its reach, and only a rule over the source can see that.
 *
 * The rules, all in SqliteIndex.kt:
 *   1. Every statement that selects or deletes by `gen < ?` is scoped by the pass's `$scope`.
 *   2. `crawl()` classifies each root with `CrawlScope.subtreeOf` and builds its sweep with
 *      `CrawlScope.sweepSql`.
 *   3. The global generation and "last indexed" are written only under the guard that the pass
 *      walked whole volumes - a folder finishing is a fact about the folder.
 *
 *   node tools/check-sweep.mjs              check
 *   node tools/check-sweep.mjs --selftest   prove every rule catches its own failure
 *
 * Prints "SWEEP OK" only after every assertion passes.
 */
import { readFileSync } from "node:fs";

const INDEX = "core-index/src/main/java/dev/niccc2007/filet/index/SqliteIndex.kt";
const GUARD = "if (volumeRoots.isNotEmpty()) {";

function load() {
  return readFileSync(INDEX, "utf8").replace(/\r\n/g, "\n");
}

/** The text of `fun crawl(` up to the next top-level member, crudely: the next `    private fun`. */
function crawlBody(src) {
  const at = src.indexOf("override suspend fun crawl(");
  if (at < 0) return "";
  const end = src.indexOf("\n    private fun ", at);
  return src.slice(at, end < 0 ? undefined : end);
}

function check(src) {
  const out = [];
  src.split("\n").forEach((line, i) => {
    if (/gen < \?/.test(line) && !line.includes("$scope")) {
      out.push(`${INDEX}:${i + 1}: selects or deletes by generation without the pass's scope: ${line.trim()}`);
    }
  });
  const body = crawlBody(src);
  if (!body) out.push(`${INDEX}: no crawl() found`);
  if (!body.includes("val owner = CrawlScope.subtreeOf(")) {
    out.push(`${INDEX}: crawl() does not classify its roots with CrawlScope.subtreeOf - a folder would be swept as a volume`);
  }
  if (!body.includes("CrawlScope.sweepSql(")) {
    out.push(`${INDEX}: crawl() does not build its sweep with CrawlScope.sweepSql`);
  }
  const guard = body.indexOf(GUARD);
  for (const key of ["META_GEN", "META_LAST_RUN"]) {
    const sites = [...src.matchAll(new RegExp(`putMeta\\(${key},`, "g"))].map((m) => m.index);
    if (sites.length !== 1) {
      out.push(`${INDEX}: putMeta(${key}, ...) is written in ${sites.length} places; it belongs to the volume-pass guard alone`);
      continue;
    }
    const inBody = sites[0] - src.indexOf(body);
    if (guard < 0 || inBody < guard || inBody > guard + 1500) {
      out.push(`${INDEX}: putMeta(${key}, ...) is not under "${GUARD}" - a folder pass would advance it`);
    }
  }
  return out;
}

function selftest() {
  const real = load();
  const failures = [];
  const expect = (name, src, want) => {
    if ((check(src).length > 0) !== want) failures.push(`${name}: expected ${want ? "a finding" : "none"}`);
  };
  expect("the tree as it stands", real, false);
  expect(
    "the sweep goes back to the whole database - the defect",
    real.replace('"DELETE FROM node WHERE gen < ? AND $scope"', '"DELETE FROM node WHERE gen < ?"'),
    true,
  );
  expect(
    "the moves query loses its scope",
    real.replace("WHERE gen < ? AND is_dir = 0 AND $scope", "WHERE gen < ? AND is_dir = 0"),
    true,
  );
  expect(
    "roots stop being classified",
    real.replace("val owner = CrawlScope.subtreeOf(volumes, root.toString())", "val owner: Long? = null"),
    true,
  );
  expect(
    "the generation is advanced for any completed pass",
    real.replace(GUARD, "if (true) {"),
    true,
  );
  if (failures.length) {
    console.error("SWEEP SELFTEST FAILED\n  " + failures.join("\n  "));
    process.exit(1);
  }
  console.log("SWEEP SELFTEST OK  (4 negative controls, 1 positive)");
}

if (process.argv.includes("--selftest")) {
  selftest();
} else {
  const findings = check(load());
  if (findings.length) {
    console.error("A crawl pass can delete what it did not walk:\n");
    for (const f of findings) console.error("  " + f);
    console.error("\nSee CrawlScope.kt and tools/check-sweep.mjs.");
    process.exit(1);
  }
  console.log("SWEEP OK  (every generation sweep scoped, roots classified, the global stamps behind the volume guard)");
}
