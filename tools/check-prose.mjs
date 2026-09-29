#!/usr/bin/env node
/**
 * How much the app talks.
 *
 * This is the third time the same note has been made about the same thing: dialogues and rows
 * had grown paragraphs, warnings had become essays, and every one of them was reasonable on its
 * own. That is exactly why it needs a number rather than another resolution - no single sentence
 * is the problem, and nobody reviewing one line can see the total.
 *
 * ## The rule
 *
 * A string the reader sees in a **control surface** - a dialogue, a row, a sheet, a settings
 * entry - gets [BUDGET] characters. Past that it is either shortened or it goes behind a
 * collapsed notice (`DlgNote`, or `NoteStack` on a tab), shut, reachable by one tap.
 *
 * The budget is not a style preference. It is roughly two lines at the sizes these surfaces use,
 * and a control that needs three lines of explanation is usually a control that is named wrong.
 *
 * ## What is exempt, and why exemption is explicit
 *
 * Three things are genuinely not this:
 *
 *  - **Non-UI files.** An HTTP header, a crash-report template, an SVG path and a trace line are
 *    all long strings and none of them is prose at a person. Listed in [SKIP].
 *  - **Surfaces that are reading material.** The About page, onboarding and the crash screen
 *    exist to be read. Listed in [ESSAY_OK].
 *  - **One line that has earned it**, marked in the source with `// PROSE OK: <reason>` on the
 *    line before. The reason is required and is the point: it makes the decision reviewable
 *    instead of invisible.
 *
 * Everything else counts, including a string built by concatenation - splitting a paragraph
 * across three `+` operators makes it shorter in the source and no shorter on the screen.
 *
 *   node tools/check-prose.mjs              check
 *   node tools/check-prose.mjs --list       every offender, longest first
 *   node tools/check-prose.mjs --selftest   prove the rules catch what they claim
 *
 * Prints "PROSE OK" when the app is not lecturing.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = "app/src/main/java/dev/niccc2007/filet";

/** Two lines at dialogue type sizes. */
export const BUDGET = 96;

/**
 * How many always-visible warning banners one composable may draw.
 *
 * One. A banner is for the single thing standing between the reader and a working result;
 * anything else is a caption, or a collapsed note behind a glyph.
 *
 * This exists because the character budget alone let a form ship with FOUR short `DlgWarn`s -
 * a setup hint, an action, a readout and a standing fact about the protocol. Each passed on
 * length and together they turned the surface into a poster. The count is the rule the length
 * check could not express.
 */
export const MAX_BANNERS = 1;

/**
 * Files whose long strings are not addressed to a person.
 *
 * Each one is here for a stated reason. "It has long strings" is not a reason; "its long strings
 * are wire format" is.
 */
const SKIP = [
  "browser/Icons.kt", // SVG path data
  "crash/CrashReport.kt", // the report template, written to a file
  "webdav/", // HTTP headers and XML
  "nearby/HttpServer.kt", // HTTP headers
  "nearby/WebTheme.kt", // generated CSS
  "nearby/WebPage.kt", // the served page's own markup
  "index/", // SQL and trace lines
  "script/ScriptEngine.kt", // denial messages built from a path, and the Lua preamble
  "metadata/", // the format table's caveat column, which is documentation
  "data/", // preference keys
  "update/UpdateNotes.kt", // release notes, which are prose on purpose
];

/**
 * Surfaces that exist to be read rather than acted on.
 *
 * A crash screen with a two-line explanation is worse than one with five. Onboarding is a
 * slideshow. About is a page about the app. None of these is a control.
 */
const ESSAY_OK = [
  "about/",
  "signet/OnboardingScreen.kt",
  "crash/CrashActivity.kt",
];

/** Where a string is aimed at a person. */
const SLOTS =
  /(?:^|[^\w.])(?:Text|toast|SectionRow|RuleLabel|WarnNote|MicroLabel|EmptyTab|DlgWarn|DlgKv|DlgSection)\s*\(|(?:label|sub|subtitle|detail|hint|title|note|blurb|body|message|text|placeholder)\s*=/;

function files(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) files(p, out);
    else if (name.endsWith(".kt")) out.push(p.replace(/\\/g, "/"));
  }
  return out;
}

const skipped = (rel) => SKIP.some((s) => rel.includes(s));
const essay = (rel) => ESSAY_OK.some((s) => rel.includes(s));

/**
 * Every over-budget string in one file's text.
 *
 * Joins a `"a" + "b"` run into one length first, because that is one sentence on screen however
 * many literals it took to write.
 */
