#!/usr/bin/env node
/**
 * Every dependency is compatible with GPL-3.0, and the README says what they are.
 *
 * This is not paperwork. Filet is GPL-3.0 on a public repo with Nic's handle on it, and GPL-3
 * cannot take a dependency with a field-of-use restriction. The one that nearly got in this
 * round is RAR: every Java decoder for it descends from the UnRAR source, whose licence
 * forbids using it to build an archiver. Shipping one would have been an actual violation
 * rather than an oversight, and the gap it leaves is documented instead.
 *
 * The check is deliberately a known-list rather than a scan of published metadata. A scanner
 * reports what a POM claims; a list records what somebody looked up and decided.
 *
 *   node tools/check-licences.mjs              check
 *   node tools/check-licences.mjs --selftest   prove it fails on an incompatible one
 *
 * Prints "LICENCES OK" / "LICENCES SELFTEST OK" only after every assertion passes.
 */
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

/**
 * What each third-party library is under, and whether GPL-3 may link it.
 *
 * `ok: false` entries are here on purpose: they are the ones somebody will be tempted to add,
 * and the reason not to should be written down where the temptation is.
 */
const KNOWN = {
  "commons-compress": { licence: "Apache-2.0", ok: true },
  xz: { licence: "Public domain", ok: true },
  json: { licence: "JSON License", ok: true, note: "test-only, never shipped in the APK" },
  "sora-editor": { licence: "LGPL-2.1", ok: true, note: "linked unmodified, never vendored" },
  editor: { licence: "LGPL-2.1", ok: true, note: "sora-editor; linked unmodified, never vendored" },
  "luaj-jse": { licence: "MIT", ok: true },
  smbj: { licence: "Apache-2.0", ok: true },
  sshj: { licence: "Apache-2.0", ok: true },
  "commons-net": { licence: "Apache-2.0", ok: true },
  "slf4j-nop": { licence: "MIT", ok: true },
  "smali-dexlib2": { licence: "BSD-3-Clause", ok: true },
  "smali-baksmali": { licence: "BSD-3-Clause", ok: true },
  smali: { licence: "BSD-3-Clause", ok: true },
  apksig: { licence: "Apache-2.0", ok: true },
  ARSCLib: { licence: "Apache-2.0", ok: true },
  core: { licence: "Apache-2.0", ok: true, note: "zxing core" },
  io: { licence: "Apache-2.0", ok: true, note: "libsu io" },
  "sqlite-android": { licence: "Apache-2.0", ok: true },
  "work-runtime-ktx": { licence: "Apache-2.0", ok: true },
  documentfile: { licence: "Apache-2.0", ok: true },
  // Never. Kept here so the reason is where somebody would go looking.
  junrar: {
    licence: "UnRAR",
    ok: false,
    why: "the UnRAR licence forbids using it to build an archiver, which is what Filet is",
  },
  unrar: { licence: "UnRAR", ok: false, why: "same restriction as junrar" },
  "sevenzipjbinding": {
    licence: "LGPL + UnRAR for the RAR codec",
    ok: false,
    why: "carries the UnRAR restriction inside it, and needs the NDK",
  },
};

/** Anything matching these is refused whatever the catalogue calls it. */
const FORBIDDEN_PATTERNS = [/unrar/i, /junrar/i, /sevenzipjbinding/i];

function parseCatalogue(toml) {
  const out = [];
  const section = toml.slice(toml.indexOf("[libraries]"), toml.indexOf("[plugins]") >>> 0 || toml.length);
  for (const line of section.split("\n")) {
    const m = line.match(/^\s*([A-Za-z0-9_.-]+)\s*=\s*\{(.*)\}\s*$/);
    if (!m) continue;
    const name = (m[2].match(/name\s*=\s*"([^"]+)"/) || [])[1];
    const group = (m[2].match(/group\s*=\s*"([^"]+)"/) || [])[1];
    if (name) out.push({ alias: m[1], name, group: group || "" });
  }
  return out;
}

function check(toml, readme) {
  const problems = [];
  const libs = parseCatalogue(toml);
  if (libs.length < 10) problems.push(`only parsed ${libs.length} libraries; the catalogue format changed`);

  for (const lib of libs) {
    const coordinate = `${lib.group}:${lib.name}`;
    if (FORBIDDEN_PATTERNS.some((p) => p.test(coordinate))) {
      problems.push(`${coordinate} is refused outright: it carries the UnRAR restriction`);
      continue;
    }
    // androidx and the Kotlin/JetBrains stack are Apache-2.0 across the board.
    if (/^androidx\./.test(lib.group) || /^org\.jetbrains/.test(lib.group)) continue;
    if (/^junit$/.test(lib.group) || /^com\.android\.tools/.test(lib.group)) continue;

    const known = KNOWN[lib.name];
    if (!known) {
      problems.push(`${coordinate} has no recorded licence — look it up and add it to KNOWN`);
      continue;
    }
    if (!known.ok) {
      problems.push(`${coordinate} is ${known.licence}: ${known.why}`);
    }
  }

  // The archive formats are the user-visible ones, so the README has to name them.
  for (const needed of ["commons-compress", "XZ"]) {
    if (!readme.includes(needed)) {
      problems.push(`the README does not credit ${needed}`);
    }
  }
  if (!/RAR/.test(readme)) {
    problems.push("the README does not say that RAR is not supported, or why");
  }
  return problems;
}

const TOML = "gradle/libs.versions.toml";
const README = "README.md";

if (process.argv.includes("--selftest")) {
  const toml = readFileSync(TOML, "utf8");
  const readme = readFileSync(README, "utf8");
  const cases = [
    [
      "a RAR decoder sneaks in",
      toml.replace("[plugins]", 'junrar = { group = "com.github.junrar", name = "junrar", version = "7.5.5" }\n\n[plugins]'),
      readme,
      /UnRAR/,
    ],
    [
      "a library with no recorded licence",
      toml.replace("[plugins]", 'mystery = { group = "com.example", name = "mystery", version = "1.0" }\n\n[plugins]'),
      readme,
      /no recorded licence/,
    ],
    ["the README stops crediting the archive libraries", toml, readme.replace(/commons-compress/g, "something"), /commons-compress/],
    ["the README stops mentioning RAR", toml, readme.replace(/RAR/g, "zip"), /RAR is not supported/],
  ];
  let failures = 0;
  for (const [name, t, r, expected] of cases) {
    const problems = check(t, r);
    if (!problems.some((p) => expected.test(p))) {
      failures++;
      console.error(`SELFTEST FAIL: "${name}" not caught. Got: ${problems.join("; ") || "(nothing)"}`);
    }
  }
  if (failures) {
    console.error(`LICENCES SELFTEST: ${failures} case(s) not caught`);
    process.exit(1);
  }
  console.log(`LICENCES SELFTEST OK  (${cases.length} negative controls all caught)`);
} else {
  const problems = check(readFileSync(TOML, "utf8"), readFileSync(README, "utf8"));
  if (problems.length) {
    console.error("LICENCE PROBLEMS: " + problems.length);
    for (const p of problems) console.error("  " + p);
    process.exit(1);
  }
  console.log("LICENCES OK  (every dependency is GPL-3 compatible and credited)");
}
