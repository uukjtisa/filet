#!/usr/bin/env node
/**
 * The planned-but-not-built work is written down, and written down carefully.
 *
 * Three requests landed as roadmap text rather than as code, and each carries a constraint
 * that is easy to satisfy on the day and easy to lose later:
 *
 *  - **Every capability that was named is present.** A roadmap entry that quietly drops half
 *    its scope is how "I asked for that" happens six months on. The capability words are
 *    checked individually rather than as one sentence, because a rewrite keeps the sentence
 *    and loses a clause.
 *  - **Nothing is written as a promise with a date on it.** Milestones here have exit
 *    criteria, not dates (PLAN.md R5). A date in a roadmap is a commitment nobody made.
 *  - **The positioning stays private.** The features of a well-known competitor were the
 *    reference for a chunk of this app, and referring to a UI convention by its common name is
 *    fine and already shipped. Declaring the app a replacement for that competitor is a
 *    marketing claim in a public file, it was explicitly not to be published, and it is the
 *    kind of line that ends up quoted back at a project.
 *
 *   node tools/check-roadmap.mjs              check
 *   node tools/check-roadmap.mjs --selftest   prove it catches each one
 *
 * Prints "ROADMAP OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";

const SRC = {
  plan: "PLAN.md",
  features: "FEATURES.md",
};

/**
 * Capabilities that were named and must still be findable, as alternatives per concept.
 *
 * Each entry is one thing that was asked for; the alternatives exist so a rewording does not
 * fail the check, while dropping the concept does.
 */
const CAPABILITIES = [
  ["a code editor", [/code editor/i]],
  ["syntax highlighting", [/syntax highlight/i, /highlighting/i]],
  ["jump to definition", [/jump[- ]to[- ]definition/i, /jump from a call/i]],
  ["a pane driven without the on-screen keyboard", [/without the on-screen keyboard/i, /driven by tapping/i]],
  ["the text editor beside it", [/text editor/i]],
  ["APK tools", [/APK tools/i]],
  ["smali-level editing", [/smali/i]],
  ["rebuilding after an edit", [/rebuild/i]],
  ["installing packages", [/install packages/i, /installs anywhere/i, /install the merged/i]],
  ["writing and running Lua", [/run Lua/i, /Lua written and run/i]],
  ["an automation endpoint on the LAN", [/LAN endpoint/i, /automation server/i]],
  ["an enumerated API surface rather than a hand-written list", [/enumerated from the tool registry/i]],
  ["merging split packages", [/merg\w* a split package/i, /merging a split package/i]],
  ["XAPK", [/XAPK/]],
  ["APKM", [/APKM/]],
  ["APKS", [/APKS/]],
];

/** A date in a roadmap is a commitment nobody made. */
const DATE_SHAPES = [
  /\bby (?:Q[1-4]|January|February|March|April|May|June|July|August|September|October|November|December)\b/i,
  /\bin Q[1-4]\b/i,
  /\bship(?:s|ping|ped)? (?:by|in) \d{4}\b/i,
  /\bdue (?:by|in|on)\b/i,
  /\breleas(?:e|ed|ing) (?:by|in) (?:Q[1-4]|\d{4})\b/i,
  /\bETA\b/,
];

/**
 * Positioning, as opposed to description.
 *
 * Naming the layout is descriptive and already in the tree. Declaring the app a replacement is
 * the claim that must not be published.
 */
const POSITIONING = [
  /\b(?:remaking|rebuilding|replacement for|clone of|better than|open ?source(?:d)? version of)\s+(?:the\s+)?MT\s*Manager\b/i,
  /\bMT\s*Manager,?\s+but\s+(?:better|open)/i,
  /\bour\s+MT\s*Manager\b/i,
  /\bMT\s*Manager\s+killer\b/i,
];

export function problems(src, publicFiles) {
  const found = [];
  const all = Object.values(src).join("\n");

  for (const [what, alts] of CAPABILITIES) {
    if (!alts.some((re) => re.test(all))) {
      found.push(`the roadmap no longer mentions ${what}`);
    }
  }

  for (const [name, text] of Object.entries(src)) {
    for (const re of DATE_SHAPES) {
      const m = text.match(re);
      if (m) found.push(`${name} commits to a date: "${m[0]}" — milestones take exit criteria, not dates`);
    }
  }

  // The planned entries must be marked as planned, not left looking shipped.
  // Matched on the milestone, not on the words in the name. Filtering by name caught the
  // editor that already ships (F26) and reported it as an unbuilt feature claiming to be
  // shipped - which it is not; it is a shipped feature the new row builds on.
  const planned = [...src.features.matchAll(/^\| (F\d+) \| ([^|]+)\|[^|]*\| (M1[12]) \| (\w+) \|/gm)];
  if (planned.length < 3) {
    found.push("the three planned features are not all in the feature table");
  }
  for (const [, id, name, , state] of planned) {
    if (state !== "PLANNED") {
      found.push(`${id} (${name.trim()}) is marked ${state} but nothing has been built`);
    }
  }

  // M11 and M12 exist and say what would finish them.
  for (const m of ["M11", "M12"]) {
    if (!new RegExp(`\\*\\*${m}\\*\\*`).test(src.plan)) {
      found.push(`${m} is missing from the roadmap table`);
    }
  }

  // The positioning, across every file that is actually published.
  for (const [file, text] of publicFiles) {
    for (const re of POSITIONING) {
      const m = text.match(re);
      if (m) {
        found.push(
          `${file} publishes the positioning, not just the reference: "${m[0].trim()}"`,
        );
      }
    }
  }

  return found;
}

