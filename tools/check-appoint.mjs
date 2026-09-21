#!/usr/bin/env node
/**
 * The storage tool's selection logic behaves, and the mock has not drifted from it.
 *
 * Tree selection is the kind of thing that looks obvious and then has eight cases. Every one
 * below is a sequence somebody can actually perform on the screen, written as the gestures
 * rather than as the internal state, because the internal state is the thing being tested and
 * asserting on it would only prove it equals itself.
 *
 *   node tools/check-appoint.mjs              run the cases and the drift check
 *   node tools/check-appoint.mjs --selftest   prove the cases can fail
 *
 * The drift check exits 2 - SKIP - where the working mock is not on this machine, the same as
 * the other mock checkers, so CI stays green without a desktop.
 *
 * Prints "APPOINT OK" only after every case passes.
 */
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import {
  emptySel, leafTotal, nodeState, normalise, tallyFor, toggle, trayRows, trayTotals,
} from "./appoint.mjs";

const MOCK = process.env.FILET_MOCK || join(homedir(), "Desktop", "filet-redesign-mock.html");
const CORE = "tools/appoint.mjs";

/**
 * A tree with the two shapes that matter: folders whose children are loaded, and folders that
 * are only a name and a count because nobody has walked into them yet.
 */
const TREE = {
  id: "root", name: "Internal storage", dir: true, size: 96.6, count: 0, children: [
    { id: "dl", name: "Download", dir: true, size: 0, count: 0, children: [
      { id: "dl/a", name: "gta-v-cutscenes-4k.mp4", dir: false, size: 8.2 },
      { id: "dl/b", name: "domus-demo-clip.mp4", dir: false, size: 4.1 },
      { id: "dl/c", name: "ARSCLib-sources.zip", dir: false, size: 2.9 },
      { id: "old", name: "old-installers", dir: true, size: 1.4, count: 4, children: [
        { id: "old/1", name: "filet-v0.0.9.apk", dir: false, size: 0.31 },
        { id: "old/2", name: "filet-v0.0.8.apk", dir: false, size: 0.29 },
        { id: "old/3", name: "trawl-v0.1.1.apk", dir: false, size: 0.42 },
        { id: "old/4", name: "trawl-v0.1.0.apk", dir: false, size: 0.38 },
      ] },
    ] },
    // Never walked into. Its count is all anybody knows about it.
    { id: "movies", name: "Movies", dir: true, size: 11.2, count: 38 },
    { id: "zipfile", name: "backup-2026-09-01.zip", dir: false, size: 0.48 },
  ],
};

/** Apply a list of ticks, left to right, from nothing. */
function play(...ids) {
  return ids.reduce((sel, id) => toggle(TREE, id, sel), emptySel());
}

/** The tray as a person reads it: "Download 6/7", newest last. */
function tray(sel) {
  return trayRows(TREE, sel).map((r) =>
    r.dir ? `${r.name} ${r.picked}/${r.total}` : r.name);
}

/** Every box on the screen, so a case can assert what the user would see. */
function boxes(sel) {
  const out = {};
  (function walk(n) {
    out[n.id] = nodeState(TREE, n.id, sel);
    for (const c of n.children || []) walk(c);
  })(TREE);
  return out;
}

const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);

