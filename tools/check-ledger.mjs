#!/usr/bin/env node
/**
 * Every reported item has a gate, exactly one, and still carries its own wording.
 *
 * The standing rule for these ledgers is that each item reported becomes one gate, in the
 * order it was written, in the original wording with spelling corrected and nothing else - no
 * splitting, no merging, no summarising. That rule exists because items were being lost, and
 * "I checked, nothing was missed" is worth nothing said out loud.
 *
 * So it is checked. The numbering is a single sequence across every round, which makes a gap
 * or a repeat mechanically visible:
 *
 *  - a **gap** means an item was read and never written down;
 *  - a **duplicate** means two rounds each think the other is handling it;
 *  - a gate whose title is short or tidy is a **summary**, which is the failure the rule was
 *    written against.
 *
 * `.unlazy/` is gitignored, so continuous integration never sees it. This exits 2 - the
 * established "skip with a reason" code - rather than failing there.
 *
 *   node tools/check-ledger.mjs              check
 *   node tools/check-ledger.mjs --selftest   prove it catches a gap, a repeat and a summary
 *
 * Prints "LEDGER OK" only after every assertion passes.
 */
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

const ROOT = ".unlazy";

/**
 * The lowest number in the shared sequence.
 *
 * Rounds before this used per-round numbering, so they are not part of the run.
 */
const FIRST = 1;

/**
 * Shorter than this and a gate is a summary rather than what was said.
 *
 * Reported items are sentences, usually several. Nothing genuinely reported has ever been
 * under forty characters, and a tidy one-liner is exactly what the rule forbids.
 */
const MIN_TITLE = 40;

/**
 * Which numbering scheme a ledger is on, or null if it does not say.
 *
 * Earlier rounds numbered their gates per round, so their N1 and this sequence's N1 are
 * different items entirely - comparing them reports duplicates that do not exist. A ledger
 * therefore declares its scheme, rather than the folder name being read as a magic number.
 *
 * **Not declaring is an error, not a quiet exclusion.** A filter that silently drops what it
 * does not recognise turns a lost marker into a green run: drop `SEQUENCE: shared` from the
 * ledger holding N24-N38 and the remaining numbers still run 1..23 unbroken, so the gap check
 * has nothing to catch and fifteen items vanish while the tool says OK. Requiring every
 * ledger to say which run it is on is what makes that impossible.
 *
 * It has to be its own line. The phrase written mid-sentence in a gate title or a note is
 * prose about the rule, not the ledger declaring anything.
 */
export function sequenceOf(md) {
  const m = md.match(/^SEQUENCE: (shared|per-round)[ \t]*$/m);
  return m ? m[1] : null;
}

/** @returns {{shared: [string, string][], undeclared: string[]} | null} */
function ledgers() {
  if (!existsSync(ROOT)) return null;
  const shared = [];
  const undeclared = [];
  for (const dir of readdirSync(ROOT)) {
    const p = join(ROOT, dir, "GATES.md");
    if (!existsSync(p)) continue;
    const md = readFileSync(p, "utf8");
    const scheme = sequenceOf(md);
    if (scheme === "shared") shared.push([dir, md]);
    else if (scheme === null) undeclared.push(dir);
  }
  return { shared, undeclared };
}

/** Every `N<number>` gate across the ledgers, as {n, round, title, absorbed}. */
export function gatesIn(files) {
  const found = [];
  for (const [round, md] of files) {
    // A ledger marked absorbed has been carried into another one; its gates live there now
    // and counting them here would report every one of them as a duplicate.
    const absorbed = /^>\s*\*\*ABSORBED/m.test(md);
    for (const m of md.matchAll(/^- \[[ x]\] N(\d+):[ \t]*(.*)$/gm)) {
      found.push({ n: Number(m[1]), round, title: m[2].trim(), absorbed });
    }
  }
  return found;
}

export function problems(files) {
  const found = [];
  const gates = gatesIn(files).filter((g) => !g.absorbed);
  if (gates.length === 0) return ["no numbered gates found in any ledger"];

  const byNumber = new Map();
  for (const g of gates) {
    if (!byNumber.has(g.n)) byNumber.set(g.n, []);
    byNumber.get(g.n).push(g);
  }

  // A number in two rounds is allowed ONLY when the item genuinely spans both - the split
  // ones say so in their own note. Two unrelated gates sharing a number is a real fault.
  for (const [n, list] of byNumber) {
    if (list.length > 1) {
      const titles = new Set(list.map((g) => g.title));
      if (titles.size > 1) {
        found.push(`N${n} is used for two different items (${list.map((g) => g.round).join(", ")})`);
      }
    }
  }

  const numbers = [...byNumber.keys()].sort((a, b) => a - b);
  const highest = numbers[numbers.length - 1];
  for (let i = Math.max(FIRST, numbers[0]); i <= highest; i++) {
    if (!byNumber.has(i)) found.push(`N${i} is missing: an item was read and never written down`);
  }

  for (const g of gates) {
    if (g.title.length < MIN_TITLE) {
      found.push(`N${g.n} in ${g.round} reads like a summary, not what was said: "${g.title}"`);
    }
  }
  return found;
}