/** Every tracked text file, because the constraint is about what is published. */
function publicTextFiles() {
  let names = [];
  try {
    names = execSync("git ls-files", { encoding: "utf8" }).split("\n").filter(Boolean);
  } catch {
    return null;
  }
  const out = [];
  for (const n of names) {
    if (!/\.(md|kt|kts|html|txt|xml|json|mjs|js|pro)$/i.test(n)) continue;
    if (!existsSync(n)) continue;
    // This file states the patterns it forbids and carries fixtures containing them, so
    // scanning itself reports itself. Excluding it is not a loophole: it is a checker, not
    // copy, and the thing being protected is what the app and its documents say.
    if (n === "tools/check-roadmap.mjs") continue;
    try {
      out.push([n, readFileSync(n, "utf8")]);
    } catch {
      // Unreadable is not a finding; it is a file this check has no opinion about.
    }
  }
  return out;
}

function read() {
  const out = {};
  for (const [k, p] of Object.entries(SRC)) out[k] = existsSync(p) ? readFileSync(p, "utf8") : "";
  return out;
}

if (process.argv.includes("--selftest")) {
  const good = read();
  const files = publicTextFiles() || [];
  const base = problems(good, files);
  if (base.length) {
    console.error("SELFTEST cannot run: the tree does not pass its own check");
    for (const p of base) console.error("  " + p);
    process.exit(1);
  }
  const swap = (key, from, to) => {
    const c = { ...good };
    c[key] = c[key].split(from).join(to);
    if (c[key] === good[key]) throw new Error(`mutation did not apply: ${from}`);
    return c;
  };

  /** A capability lives across both documents, so a control that removes it removes it twice. */
  const drop = (re) => [
    { ...good, plan: good.plan.replace(re, "x"), features: good.features.replace(re, "x") },
    files,
  ];

  const CASES = [
    ["smali dropped", () => drop(/smali/gi), true],
    ["jump-to-definition dropped", () => drop(/jump[- ]to[- ]definition|jump from a call/gi), true],
    ["the keyboard-free pane dropped", () =>
      drop(/without the on-screen keyboard|driven by tapping/gi), true],
    ["XAPK dropped", () => drop(/XAPK/g), true],
    ["a date committed to", () =>
      [swap("plan", "after the polish", "shipping by Q3 2027"), files], true],
    ["a planned feature marked shipped", () =>
      [swap("features", "| L5 | M11 | PLANNED |", "| L5 | M11 | SHIPPED |"), files], true],
    ["a milestone removed", () => [swap("plan", "**M12**", "M-gone"), files], true],
    ["the positioning published", () =>
      [good, [...files, ["README.md", "Filet is an open source version of MT Manager."]]], true],
    ["the positioning published another way", () =>
      [good, [...files, ["PLAN.md", "We are remaking MT Manager but better."]]], true],
    // The positive control that matters: naming the layout is description, not positioning,
    // and it is already in the tree.
    ["a descriptive reference to the layout", () =>
      [good, [...files, ["x.kt", "Side-by-side is the MT Manager layout the mock settled on."]]], false],
  ];

  let bad = 0;
  for (const [name, mutate, expect] of CASES) {
    const [s, f] = mutate();
    const caught = problems(s, f).length > 0;
    if (caught !== expect) {
      console.error(`SELFTEST FAILED: ${name} — expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(
    `ROADMAP SELFTEST OK  (${CASES.filter((c) => c[2]).length} negative controls, ` +
      `${CASES.filter((c) => !c[2]).length + 1} positive)`,
  );
  process.exit(0);
}

const files = publicTextFiles();
if (files === null) {
  console.log("ROADMAP CHECK SKIPPED: not a git checkout, so what is published cannot be known");
  process.exit(2);
}
const found = problems(read(), files);
if (found.length) {
  console.error("The roadmap does not hold what it was given:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
console.log(
  `ROADMAP OK  (${CAPABILITIES.length} capabilities recorded, no dates committed to, ` +
    `positioning kept out of ${files.length} published files)`,
);
