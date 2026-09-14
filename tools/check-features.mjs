#!/usr/bin/env node
/**
 * The three documents that describe Filet to somebody who has not read the code, checked
 * against themselves.
 *
 * All three drift in the same quiet way: a row is added and a total at the top is not, a
 * limitation is fixed in one file and left standing in another, an "open" item is closed by
 * having stopped being mentioned. None of it is visible while writing, and all of it is visible
 * to a reader.
 *
 * Three things are enforced here, and each one has already gone wrong once:
 *
 *  1. **`FEATURES.md`'s standings must be a count of its own table.** The header was hand-
 *     incremented and came out one too high, because counting fifteen new rows by hand and
 *     counting the table are different operations.
 *  2. **A retracted claim must be retracted in the README too.** The whole reason the compress
 *     dialog has no 7z password is a licence measurement, and a README that omits it leaves the
 *     reader to conclude the feature was forgotten.
 *  3. **An unmet item from a previous round must still be listed.** A round ending does not
 *     close the gates the previous one left open, and the failure mode is that nobody notices
 *     they stopped being written down.
 *
 *   node tools/check-features.mjs              check
 *   node tools/check-features.mjs --selftest   prove it fails on each way this breaks
 *
 * Prints "FEATURES OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";

const FEATURES = "FEATURES.md";
const README = "README.md";
const FIXES = "FIXES.md";

const STATUSES = ["SHIPPED", "BUILT", "IDEA", "CUT"];

/**
 * Count the feature table by its status column.
 *
 * Positional rather than a regex over the line: a Notes cell can contain anything, including
 * the word SHIPPED, and a regex that searched the whole row would count those.
 */
export function tally(features) {
  const counts = Object.fromEntries(STATUSES.map((s) => [s, 0]));
  const ids = [];
  for (const line of features.split("\n")) {
    // `F74b`..`F74f` exist: a feature that turned out to be several got lettered suffixes
    // rather than renumbering everything after it. The id pattern has to admit them, and the
    // first version of this checker did not - it reported 77 rows against a real 83.
    if (!/^\| F\d+[a-z]? \|/.test(line)) continue;
    const cells = line.split("|").map((c) => c.trim());
    if (cells.length < 7) continue;
    ids.push(cells[1]);
    const status = cells[5].replace(/\*/g, "");
    if (status in counts) counts[status]++;
  }
  return { counts, ids };
}

/** Claims from a previous round that must still be visible as open. */
const STILL_OPEN = [
  { what: "the Termux usr/bin case", from: /Termux/ },
  { what: "inzip: inside a RAR", from: /inzip:[^\n]*RAR|RAR[^\n]*inzip:/ },
];

/** The retracted claim, and where a reader has to be able to find it. */
const SEVEN_ZIP_LIMIT = [
  { file: "README", what: "that 7z cannot take a password here", from: /7z cannot be given a password/i },
  { file: "README", what: "what to use instead", from: /Use zip if the archive needs a password/i },
  { file: "FEATURES", what: "the cut row for the native writer", from: /Native 7z writer~~/ },
];

export function check(features, readme, fixes) {
  const problems = [];
  const { counts, ids } = tally(features);

  if (ids.length === 0) {
    problems.push("FEATURES.md has no feature rows at all — has the table format changed?");
    return problems;
  }

  const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
  if (dupes.length) {
    problems.push(`FEATURES.md reuses ${[...new Set(dupes)].join(", ")} — a row id has to be unique`);
  }

  // The standings line, in the order the file writes it.
  const stated = features.match(
    /\*\*(\d+) SHIPPED · (\d+) BUILT · (\d+) IDEA · (\d+) CUT\.\*\*/,
  );
  if (!stated) {
    problems.push("FEATURES.md has no standings line to check the table against");
  } else {
    const said = { SHIPPED: +stated[1], BUILT: +stated[2], IDEA: +stated[3], CUT: +stated[4] };
    for (const s of STATUSES) {
      if (said[s] !== counts[s]) {
        problems.push(
          `FEATURES.md says ${said[s]} ${s} and the table holds ${counts[s]} — ` +
            "the header was incremented by hand rather than counted",
        );
      }
    }
  }

  for (const c of SEVEN_ZIP_LIMIT) {
    const text = c.file === "README" ? readme : features;
    if (!c.from.test(text)) {
      problems.push(
        `${c.file}.md does not say ${c.what} — a limit that only exists inside a dialog reads ` +
          "as a feature somebody forgot",
      );
    }
  }

  for (const o of STILL_OPEN) {
    if (!o.from.test(fixes)) {
      problems.push(
        `FIXES.md no longer lists ${o.what} as open — a round ending does not close the ` +
          "previous round's gates, and dropping the mention is how that happens quietly",
      );
    }
  }

  return problems;
}

if (process.argv.includes("--selftest")) {
  const files = [FEATURES, README, FIXES];
  if (files.some((f) => !existsSync(f))) {
    console.error("SELFTEST FAIL: a real file is missing, so the positive control is meaningless");
    process.exit(1);
  }
  const [features, readme, fixes] = files.map((f) => readFileSync(f, "utf8"));

  // The positive control first. Without it, a checker that reported everything would "pass"
  // every negative case below.
  const clean = check(features, readme, fixes);
  if (clean.length) {
    console.error("SELFTEST FAIL: the real files are reported as broken: " + clean.join("; "));
    process.exit(1);
  }

  const { counts } = tally(features);
  const offByOne = features.replace(
    /\*\*\d+ SHIPPED/,
    `**${counts.SHIPPED + 1} SHIPPED`,
  );
  const aRow = features.match(/^\| F\d+[a-z]? \|.*$/m)[0];

  const cases = [
    ["a standings line one too high", offByOne, readme, fixes, /incremented by hand/],
    ["a duplicated row id", features + "\n" + aRow + "\n", readme, fixes, /has to be unique/],
    ["the README silent on the 7z limit", features, readme.replace(/7z cannot be given a password/i, "7z is great"), fixes, /README/],
    ["the cut row quietly un-cut", features.replace(/Native 7z writer~~/, "Native 7z writer"), readme, fixes, /FEATURES/],
    ["a previous round's open item dropped", features, readme, fixes.replace(/Termux/g, "elsewhere"), /does not close/],
  ];
  let failures = 0;
  for (const [name, f, r, x, expected] of cases) {
    const problems = check(f, r, x);
    if (!problems.some((p) => expected.test(p))) {
      console.error(`SELFTEST FAIL: "${name}" was not caught (got: ${problems.join("; ") || "nothing"})`);
      failures++;
    }
  }
  if (failures) process.exit(1);
  console.log(`FEATURES SELFTEST OK  (${cases.length} negative controls and 1 positive)`);
  process.exit(0);
}

for (const f of [FEATURES, README, FIXES]) {
  if (!existsSync(f)) {
    console.error(`FAIL: ${f} is missing`);
    process.exit(1);
  }
}
const [features, readme, fixes] = [FEATURES, README, FIXES].map((f) => readFileSync(f, "utf8"));
const problems = check(features, readme, fixes);
if (problems.length) {
  console.error(`FEATURES PROBLEMS: ${problems.length}`);
  for (const p of problems) console.error("  " + p);
  process.exit(1);
}
const { counts, ids } = tally(features);
console.log(
  `FEATURES OK  (${ids.length} rows counted: ` +
    STATUSES.map((s) => `${counts[s]} ${s.toLowerCase()}`).join(", ") +
    "; the standings match the table, the 7z limit is in the README, and the previous round's " +
    "open items are still listed)",
);
