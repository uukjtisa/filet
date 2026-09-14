#!/usr/bin/env node
/**
 * The extraction preview must be a drawing of the plan, and nothing else.
 *
 * The bug Nic reported is specific: he expected an archive to extract as
 * `<archive name>/contents` and it scattered its contents into the folder instead. He only
 * found out afterwards, with the mess already made. The fix is a preview - and a preview is
 * only worth having if it is showing the value the extractor will actually walk.
 *
 * So the failure this guards against is not "the sheet looks wrong". It is the sheet computing
 * its own answer: reading entry names, re-deciding what gets wrapped, formatting its own idea
 * of the tree. That produces a preview that is honest about a plan nobody runs, which is worse
 * than no preview at all, because it is believed.
 *
 *   node tools/check-extractui.mjs              check
 *   node tools/check-extractui.mjs --selftest   prove it fails on each way this breaks
 *
 * Prints "EXTRACTUI OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";

const SHEET = "app/src/main/java/dev/niccc2007/filet/browser/ExtractSheet.kt";
const MENU = "app/src/main/java/dev/niccc2007/filet/browser/Menus.kt";
const ACTIONS = "app/src/main/java/dev/niccc2007/filet/browser/SelectionActions.kt";

/**
 * Everything the preview has to say before you commit, and the field it must say it from.
 *
 * Each is something that is otherwise discovered too late: after the overwrite, after the
 * volume fills, after the 40,000-entry extract has been running for a minute, or never at all
 * in the case of an entry that tried to write outside the folder.
 */
const SAYS = [
  { gate: "X6", what: "the tree it is about to write", from: /plan\.items/ },
  { gate: "X6", what: "which folders it invented", from: /\.added/ },
  { gate: "X6", what: "which redundant folders it lifted away", from: /plan\.strippedFolders/ },
  { gate: "X7", what: "what is already there", from: /plan\.collisions/ },
  { gate: "X7", what: "the three ways to resolve that", from: /CollisionChoice\.entries/ },
  { gate: "X8", what: "whether it fits", from: /plan\.fits/ },
  { gate: "X8", what: "how much room there is", from: /plan\.freeBytes/ },
  { gate: "X9", what: "how many entries were refused for pointing outside", from: /plan\.refused/ },
  { gate: "X10", what: "how many files", from: /plan\.entryCount/ },
  { gate: "X10", what: "how many bytes", from: /plan\.needsBytes/ },
  { gate: "X10", what: "how much of it will not shrink", from: /plan\.alreadyCompressedBytes/ },
];

/** His two buttons, and the callbacks that make them do something. */
const BUTTONS = [
  { gate: "X5", what: "put the contents in a folder / take them out of one", from: /onWrap/ },
  { gate: "X5", what: "keep one level of the lifted chain", from: /onStripLess/ },
];

/**
 * A second opinion about the archive, inside the sheet.
 *
 * This is the actual failure mode. Any of these means the sheet is deciding something for
 * itself instead of drawing what the planner decided, and the two answers will diverge.
 */
