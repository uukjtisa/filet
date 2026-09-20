#!/usr/bin/env node
/**
 * The translation spec exists, is current, and cites every surface.
 *
 * A mock loses fidelity on the way into real code because the implementer re-derives values by
 * eye instead of reading them off, and because a surface nobody located is a surface that
 * quietly does not get built. The spec is the defence against both, and a spec that has gone
 * stale is worse than none - it sends somebody to the wrong line and looks authoritative.
 *
 * So this checks three things: that it was generated from the source and still matches it,
 * that every surface has a citation rather than a blank, and that each one carries the note
 * saying what must not be lost. It cannot check that the note is any good.
 *
 *   node tools/check-redesignspec.mjs              check
 *   node tools/check-redesignspec.mjs --selftest   prove it catches a blank citation
 *
 * Prints "REDESIGNSPEC OK" only after every assertion passes.
 */
import { existsSync, readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";

const DOC = "docs/early_mockups_preDev/REDESIGN-SPEC.md";

export function problems(doc) {
  const found = [];
  if (!doc) return [`${DOC} is missing; run node tools/make-redesign-spec.mjs`];

  if (!/> \*\*Generated\.\*\*/.test(doc)) {
    found.push("the spec does not say it is generated, so somebody will edit it by hand");
  }

  const rows = [...doc.matchAll(/^\| \*\*([^*]+)\*\* \| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \|$/gm)];
  if (rows.length < 30) {
    found.push(`only ${rows.length} surfaces cited; the redesign covers far more than that`);
  }
  for (const [, name, where, mock, gate, note] of rows) {
    if (!/`[^`]+:\d+`|_new, nothing to replace_/.test(where)) {
      found.push(`${name.trim()} has no file and line, so nobody can open the thing it replaces`);
    }
    if (!/`[^`]+`/.test(mock)) found.push(`${name.trim()} does not say what draws it in the mock`);
    if (!/^N\d+$/.test(gate.trim())) found.push(`${name.trim()} is not tied to a gate`);
    if (note.trim().split(/\s+/).length < 5) {
      found.push(`${name.trim()} has no note saying what must not be lost in translation`);
    }
  }
  return found;
}

if (process.argv.includes("--selftest")) {
  const head = "> **Generated.** by a script\n\n| S | A | M | G | N |\n|---|---|---|---|---|\n";
  const row = (n, where, mock, gate, note) => `| **${n}** | ${where} | ${mock} | ${gate} | ${note} |\n`;
  const many = (f) => Array.from({ length: 32 }, (_, i) => f(i)).join("");
  const good = head + many((i) => row(`S${i}`, "`a/b.kt:1`", "`x`", "N1", "a note long enough to say something"));
  const CASES = [
    ["a full spec", good, false],
    ["missing entirely", "", true],
    ["not marked generated", good.replace("> **Generated.**", "> Notes."), true],
    ["too few surfaces", head + row("S", "`a/b.kt:1`", "`x`", "N1", "a note long enough to say something"), true],
    ["a blank citation", good.replace("`a/b.kt:1`", "TBD"), true],
    ["no mock reference", good.replace("| `x` |", "| x |"), true],
    ["not tied to a gate", good.replace("| N1 |", "| later |"), true],
    ["a note that says nothing", good.replace("a note long enough to say something", "fix it"), true],
  ];
  let bad = 0;
  for (const [name, doc, expect] of CASES) {
    const got = problems(doc).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} - expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  const neg = CASES.filter((c) => c[2]).length;
  console.log(`REDESIGNSPEC SELFTEST OK  (${neg} negative controls, ${CASES.length - neg} positive)`);
  process.exit(0);
}

const doc = existsSync(DOC) ? readFileSync(DOC, "utf8") : "";
const found = problems(doc);
if (found.length) {
  console.error("The translation spec cannot be relied on:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
// And it has to still agree with the source it was generated from.
try {
  execFileSync("node", ["tools/make-redesign-spec.mjs", "--check"], { stdio: "pipe" });
} catch {
  console.error(`${DOC} is out of date. Run: node tools/make-redesign-spec.mjs`);
  process.exit(1);
}
const n = [...doc.matchAll(/^\| \*\*/gm)].length;
console.log(`REDESIGNSPEC OK  (${n} surfaces, each with a file, a line, a mock reference and a gate)`);
