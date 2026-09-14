#!/usr/bin/env node
/**
 * If the manifest offers Filet as a file picker, something has to answer.
 *
 * This checker exists because of a bug that shipped in every release: `AndroidManifest.xml`
 * declared an `ACTION_GET_CONTENT` filter, so Filet appeared in every "choose a file" chooser
 * on the device, and nothing in the app ever called `setResult`. Somebody picked Filet,
 * browsed, tapped a file, and the app that asked got nothing back.
 *
 * That is rule R1 - no dead switches - at the one place R1 is invisible: the switch is in
 * ANOTHER app's chooser, so the failure looks like the other app being broken. Nobody reports
 * it against the file manager.
 *
 * Underneath it was a second fault that would have defeated a naive fix. The filter sat on an
 * activity declared `launchMode="singleTask"`, and a singleTask activity does not run in the
 * caller's task: `setResult` is discarded and the caller gets `RESULT_CANCELED` immediately.
 * Adding result code to that activity would have produced a picker that still returned nothing.
 * So the launch mode is checked too, and it is the assertion most likely to save somebody.
 *
 *   node tools/check-picker.mjs              check
 *   node tools/check-picker.mjs --selftest   prove it fails on each way this breaks
 *
 * Prints "PICKER OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";

const MANIFEST = "app/src/main/AndroidManifest.xml";
const SRC = "app/src/main/java/dev/niccc2007/filet";

/** The actions an ordinary activity can answer with a result. */
const PICKER_ACTIONS = ["android.intent.action.GET_CONTENT", "android.intent.action.PICK"];

/**
 * Actions that CANNOT be answered by an activity, whatever the manifest says.
 *
 * These are served by a `DocumentsProvider`. An intent filter for one puts Filet in a list it
 * can never answer, which is the same bug this checker was written for wearing a different
 * name - so declaring one is a failure until a provider exists to back it.
 */
const PROVIDER_ONLY = [
  "android.intent.action.OPEN_DOCUMENT",
  "android.intent.action.CREATE_DOCUMENT",
  "android.intent.action.OPEN_DOCUMENT_TREE",
];

/** Launch modes that silently discard setResult. */
const BAD_LAUNCH_MODES = ["singleTask", "singleInstance"];

/**
 * Split a manifest into its `<activity …> … </activity>` blocks.
 *
 * Text rather than XML parsing, deliberately and in keeping with the other checkers here: this
 * has to fail loudly when the shape changes, and a brittle check that says so is worth more
 * than a clever one that quietly stops applying.
 */
export function activities(manifest) {
  const out = [];
  let at = 0;
  for (;;) {
    const start = manifest.indexOf("<activity", at);
    if (start < 0) break;
    const openEnd = manifest.indexOf(">", start);
    if (openEnd < 0) break;
    // A self-closing <activity … /> ends at its own tag; anything else runs to </activity>.
    //
    // Scanned rather than matched with one regex, and that is not fussiness. The obvious
    // pattern stops at the first self-closing tag INSIDE the block — which is always an
    // `<action … />` — so every activity was read as having exactly one action and a second
    // intent-filter was invisible. The checker passed its own selftest while seeing half the
    // manifest, which is the failure mode a checker is supposed to prevent rather than have.
    let end;
    if (manifest[openEnd - 1] === "/") {
      end = openEnd + 1;
    } else {
      const close = manifest.indexOf("</activity>", openEnd);
      if (close < 0) break;
      end = close + "</activity>".length;
    }
    const block = manifest.slice(start, end);
    const head = manifest.slice(start, openEnd + 1);
    const name = (head.match(/android:name\s*=\s*"([^"]+)"/) || [])[1] || "(unnamed)";
    const launchMode = (head.match(/android:launchMode\s*=\s*"([^"]+)"/) || [])[1] || "standard";
    const actions = [...block.matchAll(/<action\s+android:name\s*=\s*"([^"]+)"/g)].map((a) => a[1]);
    out.push({ name, launchMode, actions, block });
    at = end;
  }
  return out;
}

/** Where an activity's source lives, from its manifest name. */
export function sourceFor(name) {
  const relative = name.replace(/^\./, "").replace(/\./g, "/");
  return `${SRC}/${relative}.kt`;
}

