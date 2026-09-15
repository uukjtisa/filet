#!/usr/bin/env node
/**
 * The compress window cannot offer a control the format will not honour.
 *
 * Rule R1, no dead switches, applied to the one screen where breaking it is invisible. It
 * asked for strength, a password and split sizes *tailored per format*, and the tailoring is
 * not cosmetic: the formats that take a password are not the ones that take a strength, and
 * none of them share a scale.
 *
 * The failure this prevents has a specific shape. A dialog that decides for itself which
 * controls to draw creates two answers to "can a 7z take a password" - the dialog's and the
 * writer's - and the one that loses is the writer, several hundred milliseconds later, after
 * somebody has typed a password and pressed Compress. Worse, if the writer did NOT refuse, the
 * result is a perfectly good archive with no protection on it, which looks identical to a
 * protected one until the day somebody needs it to be protected.
 *
 * So: every control in the options section is rendered inside a check on the capability that
 * governs it, and the capability table is the only source of those answers.
 *
 *   node tools/check-archiveui.mjs              check
 *   node tools/check-archiveui.mjs --selftest   prove it fails on each way this breaks
 *
 * Prints "ARCHIVEUI OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";

const UI = "app/src/main/java/dev/niccc2007/filet/browser/ArchiveOptionsUi.kt";
const DIALOG = "app/src/main/java/dev/niccc2007/filet/browser/Dialogs.kt";

/**
 * Each control, the capability that governs it, and how that check is spelled.
 *
 * Matching on the guard text rather than parsing Kotlin is deliberate: this needs to fail
 * loudly when somebody moves a control out of its guard, and a brittle textual check that says
 * so is worth more here than a clever one that quietly stops applying.
 */
const CONTROLS = [
  {
    what: "the strength slider",
    control: /Slider\s*\(/,
    guard: /capability\.strength/,
    why: "a format that does not compress would get a slider that moves and changes nothing",
  },
  {
    what: "the password field",
    control: /PasswordVisualTransformation/,
    guard: /capability\.password\.supported/,
    why: "a password on a format with no encryption produces an unprotected archive that looks protected",
  },
  {
    what: "the encryption methods",
    control: /capability\.password\.methods/,
    guard: /capability\.password\.supported/,
    why: "offering AES on a format that cannot write it is a choice that fails after the fact",
  },
  {
    what: "the split sizes",
    control: /SPLIT_SIZES/,
    guard: /capability\.canSplit/,
    why: "a split size on a format that cannot be split is refused only once writing starts",
  },
];

/** Both refusals have to be SHOWN. Hiding a control says nothing; a sentence says why. */
const REFUSALS = [/capability\.password\.refusal/, /capability\.splitRefusal/];

/**
 * A format id used to decide what to draw.
 *
 * This is the actual failure mode - `if (format.id == "zip")` in the dialog - and it is how the
 * two answers get created in the first place.
 */
const FORMAT_SWITCH = /format\.id\s*==\s*"/;

/**
 * The estimated output size, and the two things that make it honest rather than a promise.
 *
 * A confident single number is worse than no number: compression depends on the bytes, and the
 * one case where the answer IS knowable - already-compressed media - is the case where somebody
 * is otherwise about to spend ten minutes finding it out the slow way.
 */
const ESTIMATE = [
  { what: "an estimated output size", from: /CompressEstimate\.of/ },
  { what: "the word estimate, so it is not read as a promise", from: /[Ee]stimate[d]?[^A-Za-z]/ },
  { what: "the already-compressed case called out", from: /mostlyIncompressible/ },
  { what: "an unmeasurable selection admitted rather than guessed", from: /\.partial/ },
];

export function check(text, dialog = "") {
  const problems = [];

  // Only when a dialog was supplied: the options-section checks stand on their own.
  if (dialog) {
    for (const e of ESTIMATE) {
      if (!e.from.test(dialog)) {
        problems.push(`C8: the compress window does not show ${e.what} (${e.from.source})`);
      }
    }
  }

  for (const c of CONTROLS) {
    if (!c.control.test(text)) {
      problems.push(`${c.what} is not in the options section at all — has it moved?`);
      continue;
    }
    if (!c.guard.test(text)) {
      problems.push(`${c.what} is drawn without checking ${c.guard.source}: ${c.why}`);
    }
  }

  for (const r of REFUSALS) {
    if (!r.test(text)) {
      problems.push(
        `${r.source} is never shown — a hidden control tells nobody why, and the reason is the useful part`,
      );
    }
  }

  if (FORMAT_SWITCH.test(text)) {
    problems.push(
      "the options section branches on a format id — that is a second answer to what a format " +
        "supports, and the capability table is supposed to be the only one",
    );
  }

  return problems;
}

if (process.argv.includes("--selftest")) {
  const good = existsSync(UI) ? readFileSync(UI, "utf8") : "";
  const dlg = existsSync(DIALOG) ? readFileSync(DIALOG, "utf8") : "";
  if (!good || !dlg) {
    console.error("SELFTEST FAIL: the real file is missing, so the positive control is meaningless");
    process.exit(1);
  }
  // The positive control first. Without it, a checker that reported everything would "pass"
  // every negative case below.
  const clean = check(good, dlg);
  if (clean.length) {
    console.error("SELFTEST FAIL: the real file is reported as broken: " + clean.join("; "));
    process.exit(1);
  }

  const cases = [
    ["a slider with no capability check", good.replace(/capability\.strength/g, "true"), /strength/],
    ["a password field with no check", good.replace(/capability\.password\.supported/g, "true"), /password/],
    ["split sizes with no check", good.replace(/capability\.canSplit/g, "true"), /split/i],
    ["a refusal that is never shown", good.replace(/capability\.password\.refusal/g, '""'), /refusal/],
    ["a branch on the format id", good + '\nval x = if (format.id == "zip") 1 else 2\n', /format id/],
    ["no estimated output size at all", good, /C8/, dlg.replace(/CompressEstimate\.of/g, "nothing")],
    ["an estimate silent about already-compressed input", good, /C8/, dlg.replace(/mostlyIncompressible/g, "false")],
    ["an estimate that never admits an unmeasured selection", good, /C8/, dlg.replace(/\.partial/g, ".no")],
  ];
  let failures = 0;
  for (const [name, text, expected, altDialog] of cases) {
    const problems = check(text, altDialog === undefined ? dlg : altDialog);
    if (!problems.some((p) => expected.test(p))) {
      console.error(`SELFTEST FAIL: "${name}" was not caught (got: ${problems.join("; ") || "nothing"})`);
      failures++;
    }
  }
  if (failures) process.exit(1);
  console.log(`ARCHIVEUI SELFTEST OK  (${cases.length} negative controls and 1 positive)`);
  process.exit(0);
}

if (!existsSync(UI)) {
  console.error(`FAIL: ${UI} is missing — the compress window's controls have nowhere to be checked`);
  process.exit(1);
}
if (!existsSync(DIALOG)) {
  console.error(`FAIL: ${DIALOG} is missing — the estimated output size has nowhere to be checked`);
  process.exit(1);
}
const problems = check(readFileSync(UI, "utf8"), readFileSync(DIALOG, "utf8"));
if (problems.length) {
  console.error(`ARCHIVEUI PROBLEMS: ${problems.length}`);
  for (const p of problems) console.error("  " + p);
  process.exit(1);
}
console.log(
  `ARCHIVEUI OK  (${CONTROLS.length} controls, each behind its own capability check, both refusals shown, ` +
    `and an output estimate that says it is one)`,
);