/**
 * Composables drawing more than [MAX_BANNERS] always-visible warning banners.
 *
 * Counted per function rather than per file: a file holding three dialogues, one banner each, is
 * fine. Only banners that are unconditionally drawn OR guarded by a simple state check count -
 * a `when` returning one of several messages is still one banner.
 */
export function banners(rel, src) {
  if (skipped(rel) || essay(rel)) return [];
  const lines = src.split("\n");
  const out = [];
  let fn = null;
  let count = 0;
  let depth = 0;

  const flush = () => {
    if (fn && count > MAX_BANNERS) out.push({ rel, line: fn.line, name: fn.name, count });
    fn = null;
    count = 0;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const t = line.trim();

    const decl = /^(?:private\s+|internal\s+)?fun\s+([A-Za-z0-9_.]+)\s*\(/.exec(t);
    if (decl && depth === 0) {
      flush();
      fn = { name: decl[1], line: i + 1 };
    }

    if (!t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")) {
      if (/\bDlgWarn\s*\(/.test(line)) count++;
      depth += (line.match(/\{/g) || []).length - (line.match(/\}/g) || []).length;
      if (depth <= 0) depth = 0;
    }
  }
  flush();
  return out;
}

export function offenders(rel, src) {
  if (skipped(rel) || essay(rel)) return [];
  const lines = src.split("\n");
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const t = line.trim();
    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
    if (t.startsWith("import ") || t.startsWith("package ")) continue;

    // An explicit, reasoned exemption on the line above.
    const prev = (lines[i - 1] || "").trim();
    if (/^\/\/\s*PROSE OK:\s*\S/.test(prev)) continue;

    if (!SLOTS.test(line)) continue;

    // Absorb the rest of the call. A paragraph is routinely written as
    //
    //     Text(
    //         "half of it " +
    //             "the other half",
    //     )
    //
    // which puts the slot on one line and every literal on later ones. Keying this off a
    // trailing `+` looked right and read none of them - it never got past `Text(`. Following the
    // open parenthesis instead reaches the whole argument list, which is where the sentence is.
    let text = line;
    let j = i;
    let depth = (line.match(/\(/g) || []).length - (line.match(/\)/g) || []).length;
    while (depth > 0 && j + 1 < lines.length && j - i < 8) {
      j++;
      text += " " + lines[j];
      depth += (lines[j].match(/\(/g) || []).length - (lines[j].match(/\)/g) || []).length;
    }

    // Split on branch arrows before measuring. One `when` shows one of its arms, so the length
    // a reader meets is the longest arm - not the total, which counted a four-state status line
    // as four times its real size.
    // Split on both branch forms. An `if (x) "a" else "b"` shows one string just as a `when`
    // does, and summing the two reported a 58-character line as 115.
    const branches = /->|\belse\b/.test(text) ? text.split(/->|\belse\b/) : [text];
    let total = 0;
    for (const branch of branches) total = Math.max(total, prose(branch));
    if (total > BUDGET) out.push({ rel, line: i + 1, len: total, text: text.trim().slice(0, 70) });
    i = j;
  }
  return out;
}

/** The visible length of the prose literals in one expression. */
function prose(text) {
    let total = 0;
    for (const m of text.matchAll(/"((?:[^"\\]|\\.)*)"/g)) {
      const s = m[1];
      // Not prose: a single word, a path, a mime list, a format string. Deliberately narrow -
      // an earlier version excluded anything lowercase, which silently excused every sentence
      // that happened not to start with a capital.
      if (!s.includes(" ")) continue;
      if (s.includes("://")) continue;
      if (/^[\w.-]+(?:\/[\w.*-]+)+$/.test(s)) continue;
      if (/^[%$][\w.,:+ -]*$/.test(s)) continue;
      total += s.length;
    }
  return total;
}