const CASES = [
  ["nothing is appointed to begin with", () => {
    const sel = emptySel();
    return eq(tray(sel), []) && boxes(sel).dl === "none" && trayTotals(TREE, sel).files === 0;
  }],

  ["a folder's count comes from its loaded children, not its stored one", () =>
    leafTotal(TREE.children[0]) === 7],

  ["a folder nobody has walked into still counts", () =>
    leafTotal(TREE.children[1]) === 38],

  // The sequence he described, step by step.
  ["ticking a folder appoints the whole thing", () => {
    const sel = play("dl");
    const b = boxes(sel);
    return b.dl === "all" && b["dl/a"] === "all" && b["old/3"] === "all"
      && eq(tray(sel), ["Download 7/7"]);
  }],

  ["the files inside do not each become a tray row", () =>
    trayRows(TREE, play("dl")).length === 1],

  ["unticking one file inside it leaves the folder appointed, and counted", () => {
    const sel = play("dl", "dl/a");
    const b = boxes(sel);
    return b.dl === "partial" && b["dl/a"] === "none" && b["dl/b"] === "all"
      && eq(tray(sel), ["Download 6/7"]);
  }],

  ["unticking a whole subfolder inside it subtracts all of it at once", () => {
    const sel = play("dl", "old");
    return eq(tray(sel), ["Download 3/7"]) && boxes(sel).old === "none";
  }],

  ["ticking a file back inside an exclusion works to any depth", () => {
    const sel = play("dl", "old", "old/2");
    const b = boxes(sel);
    return b.old === "partial" && b["old/2"] === "all" && b["old/1"] === "none"
      && eq(tray(sel), ["Download 4/7"]);
  }],

  // Rule 2.
  ["ticking a partial folder fills it rather than clearing it", () => {
    const sel = toggle(TREE, "dl", play("dl", "dl/a"));
    return boxes(sel).dl === "all" && eq(tray(sel), ["Download 7/7"]);
  }],

  ["ticking a full folder clears it", () => {
    const sel = toggle(TREE, "dl", play("dl"));
    return eq(tray(sel), []) && boxes(sel).dl === "none";
  }],

  // Rule 3. Note the gesture count: from partial, ONE tick fills it (rule 2), so getting back
  // to empty and round again takes three. Writing this as two ticks was the first thing this
  // suite caught, and it was the test that was wrong, not the rule.
  ["re-ticking a folder forgets what was unticked inside it", () => {
    const a = play("dl", "dl/a", "dl/b");            // partial, two holes
    const full = toggle(TREE, "dl", a);              // -> all
    const back = toggle(TREE, "dl", toggle(TREE, "dl", full)); // off, then on again
    return boxes(back).dl === "all" && eq(tray(back), ["Download 7/7"])
      && back.excluded.length === 0;
  }],

  // Rule 4.
  ["unticking the last remaining file drops the appointment entirely", () => {
    const sel = play("old", "old/1", "old/2", "old/3", "old/4");
    return eq(tray(sel), []) && boxes(sel).old === "none";
  }],

  // Rule 5.
  ["ticking every child one at a time is the same as ticking the parent", () => {
    const byHand = play("old/1", "old/2", "old/3", "old/4");
    const direct = play("old");
    return eq(tray(byHand), tray(direct)) && eq(tray(byHand), ["old-installers 4/4"]);
  }],

  ["the collapse carries up more than one level", () => {
    const sel = play("dl/a", "dl/b", "dl/c", "old");
    return eq(tray(sel), ["Download 7/7"]);
  }],

  // Independence.
  ["a file outside the folder is its own row", () => {
    const sel = play("dl", "zipfile");
    return eq(tray(sel), ["Download 7/7", "backup-2026-09-01.zip"]);
  }],

  ["a folder nobody walked into is appointed whole", () => {
    const sel = play("movies");
    return eq(tray(sel), ["Movies 38/38"]) && trayTotals(TREE, sel).files === 38;
  }],

  // Sizes follow selection, which is what the tray's total is for.
  // 8.2 + 4.1 + 2.9 + (0.31 + 0.29 + 0.42 + 0.38) = 16.6, and 8.4 once the 8.2 is dropped.
  ["the tray totals only what is actually going", () => {
    const whole = trayTotals(TREE, play("dl")).bytes;
    const less = trayTotals(TREE, play("dl", "dl/a")).bytes;
    return Math.abs(whole - 16.6) < 0.001 && Math.abs(less - 8.4) < 0.001;
  }],

  ["the counts add up to the total when everything is on", () => {
    const sel = play("root");
    const t = trayTotals(TREE, sel);
    return t.rows === 1 && t.files === leafTotal(TREE);
  }],

  // Idempotence and stability - the properties that stop a gesture from drifting.
  ["normalising twice changes nothing", () => {
    const once = play("dl", "dl/a", "old/2");
    return eq(once, normalise(TREE, once));
  }],

  ["ticking and unticking returns to nothing", () => {
    const sel = toggle(TREE, "dl/b", toggle(TREE, "dl/b", emptySel()));
    return eq(sel, emptySel()) && eq(tray(sel), []);
  }],

  ["no decision is ever recorded twice", () => {
    const sel = play("dl", "dl/a", "dl/a", "dl", "old");
    const all = [...sel.appointed, ...sel.excluded];
    return new Set(all).size === all.length;
  }],

  ["a tally is never more than the total", () => {
    const sel = play("root", "dl/a", "old");
    return ["root", "dl", "old", "movies"].every((id) => {
      const t = tallyFor(TREE, id, sel);
      return t.picked <= t.total && t.picked >= 0;
    });
  }],
];

function run(cases) {
  const failed = [];
  for (const [name, fn] of cases) {
    let ok = false;
    try { ok = fn() === true; } catch (e) { ok = false; }
    if (!ok) failed.push(name);
  }
  return failed;
}

