#!/usr/bin/env node
/**
 * The save prompt for a file edited inside an archive.
 *
 * The design calls for the prompt itself - update the archive, or save the file somewhere else - and
 * two properties of it are worth enforcing rather than eyeballing, because both fail silently.
 *
 * **"Save somewhere else" must be unconditional.** It is the answer to "I did not mean to
 * change the archive" and the only answer for a format Filet cannot write. Put it behind the
 * same condition as the update button and the read-only case becomes a dialog whose only
 * button says Cancel.
 *
 * **The update button must not exist when the archive refuses.** An encrypted zip cannot have
 * one entry rewritten without dropping the protection on the rest. A disabled button invites a
 * tap and explains nothing; an enabled one that fails afterwards is worse.
 *
 *   node tools/check-editui.mjs              check
 *   node tools/check-editui.mjs --selftest   prove it fails on each way this breaks
 *
 * Prints "EDITUI OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";

const SHEET = "app/src/main/java/dev/niccc2007/filet/browser/ArchiveSaveSheet.kt";
const VM = "app/src/main/java/dev/niccc2007/filet/browser/BrowserViewModel.kt";

/** The sentence in front of Save, and where its words come from. */
const COST = [
  { gate: "E3", what: "the cost of updating", from: /\bcost\b/, file: "sheet" },
  { gate: "E3", what: "a cost from the tested describer", from: /EditCosts\.describe/, file: "vm" },
  { gate: "E6", what: "the refusal, when the archive cannot hold the edit", from: /\brefusal\b/, file: "sheet" },
  { gate: "E6", what: "a refusal asked for before anything is written", from: /ArchiveEdits\.refusalFor/, file: "vm" },
];

/** Both offers, and the handler each has to reach. */
const OFFERS = [
  { gate: "E5", what: "save somewhere else", from: /onElsewhere/, vm: /saveArchiveMemberElsewhere/ },
  { gate: "E3", what: "update the archive", from: /onUpdate/, vm: /confirmArchiveSave/ },
];

/**
 * How far back from a call site to look for the `if (refusal ...)` that guards it.
 *
 * Proximity rather than block parsing, on purpose: a comment, a modifier or a reordered
 * argument all move the guard around without changing what it guards, and a structural matcher
 * that quietly stops applying is worse than a blunt one that says why it fired.
 */
const GUARD_WINDOW = 320;

/** Is there an open `if (refusal ...) {` immediately before this call site? */
function guardedByRefusal(text, call) {
  const at = text.indexOf(call);
  if (at < 0) return false;
  const before = text.slice(Math.max(0, at - GUARD_WINDOW), at);
  return /if\s*\(\s*refusal[^)]*\)\s*\{[^{}]*$/s.test(before);
}

export function check(sheet, vm) {
  const problems = [];

  for (const c of COST) {
    const text = c.file === "sheet" ? sheet : vm;
    if (!c.from.test(text)) {
      problems.push(`${c.gate}: the save prompt never shows ${c.what} (${c.from.source})`);
    }
  }

  for (const o of OFFERS) {
    if (!o.from.test(sheet)) {
      problems.push(`${o.gate}: the prompt does not offer to ${o.what} (${o.from.source})`);
    }
    if (!o.vm.test(vm)) {
      problems.push(`${o.gate}: nothing carries out "${o.what}" (${o.vm.source})`);
    }
  }

  if (guardedByRefusal(sheet, "onClick = onElsewhere")) {
    problems.push(
      'E5: "save somewhere else" is behind a condition — it must be offered for every archive, ' +
        "including the ones Filet cannot write, because it is the only answer for those",
    );
  }

  if (!guardedByRefusal(sheet, "onClick = onUpdate")) {
    problems.push(
      "E6: the update button is offered unconditionally — an encrypted archive cannot have one " +
        "entry rewritten without dropping the protection on the rest, and finding that out " +
        "after the tap is the worst outcome in this feature",
    );
  }

  // The refusal has to come from the tested decision, not from a second opinion in the UI.
  if (/usesEncryption|generalPurposeBit/.test(sheet) || /usesEncryption|generalPurposeBit/.test(vm)) {
    problems.push(
      "E6: encryption is being detected outside EditCosts — that is a second answer to whether " +
        "an archive is encrypted, and the one that loses is the writer",
    );
  }

  return problems;
}

if (process.argv.includes("--selftest")) {
  const sheet = existsSync(SHEET) ? readFileSync(SHEET, "utf8") : "";
  const vm = existsSync(VM) ? readFileSync(VM, "utf8") : "";
  if (!sheet || !vm) {
    console.error("SELFTEST FAIL: a real file is missing, so the positive control is meaningless");
    process.exit(1);
  }
  // The positive control first. Without it, a checker that reported everything would "pass"
  // every negative case below.
  const clean = check(sheet, vm);
  if (clean.length) {
    console.error("SELFTEST FAIL: the real files are reported as broken: " + clean.join("; "));
    process.exit(1);
  }

  // Wrap the unconditional offer in the guard the other one has. Matched by looking ahead for
  // the callback rather than by the widget's name, so a change of widget does not silently
  // turn this negative control into a no-op - which is exactly what happened once.
  const gated = sheet.replace(
    /(Choice\()(?=[\s\S]{0,320}?onClick = onElsewhere)/,
    "if (refusal == null) { $1",
  );
  if (gated === sheet) {
    console.error("SELFTEST FAIL: the gated mutation matched nothing, so it proves nothing");
    process.exit(1);
  }
  // Neuter the guard the update button has, leaving a block that always runs.
  const ungated = sheet.replace(/if \(refusal == null\) \{/, "run {");

  const cases = [
    ["no cost sentence at all", sheet.replace(/\bcost\b/g, "blank"), vm, /E3/],
    ["a cost invented in the UI", sheet, vm.replace(/EditCosts\.describe/g, "guess"), /E3/],
    ["no refusal shown", sheet.replace(/\brefusal\b/g, "none"), vm, /E6/],
    ["the refusal never asked for", sheet, vm.replace(/ArchiveEdits\.refusalFor/g, "nothing"), /E6/],
    ["save-somewhere-else removed", sheet.replace(/onElsewhere/g, "onNothing"), vm, /E5/],
    ["save-somewhere-else put behind the refusal", gated, vm, /behind a condition/],
    ["the update button offered even when refused", ungated, vm, /unconditionally/],
    [
      "encryption re-detected in the UI",
      sheet + "\nval e = it.generalPurposeBit.usesEncryption()\n",
      vm,
      /second answer/,
    ],
  ];
  let failures = 0;
  for (const [name, s, v, expected] of cases) {
    const problems = check(s, v);
    if (!problems.some((p) => expected.test(p))) {
      console.error(`SELFTEST FAIL: "${name}" was not caught (got: ${problems.join("; ") || "nothing"})`);
      failures++;
    }
  }
  if (failures) process.exit(1);
  console.log(`EDITUI SELFTEST OK  (${cases.length} negative controls and 1 positive)`);
  process.exit(0);
}

for (const f of [SHEET, VM]) {
  if (!existsSync(f)) {
    console.error(`FAIL: ${f} is missing — the save prompt has nowhere to be checked`);
    process.exit(1);
  }
}
const problems = check(readFileSync(SHEET, "utf8"), readFileSync(VM, "utf8"));
if (problems.length) {
  console.error(`EDITUI PROBLEMS: ${problems.length}`);
  for (const p of problems) console.error("  " + p);
  process.exit(1);
}
console.log(
  "EDITUI OK  (both offers present, save-elsewhere unconditional, update withheld when the " +
    "archive refuses, and every reason read from the tested decision)",
);
