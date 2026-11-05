#!/usr/bin/env node
/**
 * Both navigation surfaces draw their rows from the shared lists, and neither hand-writes one.
 *
 * The defect this exists for: the Apps tab was added to the wide-screen rail alone, and the rail
 * only exists above 720dp - so on a phone the tab was unreachable while every check passed and
 * the build was green. `SpecialPanes.DESTINATIONS` fixed that for destinations; the pinned rows
 * (Activity, Settings, About, and now Shutdown Filet) stayed hand-written in both surfaces until
 * `SpecialPanes.PINNED`. A unit test can see the lists. Only a rule about the SOURCE can see
 * whether a surface still reads them.
 *
 * The rules, for the switcher (phones) and the rail (wide screens):
 *   1. Both draw `SideFolds`, and `SideFolds` reads `SpecialPanes.inFold` and
 *      `SpecialPanes.PINNED` - so both surfaces read both lists by construction.
 *   2. No surface, and not `SideFolds` itself, draws a pinned entry by hand - no row call whose
 *      label is one of the pinned labels, which is what an addition in a hurry looks like.
 *
 *   node tools/check-pinned.mjs              check
 *   node tools/check-pinned.mjs --selftest   prove both rules catch their own failure
 *
 * Prints "PINNED OK" only after every assertion passes.
 */
import { readFileSync } from "node:fs";

const SURFACES = {
  "app/src/main/java/dev/niccc2007/filet/browser/Switcher.kt": "SwitcherRow",
  "app/src/main/java/dev/niccc2007/filet/browser/BrowserScreen.kt": "(?:SwitcherRow|RailItem)",
};
const SPECIAL = "app/src/main/java/dev/niccc2007/filet/browser/SpecialPanes.kt";
// The side bar both surfaces draw. Both lists are read HERE, once, and each surface draws this -
// which is what makes "both surfaces read the lists" true by construction.
const FOLDS = "app/src/main/java/dev/niccc2007/filet/browser/SideFolds.kt";

function pinnedLabels(special) {
  const block = special.match(/enum class Pinned[\s\S]*?\{([\s\S]*?)\n\s*\}/);
  if (!block) return null;
  return [...block[1].matchAll(/[A-Z_]+\("([^"]+)"/g)].map((m) => m[1]);
}

function check(files) {
  const out = [];
  const labels = pinnedLabels(files[SPECIAL] ?? "");
  if (!labels || labels.length === 0) return [`${SPECIAL}  Pinned enum not found - the parser is broken, not the code`];
  const folds = files[FOLDS] ?? "";
  if (!/SpecialPanes\.inFold\(|SpecialPanes\.DESTINATIONS/.test(folds)) {
    out.push(`${FOLDS}  does not read the destinations from SpecialPanes`);
  }
  if (!folds.includes("SpecialPanes.PINNED")) out.push(`${FOLDS}  does not read SpecialPanes.PINNED`);
  if (!/fun inFold\(fold: Fold\): List<Destination> = DESTINATIONS\.filter/.test(files[SPECIAL] ?? "")) {
    out.push(`${SPECIAL}  inFold is not read off DESTINATIONS`);
  }
  for (const [path, rowCall] of [...Object.entries(SURFACES), [FOLDS, "SwitcherRow"]]) {
    const text = files[path] ?? "";
    if (path !== FOLDS && !text.includes("SideFolds(")) out.push(`${path}  does not draw SideFolds, so it cannot reach the lists`);
    text.split(/\r?\n/).forEach((line, i) => {
      const t = line.trim();
      if (t.startsWith("//") || t.startsWith("*")) return;
      for (const label of labels) {
        const hand = new RegExp(`\\b${rowCall}\\([^)]*"${label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}"`);
        if (hand.test(line)) out.push(`${path}:${i + 1}  draws "${label}" by hand - add it to SpecialPanes.PINNED instead`);
      }
    });
  }
  return out;
}

function load() {
  const files = { [SPECIAL]: readFileSync(SPECIAL, "utf8"), [FOLDS]: readFileSync(FOLDS, "utf8") };
  for (const p of Object.keys(SURFACES)) files[p] = readFileSync(p, "utf8");
  return files;
}

function selftest() {
  const real = load();
  const [switcher, rail] = Object.keys(SURFACES);
  const failures = [];
  const expect = (name, files, want) => {
    if ((check(files).length > 0) !== want) failures.push(`${name}: expected ${want ? "a finding" : "none"}`);
  };
  expect("the tree as it stands", real, false);
  expect(
    "the side bar stops reading the pinned list",
    { ...real, [FOLDS]: real[FOLDS].split("SpecialPanes.PINNED").join("SpecialPanes.SOMETHING") },
    true,
  );
  expect(
    "the rail stops drawing the side bar",
    { ...real, [rail]: real[rail].split("SideFolds(").join("NotTheFolds(") },
    true,
  );
  expect(
    "a pinned row written by hand in the side bar",
    { ...real, [FOLDS]: real[FOLDS] + '\n        SwitcherRow(FiletIcons.Cog, "Settings", i++, reduced) { }\n' },
    true,
  );
  expect(
    "a pinned row written by hand in the switcher",
    { ...real, [switcher]: real[switcher] + '\n        SwitcherRow(FiletIcons.Power, "Shutdown Filet", i++, reduced) { }\n' },
    true,
  );
  expect(
    "the folds drawn in one surface only - the Apps-tab defect",
    { ...real, [switcher]: real[switcher].split("SideFolds(").join("NotTheFolds(") },
    true,
  );
  expect(
    "the folds no longer built from the destinations",
    { ...real, [FOLDS]: real[FOLDS].split("SpecialPanes.inFold(").join("emptyFold(") },
    true,
  );
  if (failures.length) {
    console.error("PINNED SELFTEST FAILED\n  " + failures.join("\n  "));
    process.exit(1);
  }
  console.log("PINNED SELFTEST OK  (6 negative controls, 1 positive)");
}

if (process.argv.includes("--selftest")) {
  selftest();
} else {
  const findings = check(load());
  if (findings.length) {
    console.error("A navigation surface is not reading the shared lists:\n");
    for (const f of findings) console.error("  " + f);
    console.error("\nSee SpecialPanes.kt and tools/check-pinned.mjs.");
    process.exit(1);
  }
  console.log(`PINNED OK  (switcher and rail both read the destinations and ${pinnedLabels(load()[SPECIAL]).length} pinned entries)`);
}