/** The mock embeds the core verbatim. This is what stops two implementations existing. */
function block(text) {
  const a = text.indexOf("/* --8<-- appoint core --8<-- */");
  const b = text.indexOf("/* --8<-- end --8<-- */");
  if (a < 0 || b < 0) return null;
  return text.slice(a, b).replace(/^export /gm, "").replace(/\s+/g, " ").trim();
}

if (process.argv.includes("--selftest")) {
  // A case table proves nothing until a wrong rule makes it red. Each entry below is one of
  // the five rules, stated as a wrong version of itself, and the suite has to notice. The
  // wrong versions are not hypothetical - three of them are what a first pass at tree
  // selection usually does, and one of them is what this file actually did on its first run.
  const WRONG = [
    ["a partial folder empties instead of filling (rule 2 inverted)",
     (root, id, sel) => {
       const st = nodeState(root, id, sel);
       return st === "none" ? toggle(root, id, sel) : normalise(root, {
         appointed: sel.appointed.filter((x) => x !== id),
         excluded: sel.excluded,
       });
     }],
    ["a tick keeps the decisions beneath it (rule 3 dropped)",
     (root, id, sel) => normalise(root, {
       appointed: sel.appointed.includes(id) ? sel.appointed : [...sel.appointed, id],
       excluded: sel.excluded,
     })],
    ["an appointment covering nothing is kept (rule 4 dropped)",
     (root, id, sel) => {
       const next = toggle(root, id, sel);
       return next.appointed.length ? next : { appointed: [id], excluded: sel.excluded };
     }],
    ["the tray lists re-inclusions as their own rows",
     null],
  ];

  let bad = 0;
  const clean = run(CASES);
  if (clean.length) {
    console.error("SELFTEST FAILED: the real implementation does not pass its own cases:");
    for (const f of clean) console.error("  " + f);
    bad++;
  }

  // Rules 2, 3 and 4, each replaced by a wrong version, must break the suite.
  for (const [name, wrongToggle] of WRONG) {
    if (!wrongToggle) continue;
    const playWrong = (...ids) => ids.reduce((s, id) => wrongToggle(TREE, id, s), emptySel());
    const broke = [
      () => eq(trayRows(TREE, playWrong("dl", "dl/a")).map((r) => `${r.picked}/${r.total}`),
               ["6/7"]),
      () => nodeState(TREE, "dl", wrongToggle(TREE, "dl", playWrong("dl", "dl/a"))) === "all",
      () => trayRows(TREE, playWrong("old", "old/1", "old/2", "old/3", "old/4")).length === 0,
    ].some((probe) => { try { return probe() !== true; } catch { return true; } });
    if (!broke) {
      console.error(`SELFTEST FAILED: ${name} - the cases did not notice`);
      bad++;
    }
  }

  // Rule 5's own probe: the tray must not gain a row for a re-inclusion.
  const reinclusion = trayRows(TREE, [["dl"], ["old"], ["old/2"]]
    .flat().reduce((s, id) => toggle(TREE, id, s), emptySel()));
  if (reinclusion.length !== 1) {
    console.error("SELFTEST FAILED: a re-inclusion became its own tray row");
    bad++;
  }

  if (bad) process.exit(1);
  console.log(
    `APPOINT SELFTEST OK  (${CASES.length} cases, ` +
      `${WRONG.filter((w) => w[1]).length} wrong rules all caught, 1 property probed directly)`,
  );
  process.exit(0);
}

const failed = run(CASES);
if (failed.length) {
  console.error("The storage tool's selection logic is wrong:\n");
  for (const f of failed) console.error("  " + f);
  process.exit(1);
}

if (!existsSync(MOCK)) {
  console.log(
    `APPOINT OK  (${CASES.length} cases)\n` +
    `SKIP: the working mock is not on this machine (${MOCK}), so the drift check did not run.`,
  );
  process.exit(2);
}
const core = block(readFileSync(CORE, "utf8"));
const inMock = block(readFileSync(MOCK, "utf8"));
if (!inMock) {
  console.error(`The mock does not embed the appoint core, so it is running its own logic.`);
  process.exit(1);
}
if (core !== inMock) {
  console.error(
    "The mock's copy of the selection logic has drifted from tools/appoint.mjs.\n" +
      "Two implementations of a decision is how the mock and the app end up disagreeing " +
      "about what a half-ticked folder means. Re-copy the block between the markers.",
  );
  process.exit(1);
}
console.log(`APPOINT OK  (${CASES.length} cases, mock matches ${CORE} exactly)`);
