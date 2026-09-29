#!/usr/bin/env node
/**
 * Every dialogue in the app is drawn by the app's own dialogue kit.
 *
 * The redesign gave the app one dialogue shape - a card with a header that carries an icon and
 * a close cross, a scrolling body, and a footer whose buttons stretch on a phone. Surfaces left
 * on Material's `AlertDialog` do not get any of that: a different corner radius, a different
 * scrim, a title that cannot hold a subtitle, and exactly two button slots, which is why the
 * three-answer extension prompt used to cram two of its answers into the dismiss slot.
 *
 * The drift is invisible until two dialogues are open in the same session, so it is checked
 * rather than remembered.
 *
 *   node tools/check-dlgkit.mjs              check
 *   node tools/check-dlgkit.mjs --selftest   prove it catches a reintroduced AlertDialog
 *
 * `DropdownMenu` is deliberately NOT banned. A menu anchored to the toolbar button that opened
 * it is a menu, and the redesign keeps those; what it replaced was a dropdown used as a
 * DIALOGUE, which arrived pinned to the screen edge in a third of the width.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const APP = join(ROOT, "app/src/main/java");

/** The kit itself may name what it replaces. */
const ALLOWED = ["ui/dialogs/DlgKit.kt"];

// A preceding LETTER is excluded, a preceding dot is not: the fully-qualified
// `androidx.compose.material3.AlertDialog(` is exactly the form that needs catching, while
// `MyAlertDialogWrapper(` is a different name that happens to contain the word.
const BANNED = /(?<![A-Za-z])AlertDialog\s*\(/g;

const strip = (src) =>
  src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/^([ \t]*)\/\/.*$/gm, (m, i) => i + " ".repeat(m.length - i.length));

function walk(dir, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (e.endsWith(".kt")) out.push(p);
  }
  return out;
}

if (process.argv.includes("--selftest")) {
  const CASES = [
    ["a bare AlertDialog is caught", "AlertDialog(\n  onDismissRequest = {},\n)", true],
    ["a fully-qualified one is caught too",
      "androidx.compose.material3.AlertDialog(\n  onDismissRequest = {},\n)", true],
    ["the app's own Dlg passes", "Dlg(onDismiss = onDismiss) {\n  DlgHeader(i, \"T\")\n}", false],
    ["an anchored DropdownMenu is not a dialogue and passes",
      "DropdownMenu(expanded = true, onDismissRequest = onDismiss) { }", false],
    ["one inside a comment does not count", "// AlertDialog(\n", false],
    ["one inside a block comment does not count", "/*\n AlertDialog(\n*/\n", false],
    ["a name merely ENDING in it is not a match", "MyAlertDialogWrapper(x)", false],
  ];
  let bad = 0;
  for (const [name, src, want] of CASES) {
    const hit = strip(src).match(BANNED) != null;
    if (hit !== want) {
      console.error(`  FAIL  ${name} — wanted ${want ? "caught" : "passed"}, got the other`);
      bad++;
    }
  }
  if (bad) {
    console.error(`DLGKIT SELFTEST FAILED (${bad})`);
    process.exit(1);
  }
  console.log(`DLGKIT SELFTEST OK  (${CASES.length} cases)`);
  process.exit(0);
}

const fails = [];
let files = 0;
let dialogues = 0;

for (const file of walk(APP)) {
  const short = relative(APP, file).replace(/\\/g, "/").replace("dev/niccc2007/filet/", "");
  if (ALLOWED.includes(short)) continue;
  files++;
  const src = strip(readFileSync(file, "utf8"));
  dialogues += (src.match(/(?<![A-Za-z])Dlg\s*\(/g) || []).length;
  for (const m of src.matchAll(BANNED)) {
    const line = src.slice(0, m.index).split("\n").length;
    fails.push(`${short}:${line} — AlertDialog, not the app's Dlg`);
  }
}

if (fails.length) {
  console.error("DLGKIT FAILED:");
  for (const f of fails) console.error("  - " + f);
  console.error("\nUse Dlg / DlgHeader / DlgBody / DlgFooter. See ui/dialogs/DlgKit.kt.");
  process.exit(1);
}
console.log(
  `DLGKIT OK  (${files} files, ${dialogues} dialogue(s), all on the app's own kit)`,
);
