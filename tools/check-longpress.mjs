#!/usr/bin/env node
/**
 * Every list of files answers a long press the same way.
 *
 * Bug identified: the expanded tracked-files tab drew its own rows and wired only a tap, so a
 * long press there did nothing. Every other list in the app opens a menu on a long press, and
 * the one screen that did not was the one showing files that had just turned up - the files
 * most likely to need renaming, moving or deleting straight away.
 *
 * A gesture that works on four screens and silently does nothing on the fifth is worse than
 * one that works nowhere, because the user has already learnt that it works.
 *
 * So this checks the wiring rather than the pixels: each surface that draws a file row must
 * pass a long press to something, that something must not be an empty lambda (PLAN.md R1, no
 * dead switches), and the two home surfaces must both reach the same menu.
 *
 *   node tools/check-longpress.mjs              check
 *   node tools/check-longpress.mjs --selftest   prove it catches a row that ignores the press
 *
 * Prints "LONG PRESS OK" only after every assertion passes.
 */
import { readFileSync } from "node:fs";

const APP = "app/src/main/java/dev/niccc2007/filet";

/** Every file that draws a tappable file row. `sheet` means it must open the shared menu. */
const SURFACES = [
  { file: `${APP}/home/HomeOverview.kt`, what: "the home feed", sheet: true },
  { file: `${APP}/home/FileHistoryScreen.kt`, what: "the expanded tracked-files tab", sheet: true },
  { file: `${APP}/browser/RowViews.kt`, what: "the browser rows", sheet: false },
  { file: `${APP}/browser/Places.kt`, what: "the places list", sheet: false },
];

/** An empty handler is the dead switch: the gesture is claimed and then dropped. */
const EMPTY = /onLongClick\s*=\s*\{\s*\}/;
const WIRED = /onLongClick\s*=\s*[^\s,)]/;

function problems(src, surface) {
  const found = [];
  if (!WIRED.test(src)) {
    found.push(`${surface.what} draws rows and never wires onLongClick`);
  }
  if (EMPTY.test(src)) {
    found.push(`${surface.what} wires onLongClick to an empty lambda, which is a dead switch`);
  }
  if (surface.sheet && !src.includes("HomeRowSheet(")) {
    found.push(`${surface.what} does not open HomeRowSheet, so its long press would show a different menu`);
  }
  return found;
}

if (process.argv.includes("--selftest")) {
  const ROW = { what: "a row", sheet: false };
  const HOME = { what: "a home row", sheet: true };
  const CASES = [
    ["a row that ignores the press", ".clickable(onClick = onClick)", ROW, true],
    ["a dead switch", ".combinedClickable(onClick = onClick, onLongClick = {})", ROW, true],
    ["a dead switch with a space in it", ".combinedClickable(onClick = onClick, onLongClick = { })", ROW, true],
    ["a home row with no menu behind it", ".combinedClickable(onClick = c, onLongClick = { menuFor = it })", HOME, true],
    // positive controls
    ["a wired row", ".combinedClickable(onClick = onClick, onLongClick = onLongClick)", ROW, false],
    ["a wired home row", ".combinedClickable(onClick = c, onLongClick = { menuFor = it })\nHomeRowSheet(node = n)", HOME, false],
  ];
  let bad = 0;
  for (const [name, src, surface, expect] of CASES) {
    const got = problems(src, surface).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} - expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  const neg = CASES.filter((c) => c[3]).length;
  console.log(`LONG PRESS SELFTEST OK  (${neg} negative controls, ${CASES.length - neg} positive)`);
  process.exit(0);
}

const found = [];
for (const surface of SURFACES) {
  let src;
  try {
    src = readFileSync(surface.file, "utf8");
  } catch {
    found.push(`${surface.file} is gone - if the screen moved, move this list with it`);
    continue;
  }
  found.push(...problems(src, surface));
}
if (found.length) {
  console.error("A list of files does not answer a long press:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
console.log(`LONG PRESS OK  (${SURFACES.length} file lists, each answering a long press)`);
