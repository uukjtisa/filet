#!/usr/bin/env node
/**
 * The redesign still offers everything the app offers.
 *
 * The failure this exists for has now happened three times in one round, in the same shape
 * each time: a surface was redrawn from a memory of what it does rather than from what it
 * does, and the redraw quietly dropped an action. The icon row lost Move and gained a Cut and
 * a Paste the app has never had. The list repeated the row. The header lost the path.
 *
 * None of those are design disagreements - they are a redesign removing things nobody decided
 * to remove. A checker can catch exactly that class, and cannot catch anything about whether
 * the design is good, which is the right division of labour.
 *
 * So: pull every user-visible label out of the Kotlin, pull the labels out of the mock, and
 * report what the app has that the mock does not. Anything deliberately dropped is listed in
 * DROPPED with the reason, so a removal is a decision on the record rather than an omission.
 *
 *   node tools/check-dialogues.mjs              check
 *   node tools/check-dialogues.mjs --selftest   prove it catches a dropped action
 *
 * The mock lives on the desktop for iteration, so this exits 2 where it is absent.
 *
 * Prints "DIALOGUES OK" only after every assertion passes.
 */
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const MOCK = process.env.FILET_MOCK || join(homedir(), "Desktop", "filet-redesign-mock.html");
const ACTIONS = "app/src/main/java/dev/niccc2007/filet/browser/SelectionActions.kt";
const CTX = "app/src/main/java/dev/niccc2007/filet/browser/ContextMenu.kt";

/**
 * Labels the redesign deliberately does not carry, each with the reason.
 *
 * A removal belongs here, in front of somebody, rather than in a diff nobody reads. If a
 * label is missing and is not in this list, it was dropped by accident.
 */
const DROPPED = {
  Select: "asked for explicitly: a check row that starts a multi-selection, judged not worth the space",
  Move: "relabelled Cut with a scissors glyph; the action id stays move, so nothing is removed",
};

/** Things the redesign adds that the app has no action for yet. Not failures; new work. */
const ADDED = ["Copy path", "Edit metadata"];

/** Pull every `SelectionAction("id", "Label"` out of the Kotlin. */
export function appLabels(actionsSrc) {
  const out = [];
  for (const m of actionsSrc.matchAll(/SelectionAction\(\s*"([a-z_]+)"\s*,\s*"([^"]+)"/g)) {
    out.push({ id: m[1], label: m[2].replace(/\\u2026/g, "…") });
  }
  return out;
}

/** The five that go in the icon row, read from the source rather than assumed. */
export function quickIds(ctxSrc) {
  const m = /val QUICK_IDS = listOf\(([^)]*)\)/.exec(ctxSrc);
  if (!m) return [];
  return [...m[1].matchAll(/"([a-z_]+)"/g)].map((x) => x[1]);
}

/** Text the mock renders, with markup and escapes flattened so a label can be matched. */
export function mockText(html) {
  return html
    .replace(/<[^>]+>/g, " ")
    .replace(/\\u2026/g, "…")
    .replace(/\\u00b7/g, "·")
    .replace(/&amp;/g, "&");
}