if (process.argv.includes("--selftest")) {
  const good = [
    ["round13", "SEQUENCE: shared\n- [ ] N1: a long enough sentence describing something that was actually reported here\n- [x] N2: another long enough sentence describing a second thing that was reported\n"],
    ["round14", "SEQUENCE: shared\n- [ ] N3: a third long enough sentence describing yet another thing that was reported\n"],
  ];
  const CASES = [
    ["a gap in the sequence", [["r", "SEQUENCE: shared\n- [ ] N1: a long enough sentence describing something that was actually reported\n- [ ] N3: a long enough sentence describing a different thing that was reported\n"]], true],
    ["the same number for two different items", [
      ["a", "SEQUENCE: shared\n- [ ] N1: a long enough sentence describing something that was actually reported\n"],
      ["b", "SEQUENCE: shared\n- [ ] N1: a completely different sentence about a completely different report\n"],
    ], true],
    ["a summarised title", [["r", "SEQUENCE: shared\n- [ ] N1: fix sorting\n"]], true],
    ["no gates at all", [["r", "SEQUENCE: shared\n# A ledger with nothing in it\n"]], true],
    // the positive controls
    ["a clean pair of ledgers", good, false],
    ["one item deliberately spanning two rounds", [
      ["a", "SEQUENCE: shared\n- [ ] N1: a long enough sentence describing something that was actually reported\n"],
      ["b", "SEQUENCE: shared\n- [ ] N1: a long enough sentence describing something that was actually reported\n"],
    ], false],
    ["an absorbed ledger is not counted twice", [
      ["old", "SEQUENCE: shared\n> **ABSORBED into another ledger**\n\n- [ ] N9: a long enough sentence describing something that was reported once\n"],
      ["new", "SEQUENCE: shared\n- [ ] N1: a long enough sentence describing something that was actually reported\n"],
    ], false],
  ];
  // The declaration itself, which decides what gets checked at all and so is the one place a
  // mistake is invisible in the result.
  const MARKERS = [
    ["a ledger on the shared run", "SEQUENCE: shared\n\n- [ ] N1: x\n", "shared"],
    ["trailing whitespace after it", "SEQUENCE: shared   \n", "shared"],
    ["a ledger on its own numbering", "SEQUENCE: per-round\n", "per-round"],
    ["a ledger that says nothing", "# Round 7\n\n- [ ] N1: x\n", null],
    // The failure this check exists for: the marker goes missing and everything it held
    // disappears from the run without the count ever looking wrong.
    ["the marker deleted", "# Round 13\n\n- [ ] N24: x\n", null],
    // Prose about the rule is not the rule being invoked.
    ["the phrase inside a sentence", "each ledger says SEQUENCE: shared at the top\n", null],
    ["the phrase indented under a note", "  NOTE: SEQUENCE: shared\n", null],
    ["a scheme nobody defined", "SEQUENCE: whatever\n", null],
  ];

  let bad = 0;
  for (const [name, files, expect] of CASES) {
    const got = problems(files).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} — expected ${expect ? "caught" : "clean"}, got ${got ? "caught" : "clean"}`);
      bad++;
    }
  }
  for (const [name, md, expect] of MARKERS) {
    const got = sequenceOf(md);
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} — expected ${expect}, got ${got}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(
    `LEDGER SELFTEST OK  (${CASES.filter((c) => c[2]).length} negative controls, ` +
      `${CASES.filter((c) => !c[2]).length} positive, ${MARKERS.length} on the declaration)`,
  );
  process.exit(0);
}

const read = ledgers();
if (read === null || (read.shared.length === 0 && read.undeclared.length === 0)) {
  console.log("LEDGER CHECK SKIPPED: .unlazy/ is not present (it is gitignored, so CI never sees it)");
  process.exit(2);
}
if (read.undeclared.length) {
  console.error("These ledgers do not say which numbering they use, so they cannot be checked:\n");
  for (const d of read.undeclared) console.error(`  ${d}/GATES.md`);
  console.error(`\nAdd "SEQUENCE: shared" or "SEQUENCE: per-round" on its own line near the top.`);
  process.exit(1);
}
const files = read.shared;
const found = problems(files);
if (found.length) {
  console.error("The ledgers do not account for every item:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const gates = gatesIn(files).filter((g) => !g.absorbed);
const numbers = [...new Set(gates.map((g) => g.n))].sort((a, b) => a - b);
console.log(
  `LEDGER OK  (${numbers.length} items, N${numbers[0]}-N${numbers[numbers.length - 1]}, ` +
    `no gaps, no repeats, every gate in its own wording)`,
);
