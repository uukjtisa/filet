#!/usr/bin/env node
/**
 * `docs/AI_USE.md` exists, says what it has to, and is NOT linked from the README.
 *
 * The last part is the unusual one and it is deliberate. Nic asked for the note to be
 * findable and not advertised: a README that opens by declaring AI assistance invites the
 * reading he is worried about, which is that the design was outsourced too. The note is for
 * somebody who went looking, and a check is what keeps a future tidy-up from "helpfully"
 * adding the link back.
 *
 *   node tools/check-aiuse.mjs              check
 *   node tools/check-aiuse.mjs --selftest   prove it fails on each known violation
 *
 * Prints "AIUSE OK" / "AIUSE SELFTEST OK" only after every assertion passes.
 */
import { readFileSync, existsSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

/** Each rule: what the note must contain, and the phrase that proves it. */
const MUST_MENTION = [
  ["the human decisions", /design(ed)? the system|Who decided what/i],
  ["the layer model, by name", /L0|virtual filesystem/i],
  ["the enforced rules", /check-r3|R3/],
  ["what the AI did", /AI wrote|implementation|refactoring/i],
  ["the licence, so a reader can go and look", /GPL-3\.0/],
];

/**
 * Phrases that would turn a statement of method into an apology.
 *
 * Not a style opinion: "vibe coded" and "just prompted" are the exact readings he asked the
 * page to avoid, and a later edit reaching for them would undo the point of the page.
 */
const MUST_NOT_SAY = [/vibe[- ]cod/i, /\bjust prompted\b/i, /\bAI slop\b/i, /\bfully automated\b/i];

function check(root) {
  const problems = [];
  const notePath = join(root, "docs", "AI_USE.md");

  if (!existsSync(notePath)) {
    problems.push("docs/AI_USE.md is missing");
    return problems;
  }
  const note = readFileSync(notePath, "utf8");

  for (const [what, pattern] of MUST_MENTION) {
    if (!pattern.test(note)) problems.push(`AI_USE.md never mentions ${what}`);
  }
  for (const pattern of MUST_NOT_SAY) {
    if (pattern.test(note)) problems.push(`AI_USE.md uses ${pattern} — that is the reading it exists to avoid`);
  }
  if (note.length < 800) problems.push("AI_USE.md is too short to divide the work honestly");

  const readmePath = join(root, "README.md");
  if (existsSync(readmePath)) {
    const readme = readFileSync(readmePath, "utf8");
    if (/AI_USE/i.test(readme)) {
      problems.push("README.md links AI_USE.md — the note is deliberately not advertised there");
    }
  }
  return problems;
}

function selftest() {
  const root = mkdtempSync(join(tmpdir(), "aiuse-"));
  const good = readFileSync(join(process.cwd(), "docs", "AI_USE.md"), "utf8");
  const write = (rel, body) => {
    mkdirSync(join(root, rel, ".."), { recursive: true });
    writeFileSync(join(root, rel), body);
  };
  const cases = [
    ["missing note", () => {}, /missing/],
    ["note with no human half", () => write("docs/AI_USE.md", "AI wrote implementation. ".repeat(50) + " GPL-3.0 L0 check-r3"), /human|decided/i],
    ["note that apologises", () => write("docs/AI_USE.md", good + "\\n\\nIt was vibe coded."), /vibe/i],
    ["readme that advertises it", () => { write("docs/AI_USE.md", good); write("README.md", "see docs/AI_USE.md"); }, /advertised/],
  ];
  let failures = 0;
  for (const [name, setup, expected] of cases) {
    rmSync(root, { recursive: true, force: true });
    mkdirSync(join(root, "docs"), { recursive: true });
    setup();
    const problems = check(root);
    const hit = problems.some((p) => expected.test(p));
    if (!hit) {
      failures++;
      console.error(`SELFTEST FAIL: "${name}" was not caught. Got: ${problems.join("; ") || "(nothing)"}`);
    }
  }
  rmSync(root, { recursive: true, force: true });
  if (failures) {
    console.error(`AIUSE SELFTEST: ${failures} case(s) not caught`);
    process.exit(1);
  }
  console.log("AIUSE SELFTEST OK  (4 negative controls all caught)");
}

if (process.argv.includes("--selftest")) {
  selftest();
} else {
  const problems = check(process.cwd());
  if (problems.length) {
    console.error("AIUSE PROBLEMS: " + problems.length);
    for (const p of problems) console.error("  " + p);
    process.exit(1);
  }
  console.log("AIUSE OK  (docs/AI_USE.md present, divides the work, not linked from the README)");
}