export function check(manifest, readSource) {
  const problems = [];
  const acts = activities(manifest);
  if (acts.length === 0) {
    problems.push("no activities found in the manifest — has its shape changed?");
    return problems;
  }

  let pickers = 0;
  for (const act of acts) {
    const picks = act.actions.filter((a) => PICKER_ACTIONS.includes(a));
    const providerOnly = act.actions.filter((a) => PROVIDER_ONLY.includes(a));

    for (const a of providerOnly) {
      problems.push(
        `${act.name} declares ${a}, which no activity can answer — it is served by a ` +
          "DocumentsProvider, so this puts Filet in a picker it cannot return anything to",
      );
    }

    if (picks.length === 0) continue;
    pickers++;

    if (BAD_LAUNCH_MODES.includes(act.launchMode)) {
      problems.push(
        `${act.name} answers ${picks.join(", ")} but is launchMode="${act.launchMode}": it does ` +
          "not run in the caller's task, so setResult is discarded and the caller is handed " +
          "RESULT_CANCELED the moment it starts",
      );
    }

    const src = readSource(sourceFor(act.name));
    if (src === null) {
      problems.push(`${act.name} answers ${picks.join(", ")} but its source was not found`);
      continue;
    }
    if (!/setResult\s*\(/.test(src)) {
      problems.push(
        `${act.name} answers ${picks.join(", ")} and never calls setResult: it is offered in ` +
          "every chooser on the device and returns nothing, which reads as the ASKING app " +
          "being broken",
      );
    }
    if (!/RESULT_OK/.test(src)) {
      problems.push(`${act.name} never returns RESULT_OK, so a pick can only ever be a cancel`);
    }
    if (!/RESULT_CANCELED/.test(src)) {
      problems.push(
        `${act.name} never sets RESULT_CANCELED: a picker that is backed out of or killed must ` +
          "look like a cancel rather than leaving the caller waiting",
      );
    }
    if (!/FLAG_GRANT_READ_URI_PERMISSION/.test(src)) {
      problems.push(
        `${act.name} returns a URI without FLAG_GRANT_READ_URI_PERMISSION, so the caller cannot ` +
          "open what it was given and fails at openInputStream rather than here",
      );
    }
  }

  if (pickers === 0) {
    problems.push(
      "nothing answers GET_CONTENT or PICK — a file manager that cannot answer \"choose a file\" " +
        "is missing the one thing every other app asks it for",
    );
  }

  return problems;
}

if (process.argv.includes("--selftest")) {
  if (!existsSync(MANIFEST)) {
    console.error("SELFTEST FAIL: the real manifest is missing, so the positive control is meaningless");
    process.exit(1);
  }
  const real = readFileSync(MANIFEST, "utf8");
  const read = (p) => (existsSync(p) ? readFileSync(p, "utf8") : null);

  // The positive control first. Without it, a checker that reported everything would "pass"
  // every negative case below.
  const clean = check(real, read);
  if (clean.length) {
    console.error("SELFTEST FAIL: the real manifest is reported as broken: " + clean.join("; "));
    process.exit(1);
  }

  const pickerSrc = sourceFor(".pick.PickActivity");
  const withoutSetResult = (p) => (p === pickerSrc ? "class PickActivity { fun nothing() {} }" : read(p));
  const withoutGrant = (p) =>
    p === pickerSrc ? read(p).replace(/FLAG_GRANT_READ_URI_PERMISSION/g, "NOTHING") : read(p);
  const withoutCancel = (p) =>
    p === pickerSrc ? read(p).replace(/RESULT_CANCELED/g, "RESULT_OK") : read(p);

  const cases = [
    [
      "the picker put back on a singleTask activity",
      real.replace(/(<activity\s+android:name=\"\.pick\.PickActivity\")/, '$1 android:launchMode="singleTask"'),
      read,
      /singleTask/,
    ],
    ["a picker that never returns a result", real, withoutSetResult, /never calls setResult/],
    ["a result with no read grant", real, withoutGrant, /FLAG_GRANT_READ_URI_PERMISSION/],
    ["a picker that can never cancel", real, withoutCancel, /RESULT_CANCELED/],
    [
      "the filter removed entirely",
      real.replace(/android\.intent\.action\.GET_CONTENT/g, "android.intent.action.NOTHING")
        .replace(/android\.intent\.action\.PICK/g, "android.intent.action.NOTHING"),
      read,
      /nothing answers GET_CONTENT/,
    ],
    [
      "a filter only a DocumentsProvider can answer",
      real.replace(/android\.intent\.action\.PICK/, "android.intent.action.OPEN_DOCUMENT"),
      read,
      /no activity can answer/,
    ],
  ];

  let failures = 0;
  for (const [name, manifest, reader, expected] of cases) {
    const problems = check(manifest, reader);
    if (!problems.some((p) => expected.test(p))) {
      console.error(`SELFTEST FAIL: "${name}" was not caught (got: ${problems.join("; ") || "nothing"})`);
      failures++;
    }
  }
  if (failures) process.exit(1);
  console.log(`PICKER SELFTEST OK  (${cases.length} negative controls and 1 positive)`);
  process.exit(0);
}

if (!existsSync(MANIFEST)) {
  console.error(`FAIL: ${MANIFEST} is missing`);
  process.exit(1);
}
const problems = check(readFileSync(MANIFEST, "utf8"), (p) =>
  existsSync(p) ? readFileSync(p, "utf8") : null,
);
if (problems.length) {
  console.error(`PICKER PROBLEMS: ${problems.length}`);
  for (const p of problems) console.error("  " + p);
  process.exit(1);
}
const acts = activities(readFileSync(MANIFEST, "utf8"));
const answered = acts.filter((a) => a.actions.some((x) => PICKER_ACTIONS.includes(x)));
const covered = [
  ...new Set(answered.flatMap((a) => a.actions.filter((x) => PICKER_ACTIONS.includes(x)))),
].map((a) => a.replace("android.intent.action.", ""));
console.log(
  `PICKER OK  (${acts.length} activities scanned, ${covered.length} picker action(s) answered: ` +
    `${covered.join(", ")} — in a launch mode that can return a result, with a read grant on it)`,
);