export function problems({ actionsSrc, ctxSrc, html }) {
  const found = [];
  const labels = appLabels(actionsSrc);
  if (labels.length < 10) {
    found.push(`only ${labels.length} actions read out of ${ACTIONS}; the parser has gone stale`);
    return found;
  }

  const quick = quickIds(ctxSrc);
  if (quick.length === 0) found.push(`QUICK_IDS not found in ${CTX}`);

  const text = mockText(html);

  for (const { id, label } of labels) {
    if (DROPPED[label]) continue;
    if (!text.includes(label)) {
      found.push(
        `the app offers "${label}" (id ${id}) and the mock never shows it` +
          (quick.includes(id) ? " - and it is one of the five in the icon row" : ""),
      );
    }
  }

  // The row is built from QUICK_IDS, so every one of them has to be in it.
  const bar = /function ctxbarHTML[\s\S]{0,900}?\n}/.exec(html)?.[0] ?? "";
  const quickBlock = /const QUICK = \[[\s\S]*?\];/.exec(html)?.[0] ?? "";
  for (const id of quick) {
    if (!quickBlock.includes(`"${id}"`)) {
      found.push(`the icon row is missing ${id}, which QUICK_IDS says belongs in it`);
    }
  }
  if (bar === "") found.push("the menu has no icon row at all");

  // A label in the row must not also be a row in the list - splitForContextMenu returns the
  // quick ones AND the rest, and showing both is how Rename appeared twice.
  const ctxBlock = /context: \(\) =>[\s\S]*?`,\n/.exec(html)?.[0] ?? "";
  for (const id of quick) {
    const label = labels.find((l) => l.id === id)?.label;
    if (!label || DROPPED[label]) continue;
    const inList = new RegExp(`class="t">${label}<`).test(ctxBlock);
    if (inList) found.push(`"${label}" is in the icon row and repeated as a list entry`);
  }

  // The header names the file AND says where it is.
  if (!/class="ctxhead"/.test(html)) found.push("the context menu header is not the app's shape");
  if (!/class="pth2"/.test(html)) {
    found.push("the context menu header does not show the path, which is the half that says which file");
  }

  return found;
}

if (process.argv.includes("--selftest")) {
  const actionsSrc = [
    'SelectionAction("copy", "Copy", icons.copy)',
    'SelectionAction("move", "Move", icons.cut)',
    'SelectionAction("send", "Send", icons.share)',
    'SelectionAction("compress", "Compress", icons.zip)',
    'SelectionAction("extracthere", "Extract here", icons.zip)',
    'SelectionAction("rename", "Rename", icons.edit)',
    'SelectionAction("openwith", "Open with", icons.file)',
    'SelectionAction("details", "Details", icons.info)',
    'SelectionAction("bookmark", "Bookmark", icons.star)',
    'SelectionAction("nearby", "Nearby", icons.wifi)',
    'SelectionAction("shortcut", "Shortcut", icons.pin)',
    'SelectionAction("delete", "Delete", icons.trash)',
  ].join("\n");
  const ctxSrc = 'val QUICK_IDS = listOf("copy", "move", "rename", "send", "delete")';
  const quickBlock = 'const QUICK = [["copy","Copy"],["move","Cut"],["rename","Rename"],["send","Send"],["delete","Delete"]];';
  const good =
    quickBlock +
    "\nfunction ctxbarHTML() {\n return QUICK;\n}\n" +
    'context: () => `<div class="ctxhead"><b>n</b><span class="pth2">/p</span></div>' +
    "Compress Extract here Open with Details Bookmark Nearby Shortcut`,\n";
  const CASES = [
    ["a faithful mock", good, false],
    ["Compress dropped", good.replace("Compress ", ""), true],
    ["Open with dropped", good.replace("Open with ", ""), true],
    ["Details dropped", good.replace("Details ", ""), true],
    ["Extract dropped", good.replace("Extract here ", ""), true],
    ["the icon row gone", good.replace("function ctxbarHTML", "x"), true],
    ["move missing from the row", good.replace('["move","Cut"],', ""), true],
    ["the path dropped from the header", good.replace('<span class="pth2">/p</span>', ""), true],
    ["a quick action repeated in the list", good.replace("Compress ", 'class="t">Rename<'), true],
  ];
  let bad = 0;
  for (const [name, html, expect] of CASES) {
    const got = problems({ actionsSrc, ctxSrc, html }).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} - expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  const neg = CASES.filter((c) => c[2]).length;
  console.log(`DIALOGUES SELFTEST OK  (${neg} negative controls, ${CASES.length - neg} positive)`);
  process.exit(0);
}

if (!existsSync(MOCK)) {
  console.log(`SKIP: the working mock is not on this machine (${MOCK}). Set FILET_MOCK to point at it.`);
  process.exit(2);
}
for (const f of [ACTIONS, CTX]) {
  if (!existsSync(f)) {
    console.error(`${f} is missing; this checker reads the app's own action list`);
    process.exit(1);
  }
}
const actionsSrc = readFileSync(ACTIONS, "utf8");
const ctxSrc = readFileSync(CTX, "utf8");
const html = readFileSync(MOCK, "utf8");

const found = problems({ actionsSrc, ctxSrc, html });
if (found.length) {
  console.error("The redesign drops something the app offers:\n");
  for (const p of found) console.error("  " + p);
  console.error(
    "\nIf one of these is deliberate, add it to DROPPED in this file with the reason.\n" +
      "A removal belongs on the record, not in a diff nobody reads.",
  );
  process.exit(1);
}
const n = appLabels(actionsSrc).length;
console.log(
  `DIALOGUES OK  (${n} actions in the app, every one shown or listed as dropped; ` +
    `${ADDED.length} added by the redesign)`,
);