const SECOND_OPINIONS = [
  { pattern: /\bArchives\./, why: "the sheet is reading the archive itself rather than the plan" },
  { pattern: /ExtractPlanner/, why: "the sheet is re-planning rather than drawing the plan it was given" },
  { pattern: /\bentries\s*\.\s*(filter|map)\s*\{/, why: "the sheet is deriving its own tree from raw entries" },
];

/** The destinations. One of these alone is the bug the gate exists for. */
const TARGETS = [
  { label: "here", from: /"Extract here"/ },
  { label: "a folder you pick", from: /vm\.extractToPicked/ },
  { label: "the other pane", from: /vm\.extractToOtherPane/ },
];

/**
 * The same three in the SELECTION action list.
 *
 * Found by looking: the toolbar overflow had all three and the long-press context menu had
 * none, so on a phone - where long-press is how anybody reaches an archive - "Extract" did not
 * exist at all. `selectionActions` is the single list both the context menu and the selection
 * bar are painted from, which is why the rows belong there rather than in a third menu.
 */
const ACTION_TARGETS = [
  { label: "here", from: /"extracthere"/ },
  { label: "a folder you pick", from: /"extractto"/ },
  { label: "the other pane", from: /"extractother"/ },
];

export function check(sheet, menu, actions = "") {
  const problems = [];

  for (const s of SAYS) {
    if (!s.from.test(sheet)) {
      problems.push(`${s.gate}: the preview never says ${s.what} (${s.from.source} is not read)`);
    }
  }
  for (const b of BUTTONS) {
    if (!b.from.test(sheet)) {
      problems.push(`${b.gate}: the button for ${b.what} is missing (${b.from.source})`);
    }
  }
  for (const o of SECOND_OPINIONS) {
    if (o.pattern.test(sheet)) {
      problems.push(`X2: ${o.why} — ${o.pattern.source} appears in the sheet`);
    }
  }

  // Both are required: a preview with no way to say no is a progress dialog.
  if (!/onConfirm/.test(sheet) || !/onDismiss/.test(sheet)) {
    problems.push("X6: the preview must offer both confirm and cancel");
  }

  const found = TARGETS.filter((t) => t.from.test(menu)).map((t) => t.label);
  if (found.length < TARGETS.length) {
    const missing = TARGETS.filter((t) => !t.from.test(menu)).map((t) => t.label);
    problems.push(`X1: extraction can only go to ${found.join(", ") || "nowhere"} — missing: ${missing.join(", ")}`);
  }

  if (actions) {
    const missing = ACTION_TARGETS.filter((t) => !t.from.test(actions)).map((t) => t.label);
    if (missing.length) {
      problems.push(
        `X1: the long-press menu cannot extract to ${missing.join(", ")} — the rows live in ` +
          "selectionActions, which is what both the context menu and the selection bar are " +
          "painted from",
      );
    }
    // Absent, not blocked: "Extract" on a photo is not unavailable, it is meaningless.
    if (!/\barchive\b/.test(actions)) {
      problems.push(
        "X1: selectionActions does not know whether the selection is an archive, so the extract " +
          "rows are either always there or never",
      );
    }
  }

  // Every target has to open the preview. A "quick" path that skips it puts the bug back for
  // exactly the case somebody is in a hurry, which is when it bites.
  if (/ops\.extract\(/.test(menu)) {
    problems.push("X11: the menu calls the extractor directly, so that target skips the preview");
  }

  return problems;
}

if (process.argv.includes("--selftest")) {
  const sheet = existsSync(SHEET) ? readFileSync(SHEET, "utf8") : "";
  const menu = existsSync(MENU) ? readFileSync(MENU, "utf8") : "";
  const actions = existsSync(ACTIONS) ? readFileSync(ACTIONS, "utf8") : "";
  if (!sheet || !menu || !actions) {
    console.error("SELFTEST FAIL: a real file is missing, so the positive control is meaningless");
    process.exit(1);
  }
  const clean = check(sheet, menu, actions);
  if (clean.length) {
    console.error("SELFTEST FAIL: the real files are reported as broken: " + clean.join("; "));
    process.exit(1);
  }

  const cases = [
    ["a preview that never mentions collisions", sheet.replace(/plan\.collisions/g, "emptyList()"), menu, /X7/],
    ["a preview that never checks free space", sheet.replace(/plan\.fits/g, "true"), menu, /X8/],
    ["a preview that hides refused entries", sheet.replace(/plan\.refused/g, "emptyList()"), menu, /X9/],
    ["a preview that does not show the cost", sheet.replace(/plan\.entryCount/g, "0"), menu, /X10/],
    ["a preview with the wrap button gone", sheet.replace(/onWrap/g, "onNothing"), menu, /X5/],
    ["a sheet that reads the archive itself", sheet + "\nval x = Archives.canList(name)\n", menu, /second opinion|reading the archive/],
    ["a sheet that re-plans", sheet + "\nval p = ExtractPlanner.plan(a, b, c)\n", menu, /re-planning/],
    ["only one destination", sheet, menu.replace(/vm\.extractToPicked/g, "vm.extract"), /X1/],
    ["a target that skips the preview", sheet, menu + "\nvm.graph.ops.extract(a, b, c)\n", /X11/],
    [
      "the long-press menu losing a destination",
      sheet,
      menu,
      /long-press/,
      actions.replace(/"extractto"/g, '"somethingelse"'),
    ],
    [
      "extract rows shown for anything, not only archives",
      sheet,
      menu,
      /always there or never/,
      actions.replace(/\barchive\b/g, "thing"),
    ],
  ];
  let failures = 0;
  for (const [name, s, m, expected, altActions] of cases) {
    const problems = check(s, m, altActions === undefined ? actions : altActions);
    if (!problems.some((p) => expected.test(p))) {
      console.error(`SELFTEST FAIL: "${name}" was not caught (got: ${problems.join("; ") || "nothing"})`);
      failures++;
    }
  }
  if (failures) process.exit(1);
  console.log(`EXTRACTUI SELFTEST OK  (${cases.length} negative controls and 1 positive)`);
  process.exit(0);
}

for (const f of [SHEET, MENU, ACTIONS]) {
  if (!existsSync(f)) {
    console.error(`FAIL: ${f} is missing — the extraction preview has nowhere to be checked`);
    process.exit(1);
  }
}
const problems = check(readFileSync(SHEET, "utf8"), readFileSync(MENU, "utf8"), readFileSync(ACTIONS, "utf8"));
if (problems.length) {
  console.error(`EXTRACTUI PROBLEMS: ${problems.length}`);
  for (const p of problems) console.error("  " + p);
  process.exit(1);
}
console.log(
  `EXTRACTUI OK  (${TARGETS.length} destinations in both menus, ${BUTTONS.length} reversible decisions, ` +
    `${SAYS.length} facts stated before you commit, all read from the plan)`,
);
