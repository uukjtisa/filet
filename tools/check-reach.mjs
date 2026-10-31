#!/usr/bin/env node
/**
 * The reach ladder is declared once, and the list of network schemes agrees with the providers.
 *
 * SEARCH.md §13. Three rules, each of which a later change could break while every test still
 * passed:
 *
 *  1. **`SearchScope` stays gone.** It was one enum carrying two tree depths, a set of drives and
 *     a query field, and its middle member named a set nobody could point at. Reintroducing an
 *     enum of scopes - under that name - would bring the chip row back as four unrelated options.
 *     Historical mentions in backticks are prose about the fault and are allowed.
 *  2. **Only `SearchReach.kt` builds rungs.** `ReachLadder.rungsFor` is the single declaration of
 *     what a pane may search; a surface that assembles its own `Rung(...)` list is how a chip
 *     appears on one screen and not another, or offers a rung the request builder does not know.
 *  3. **`VolumeKey.REMOTE_SCHEMES` matches every provider declaring `remote = true`.** The set is
 *     listed rather than asked of a provider because the ladder and the battery rules must know
 *     whether a drive is across Wi-Fi before anything is dialled. A provider added without its
 *     scheme here would be treated as local: offered under "This phone", crawled on open, and
 *     walked by every search - the battery defect this round exists to end.
 *
 *   node tools/check-reach.mjs              check
 *   node tools/check-reach.mjs --selftest   prove each rule catches its own failure
 *
 * Prints "REACH OK" only after every assertion passes.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const ROOTS = [
  "app/src/main/java",
  "app/src/test/java",
  "core-index/src/main/java",
  "core-index/src/test/java",
  "core-vfs/src/main/java",
  "core-vfs-jvm/src/main/java",
];
const LADDER = "app/src/main/java/dev/niccc2007/filet/browser/SearchReach.kt";
const REACH = "core-index/src/main/java/dev/niccc2007/filet/index/Reach.kt";
const NET_PROTOCOL = "core-vfs/src/main/java/dev/niccc2007/filet/vfs/provider/net/NetConnections.kt";

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

// ── rule 1 ────────────────────────────────────────────────────────────────────────────────
// A bare identifier, not inside backticks and not a prefix of a longer name (`SearchScopeTest`
// is a test of pane kinds and keeps its name).
const SCOPE = /(?<![`\w])SearchScope(?![\w`])/;

function scopeFindings(files) {
  const out = [];
  for (const [path, text] of Object.entries(files)) {
    text.split(/\r?\n/).forEach((line, i) => {
      if (SCOPE.test(line)) out.push(`${path}:${i + 1}  SearchScope is back`);
    });
  }
  return out;
}

// ── rule 2 ────────────────────────────────────────────────────────────────────────────────
const RUNG = /(?<![\w.])Rung\(/;

function rungFindings(files) {
  const out = [];
  for (const [path, text] of Object.entries(files)) {
    if (path === LADDER || path.includes("/src/test/")) continue;
    text.split(/\r?\n/).forEach((line, i) => {
      if (RUNG.test(line)) out.push(`${path}:${i + 1}  builds its own rung - read ReachLadder.rungsFor`);
    });
  }
  return out;
}

// ── rule 3 ────────────────────────────────────────────────────────────────────────────────
function listedRemote(files) {
  const text = files[REACH] ?? "";
  const m = text.match(/REMOTE_SCHEMES\s*=\s*setOf\(([^)]*)\)/);
  if (!m) return null;
  return new Set([...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]));
}

function protocolSchemes(files) {
  const out = {};
  for (const m of (files[NET_PROTOCOL] ?? "").matchAll(/^\s*([A-Z]+)\("([a-z]+)",/gm)) out[m[1]] = m[2];
  return out;
}

function declaredRemote(files) {
  const proto = protocolSchemes(files);
  const out = new Map();
  for (const [path, text] of Object.entries(files)) {
    if (!/override val remote:\s*Boolean\s*=\s*true/.test(text)) continue;
    let scheme = null;
    const viaProto = text.match(/override val scheme:\s*String\s*=\s*NetProtocol\.([A-Z]+)\.scheme/);
    if (viaProto) scheme = proto[viaProto[1]] ?? null;
    if (!scheme && /override val scheme:\s*String\s*=\s*SCHEME/.test(text)) {
      scheme = text.match(/const val SCHEME\s*=\s*"([^"]+)"/)?.[1] ?? null;
    }
    out.set(path, scheme);
  }
  return out;
}

function schemeFindings(files) {
  const out = [];
  const listed = listedRemote(files);
  if (!listed) return [`${REACH}  REMOTE_SCHEMES not found`];
  const declared = declaredRemote(files);
  if (declared.size === 0) return ["no provider declares remote = true - the parser is broken, not the code"];
  const seen = new Set();
  for (const [path, scheme] of declared) {
    if (!scheme) {
      out.push(`${path}  remote provider whose scheme could not be read`);
      continue;
    }
    seen.add(scheme);
    if (!listed.has(scheme)) out.push(`${path}  remote scheme "${scheme}" missing from VolumeKey.REMOTE_SCHEMES`);
  }
  for (const s of listed) {
    if (!seen.has(s)) out.push(`${REACH}  "${s}" is listed as remote but no remote provider declares it`);
  }
  return out;
}

function check(files) {
  return [...scopeFindings(files), ...rungFindings(files), ...schemeFindings(files)];
}

// ── selftest ──────────────────────────────────────────────────────────────────────────────
function selftest() {
  const real = load();
  const failures = [];
  const expect = (name, files, wantFinding) => {
    const got = check(files).length > 0;
    if (got !== wantFinding) failures.push(`${name}: expected ${wantFinding ? "a finding" : "none"}`);
  };
  const withFile = (path, text) => ({ ...real, [path]: text });

  expect("the tree as it stands", real, false);
  expect(
    "an enum called SearchScope comes back",
    withFile("app/src/main/java/x/Back.kt", "enum class SearchScope { A }"),
    true,
  );
  expect(
    "a backticked mention of the old enum is prose, not code",
    withFile("app/src/main/java/x/Prose.kt", "// `SearchScope` was one enum"),
    false,
  );
  expect(
    "a screen builds its own rung",
    withFile("app/src/main/java/x/Chips.kt", 'val r = Rung("Mine", Reach.Everywhere)'),
    true,
  );
  expect(
    "a remote provider with an unlisted scheme",
    withFile(
      "core-vfs/src/main/java/x/NewProvider.kt",
      'override val scheme: String = SCHEME\noverride val remote: Boolean = true\nconst val SCHEME = "gdrive"',
    ),
    true,
  );
  expect(
    "a listed scheme that no provider serves",
    withFile(REACH, real[REACH].replace('setOf("smb"', 'setOf("nfs", "smb"')),
    true,
  );

  if (failures.length) {
    console.error("REACH SELFTEST FAILED\n  " + failures.join("\n  "));
    process.exit(1);
  }
  console.log("REACH SELFTEST OK  (5 negative controls, 1 positive)");
}

if (process.argv.includes("--selftest")) {
  selftest();
} else {
  const findings = check(load());
  if (findings.length) {
    console.error("The reach ladder has more than one declaration, or the network schemes drifted:\n");
    for (const f of findings) console.error("  " + f);
    console.error("\nSee SEARCH.md §13 and tools/check-reach.mjs.");
    process.exit(1);
  }
  const n = declaredRemote(load()).size;
  console.log(`REACH OK  (SearchScope gone, rungs built in one place, ${n} remote providers all listed)`);
}
