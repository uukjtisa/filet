#!/usr/bin/env node
/**
 * The two home feed headings have to be tellable apart.
 *
 * Bug identified: "New files" sat directly above "Recent". They are different questions - one
 * is files that turned up in a watched folder, the other is files the user opened - and
 * neither name said which, so the second read as a shorter way of saying the first.
 *
 * A heading that needs a subtitle to disambiguate has already failed, because a line of
 * explanation under a section header is not read. So this checks the headings themselves:
 * each must name its own cause, and neither may be one of the bare words that caused this.
 *
 *   node tools/check-homenames.mjs              check
 *   node tools/check-homenames.mjs --selftest   prove it catches the names that shipped
 *
 * Prints "HOME NAMES OK" only after every assertion passes.
 */
import { readFileSync } from "node:fs";

const SRC = "app/src/main/java/dev/niccc2007/filet/home/HomeSections.kt";

/** Bare labels that say nothing about why a file is in the list. */
const AMBIGUOUS = ["recent", "recents", "files", "new files", "new", "history", "latest"];

/** One of these has to appear, so a heading says what put the entry there. */
const ARRIVAL_WORDS = ["arriv", "new in", "turned up", "appeared", "landed", "added"];
const OPENED_WORDS = ["open", "viewed", "visited", "used"];

function labels(src) {
  const out = {};
  for (const m of src.matchAll(/const val (ARRIVED|OPENED)\s*=\s*"([^"]+)"/g)) out[m[1]] = m[2];
  return out;
}

function problems(src) {
  const found = [];
  const { ARRIVED, OPENED } = labels(src);
  if (!ARRIVED || !OPENED) return ["HomeSections must declare both ARRIVED and OPENED"];

  if (ARRIVED.trim().toLowerCase() === OPENED.trim().toLowerCase()) {
    found.push("both headings are the same string");
  }
  for (const [name, value] of [["ARRIVED", ARRIVED], ["OPENED", OPENED]]) {
    if (AMBIGUOUS.includes(value.trim().toLowerCase())) {
      found.push(`${name} is "${value}", which is one of the bare labels that caused this`);
    }
  }
  if (!ARRIVAL_WORDS.some((w) => ARRIVED.toLowerCase().includes(w))) {
    found.push(`ARRIVED is "${ARRIVED}" and does not say the file turned up on its own`);
  }
  if (!OPENED_WORDS.some((w) => OPENED.toLowerCase().includes(w))) {
    found.push(`OPENED is "${OPENED}" and does not say the user opened it`);
  }
  return found;
}

if (process.argv.includes("--selftest")) {
  const CASES = [
    ["the pair that shipped", 'const val ARRIVED = "New files"\nconst val OPENED = "Recent"', true],
    ["identical headings", 'const val ARRIVED = "Recently opened"\nconst val OPENED = "Recently opened"', true],
    ["arrival heading says nothing", 'const val ARRIVED = "Stuff"\nconst val OPENED = "Recently opened"', true],
    ["opened heading says nothing", 'const val ARRIVED = "New in your folders"\nconst val OPENED = "Latest"', true],
    ["only one declared", 'const val ARRIVED = "New in your folders"', true],
    // the positive control
    ["a pair that works", 'const val ARRIVED = "New in your folders"\nconst val OPENED = "Recently opened"', false],
  ];
  let bad = 0;
  for (const [name, src, expect] of CASES) {
    const got = problems(src).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} — expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(`HOME NAMES SELFTEST OK  (${CASES.filter((c) => c[2]).length} negative controls, 1 positive)`);
  process.exit(0);
}

const found = problems(readFileSync(SRC, "utf8"));
if (found.length) {
  console.error("The home feed headings cannot be told apart:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const { ARRIVED, OPENED } = labels(readFileSync(SRC, "utf8"));
console.log(`HOME NAMES OK  ("${ARRIVED}" and "${OPENED}", each naming its own cause)`);
