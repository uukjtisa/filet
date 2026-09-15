#!/usr/bin/env node
/**
 * The repository is written in one voice, and that voice has no second person in it.
 *
 * Comments, KDoc, test names, checker docstrings and tracked documents describe **what was
 * wrong, why it was wrong, and why the fix is the fix**. They do not name, quote or refer to
 * whoever noticed the problem. That information is worth nothing to a reader and this is a
 * public repository.
 *
 * "The user asked for a file" is fine and stays: that is the person using Filet, not the
 * person who requested the change. What is banned is attribution for a DECISION.
 *
 * The rule was applied across the whole tree in round 11, which removed 158 attributions and
 * every embedded quotation. This checker exists so it stays applied: the habit that produced
 * them is easy to fall back into, and nothing else in the build would ever notice.
 *
 * ## What it rejects
 *
 *   // Reported by X: pinch to zoom stopped working.        <- attribution
 *   // X asked for *"a stop button or attempt limit"*.      <- attribution and a quotation
 *   // He wanted the counter to keep the old total.          <- a third party
 *
 * ## What it wants instead
 *
 *   // Bug identified here: `pointerInput` restarts only when its keys change, so the block
 *   // held `scale` at its first value and every pinch frame computed `1f * zoom`.
 *
 *   node tools/check-voice.mjs              check
 *   node tools/check-voice.mjs --selftest   prove it catches each form
 *
 * Prints "VOICE OK" only after every assertion passes.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, sep } from "node:path";

const ROOTS = ["app/src", "core-vfs/src", "core-index/src", "tools", "docs"];
const TOP_DOCS = ["README.md", "FEATURES.md", "FIXES.md", "PLAN.md", "GATES.md", "SEARCH.md", "NEARBY.md", "TEMPLATE.md"];
const EXT = [".kt", ".mjs", ".md"];

/**
 * Each pattern is a form that actually appeared in this repository before the sweep.
 *
 * Deliberately narrow. A bare "he" or "his" is ordinary English and appears in perfectly good
 * prose about a hypothetical user; what is banned is attribution — a named person, a
 * possessive about a requester, or a verb that frames the change as somebody's request.
 */
const BANNED = [
  { re: /\bNic(?:'s)?\b/gi, why: "names a person" },
  { re: /\breported by\s+(?!review\b)\w+/gi, why: "attributes the finding to someone" },
  { re: /\bhis (?:report|ask|call|words|wording|question|brief|verdict|pick|suggestion|correction|own)\b/gi, why: "possessive about a requester" },
  { re: /\bhe (?:said|asked|reported|wanted|chose|picked|flagged|rejected|expected)\b/gi, why: "a third party made the decision" },
  { re: /\bas (?:he|they) asked\b/gi, why: "frames the change as a request" },
  { re: /\bat his request\b/gi, why: "frames the change as a request" },
];

/**
 * A quotation of someone's phrasing.
 *
 * `*"…"*` is the markdown emphasis-plus-quote form every one of these used. The lookahead
 * matters: without it the pattern also matches the tail of the mime literal `"*<slash>*"`,
 * which is not a quotation and is all over the picker and handler code.
 */
const QUOTE = /\*"(?=[A-Za-z(\[])[^"]{0,600}"\*/g;

function walk(dir) {
  const out = [];
  let entries;
  try { entries = readdirSync(dir); } catch { return out; }
  for (const name of entries) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...walk(p));
    else if (EXT.some((e) => name.endsWith(e))) out.push(p);
  }
  return out;
}

function files() {
  const out = ROOTS.flatMap(walk);
  for (const d of TOP_DOCS) { try { statSync(d); out.push(d); } catch { /* absent is fine */ } }
  // Never the ledgers or scaffolding: they are gitignored and are working notes, not the repo.
  // Not itself: its docstring and selftest quote every form it rejects, on purpose.
  return out.filter(
    (f) =>
      !f.includes(`${sep}.unlazy${sep}`) &&
      !f.endsWith("CLAUDE.md") &&
      !f.endsWith("check-voice.mjs"),
  );
}

function scan(list, read = readFileSync) {
  const problems = [];
  for (const file of list) {
    const src = typeof read === "function" ? read(file, "utf8") : read;
    const lines = src.split("\n");
    lines.forEach((line, i) => {
      for (const { re, why } of BANNED) {
        re.lastIndex = 0;
        const m = re.exec(line);
        if (m) problems.push(`${file}:${i + 1}  "${m[0]}" — ${why}`);
      }
      QUOTE.lastIndex = 0;
      if (QUOTE.test(line)) problems.push(`${file}:${i + 1}  quotes somebody's phrasing`);
    });
  }
  return problems;
}

if (process.argv.includes("--selftest")) {
  const CONTROLS = [
    ["Reported by Nic: the picker returned nothing.", true],
    ["// Nic's call after seeing it.", true],
    ["* He asked for a stop button.", true],
    ["* The requirement was *\"a stop button or attempt limit\"*.", true],
    ["// his report was that folders went stale", true],
    ["// at his request, the bar scrolls", true],
    // the positive controls — ordinary prose that must NOT be flagged
    ["// Bug identified here: the block held `scale` at its first value.", false],
    ["// A caller that sets EXTRA_ALLOW_MULTIPLE and reads only getData() gets one file.", false],
    ['        "anything" to "*/*",', false],
    ["// The user sees a folder they cannot pick, so the refusal says why.", false],
    ["// Identified in review: the sweep ran after the moves rather than before.", false],
  ];
  let failed = 0;
  for (const [line, expect] of CONTROLS) {
    const got = scan(["case"], () => line).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${JSON.stringify(line)} — expected ${expect ? "caught" : "clean"}`);
      failed++;
    }
  }
  if (failed) process.exit(1);
  console.log(`VOICE SELFTEST OK  (${CONTROLS.filter((c) => c[1]).length} negative controls, ${CONTROLS.filter((c) => !c[1]).length} positive)`);
  process.exit(0);
}

const list = files();
const problems = scan(list);
if (problems.length) {
  console.error("The repository speaks in one voice and these lines break it:\n");
  for (const p of problems.slice(0, 40)) console.error("  " + p);
  if (problems.length > 40) console.error(`  … and ${problems.length - 40} more`);
  console.error("\nDescribe the defect and the reasoning, not who found it. See CLAUDE.md.");
  process.exit(1);
}
console.log(`VOICE OK  (${list.length} files, no attributions and no quoted phrasing)`);
