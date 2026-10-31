#!/usr/bin/env node
/**
 * The Settings search finds every setting on the page.
 *
 *   node tools/check-settingsindex.mjs              check the page against its index
 *   node tools/check-settingsindex.mjs --selftest   prove the check fails on known faults
 *
 * Three things have to hold, and each is a way a setting goes missing from search:
 *
 *  1. settings/SettingsIndex.kt is exactly what make-settings-index.mjs writes from the page
 *     now. A row added, renamed or moved without regenerating is a row search cannot find.
 *  2. Every indexed key has an anchor(...) on the page, so a result can scroll to its row.
 *     Without one the tap opens the right tab and leaves the row somewhere off screen.
 *  3. Every row with a title is indexed under one - no entry with a blank name in the results.
 *
 * Prints "SETTINGSINDEX OK" / "SETTINGSINDEX SELFTEST OK" only after every assertion passes.
 */
import { existsSync, readFileSync } from "node:fs";
import { PAGE, HELPERS, OUT, entries, render } from "./make-settings-index.mjs";

/** What is wrong with [page] and its [index] file, given the [helpers] it draws rows from. */
export function problems(page, helpers, index) {
  const found = [];
  let list;
  try {
    list = entries(page, helpers);
  } catch (e) {
    return [`the page could not be read: ${e.message}`];
  }
  if (list.length < 30) found.push(`only ${list.length} settings indexed; the page has far more than that`);
  if (index !== render(list)) {
    found.push(`${OUT} is out of date. Run: node tools/make-settings-index.mjs`);
  }
  const anchored = new Set();
  for (const m of page.matchAll(/\banchor\(([^)]*)\)/g)) {
    for (const k of m[1].split(",")) anchored.add(k.trim());
  }
  for (const e of list) {
    if (!anchored.has(e.key)) found.push(`${e.key} (${e.title}) has no anchor(...), so a result cannot scroll to it`);
    if (!e.title || !e.title.trim()) found.push(`${e.key} is indexed with no name`);
  }
  return found;
}

if (process.argv.includes("--selftest")) {
  const page = (rows) => `
fun SettingsPage() {
    LazyColumn(Modifier.fillMaxSize()) {
        if (group == SettingsGroup.APPEARANCE) {
        item { SettingsSection("Look", "") }
${rows}
        }
    }
}
`;
  const row = (i, anchored = true, title = `Row ${i}`) =>
    (anchored ? `        anchor(Prefs.K_${i})\n` : "") +
    `        item { ToggleRow("${title}", "What row ${i} does.", on, key = Prefs.K_${i}) { } }\n`;
  const many = (f) => Array.from({ length: 32 }, (_, i) => f(i)).join("");
  const good = page(many((i) => row(i)));
  const goodIndex = render(entries(good, []));
  const CASES = [
    ["a page and its index in step", good, goodIndex, false],
    ["a row added without regenerating", page(many((i) => row(i)) + row(99)), goodIndex, true],
    ["a row renamed without regenerating", good.replace('"Row 3"', '"Renamed"'), goodIndex, true],
    ["an indexed row with no anchor", page(many((i) => row(i, i !== 5))), render(entries(page(many((i) => row(i, i !== 5))), [])), true],
    ["too few settings to be the page", page(row(1)), render(entries(page(row(1)), [])), true],
    ["no list at all", "fun SettingsPage() {}", "", true],
  ];
  let bad = 0;
  for (const [name, p, idx, expect] of CASES) {
    const got = problems(p, [], idx).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} - expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  const neg = CASES.filter((c) => c[3]).length;
  console.log(`SETTINGSINDEX SELFTEST OK  (${neg} negative controls, ${CASES.length - neg} positive)`);
  process.exit(0);
}

const page = readFileSync(PAGE, "utf8");
const helpers = HELPERS.filter(existsSync).map((f) => readFileSync(f, "utf8"));
const index = existsSync(OUT) ? readFileSync(OUT, "utf8") : "";
const found = problems(page, helpers, index);
if (found.length) {
  console.error("Settings search would miss settings:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const n = entries(page, helpers).length;
console.log(`SETTINGSINDEX OK  (${n} settings, each indexed under its name and anchored for scrolling)`);