if (process.argv.includes("--selftest")) {
  const CASES = [
    ["a short label passes", "ui/X.kt",
      'Text("Copy path", fontSize = 13.sp)', false],
    ["a paragraph in a Text is caught", "ui/X.kt",
      `Text("${"word ".repeat(30)}", fontSize = 11.sp)`, true],
    ["a paragraph split across + is still caught", "ui/X.kt",
      `Text(\n    "${"word ".repeat(12)}" +\n        "${"more ".repeat(12)}",\n)`, true],
    ["a reasoned exemption is honoured", "ui/X.kt",
      `// PROSE OK: the legal notice has to be quoted in full\nText("${"word ".repeat(30)}")`, false],
    ["an exemption with no reason is not honoured", "ui/X.kt",
      `// PROSE OK:\nText("${"word ".repeat(30)}")`, true],
    ["a sub = slot is checked", "ui/X.kt",
      `sub = "${"word ".repeat(30)}",`, true],
    ["a long wire string outside a slot is ignored", "ui/X.kt",
      `val h = "Allow: OPTIONS, GET, HEAD, PROPFIND, PROPPATCH, PUT, DELETE, MKCOL, COPY, MOVE"`, false],
    ["a skipped file is skipped", "browser/Icons.kt",
      `Text("${"word ".repeat(30)}")`, false],
    ["a reading surface is allowed", "about/AboutPage.kt",
      `Text("${"word ".repeat(30)}")`, false],
    ["a comment holding a long sentence is ignored", "ui/X.kt",
      `// Text("${"word ".repeat(30)}")`, false],
  ];

  // The banner rule, which the character budget could not express: four SHORT warnings on one
  // surface all pass on length and together read as a wall of faults.
  const BANNER_CASES = [
    ["one banner is fine", "ui/X.kt",
      `fun A() {\n  DlgWarn("nope")\n}`, false],
    ["two banners in one surface are caught", "ui/X.kt",
      `fun A() {\n  DlgWarn("one")\n  DlgWarn("two")\n}`, true],
    ["four banners are caught", "ui/X.kt",
      `fun A() {\n  DlgWarn("a")\n  DlgWarn("b")\n  DlgWarn("c")\n  DlgWarn("d")\n}`, true],
    ["one banner each in two surfaces is fine", "ui/X.kt",
      `fun A() {\n  DlgWarn("a")\n}\nfun B() {\n  DlgWarn("b")\n}`, false],
    ["a banner in a comment does not count", "ui/X.kt",
      `fun A() {\n  DlgWarn("a")\n  // DlgWarn("b")\n}`, false],
    ["notes and captions are not banners", "ui/X.kt",
      `fun A() {\n  DlgWarn("a")\n  DlgNote("t", "b")\n  DlgCaption("c")\n}`, false],
    ["a skipped file is skipped", "browser/Icons.kt",
      `fun A() {\n  DlgWarn("a")\n  DlgWarn("b")\n}`, false],
  ];

  let bad = 0;
  for (const [name, rel, src, expect] of CASES) {
    const got = offenders(rel, src).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} - expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  for (const [name, rel, src, expect] of BANNER_CASES) {
    const got = banners(rel, src).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} - expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(
    `PROSE SELFTEST OK  (${CASES.filter((c) => c[3]).length + BANNER_CASES.filter((c) => c[3]).length}` +
      ` negative controls, ` +
      `${CASES.filter((c) => !c[3]).length + BANNER_CASES.filter((c) => !c[3]).length} positive)`,
  );
  process.exit(0);
}

const all = [];
const walls = [];
for (const p of files(ROOT)) {
  const rel = p.slice(ROOT.length + 1);
  const src = readFileSync(p, "utf8");
  all.push(...offenders(rel, src));
  walls.push(...banners(rel, src));
}
all.sort((a, b) => b.len - a.len);
walls.sort((a, b) => b.count - a.count);

if (process.argv.includes("--list")) {
  for (const o of all) console.log(`${String(o.len).padStart(4)}  ${o.rel}:${o.line}  ${o.text}`);
  console.log(`\n${all.length} over ${BUDGET}`);
  process.exit(0);
}

if (walls.length) {
  console.error(
    `${walls.length} surface(s) draw more than ${MAX_BANNERS} warning banner at once.
` +
      `A banner is for the one thing between the reader and a working result. Feedback on
` +
      `what was typed is a DlgCaption; a standing fact is a collapsed DlgNote.
`,
  );
  for (const w of walls) {
    console.error(`  ${w.count} banners  ${w.rel}:${w.line}  ${w.name}`);
  }
  process.exit(1);
}

if (all.length) {
  console.error(
    `The app is lecturing. ${all.length} string(s) past ${BUDGET} characters in a control` +
      ` surface.\nShorten it, or put it behind a collapsed DlgNote / NoteStack, or mark the` +
      ` line\nabove with "// PROSE OK: <why this one has earned it>".\n`,
  );
  for (const o of all.slice(0, 25)) {
    console.error(`  ${String(o.len).padStart(4)}  ${o.rel}:${o.line}  ${o.text}`);
  }
  if (all.length > 25) console.error(`  ... and ${all.length - 25} more (--list for all)`);
  process.exit(1);
}

console.log(
  `PROSE OK  (nothing past ${BUDGET} characters, and no surface with more than ` +
    `${MAX_BANNERS} warning banner)`,
);
