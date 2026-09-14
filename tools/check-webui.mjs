#!/usr/bin/env node
/**
 * The share page must not build a whole folder's worth of rows at once.
 *
 * Nic's report: sharing a large folder made the page unresponsive on a phone and fine on a
 * PC. That split is the signature of a render that is merely expensive rather than wrong -
 * a desktop browser absorbs fifty thousand DOM nodes and a phone does not - which is why it
 * survived being tested on a laptop.
 *
 * The fix is a windowed render, and the thing a checker is good for is that a later edit
 * "simplifying" the loop back to `for (const f of shown)` would put the hang back with no
 * test failing anywhere.
 *
 *   node tools/check-webui.mjs              check
 *   node tools/check-webui.mjs --selftest   prove it fails on the pre-fix shape
 *
 * Prints "WEBUI OK" / "WEBUI SELFTEST OK" only after every assertion passes.
 */
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const PAGE = "app/src/main/assets/web/app.html";

function check(html) {
  const problems = [];

  const script = html.slice(html.indexOf("<script>") + 8, html.indexOf("</script>"));
  if (!script) return ["no script block in the share page"];

  // 1. It parses. A page that does not is a share that shows nothing at all.
  const runnable = script.replace(/\{\{UPLOADS\}\}/g, "true").replace(/\{\{[A-Z_]+\}\}/g, "x");
  try {
    new Function(runnable);
  } catch (e) {
    problems.push(`the share page's script does not parse: ${e.message}`);
  }

  // 2. A window, and a size for it.
  if (!/const\s+PAGE\s*=\s*\d+/.test(script)) {
    problems.push("no PAGE size: the listing is not windowed");
  }
  if (!/windowSize/.test(script)) {
    problems.push("no windowSize: every row is still built at once");
  }
  if (!/\.slice\(0,\s*windowSize\)/.test(script)) {
    problems.push("the render does not slice to the window");
  }

  // 3. Built off-document. Appending to a live list re-lays out the page per row.
  if (!/createDocumentFragment/.test(script)) {
    problems.push("rows are appended to the live list rather than to a fragment");
  }

  // 4. Thumbnails stay lazy: one request per visible row, not per row in the folder.
  if (!/loading\s*=\s*'lazy'/.test(script)) {
    problems.push("thumbnails are not lazy, so a folder of photos fetches every preview");
  }

  // 5. More rows arrive on their own.
  if (!/IntersectionObserver/.test(script)) {
    problems.push("nothing loads the next page as you reach the bottom");
  }

  // 6. The count admits when it is showing part of the folder.
  if (!/showing/.test(script)) {
    problems.push("the count does not say when only part of the folder is on screen");
  }

  return problems;
}

function selftest() {
  const good = readFileSync(PAGE, "utf8");
  const cases = [
    [
      "the pre-fix render, rebuilt whole every time",
      good.replace(/\.slice\(0,\s*windowSize\)/, ""),
      /slice to the window/,
    ],
    [
      "rows appended straight to the live list",
      good.replace(/createDocumentFragment/g, "notAFragment"),
      /fragment/,
    ],
    [
      "eager thumbnails",
      good.replace(/loading\s*=\s*'lazy'/g, "loading = 'eager'"),
      /lazy/,
    ],
    [
      "no more-rows watcher",
      good.replace(/IntersectionObserver/g, "NothingAtAll"),
      /reach the bottom/,
    ],
    [
      "a script that does not parse",
      good.replace("function render(keepWindow) {", "function render(keepWindow) { ("),
      /does not parse/,
    ],
  ];
  let failures = 0;
  for (const [name, html, expected] of cases) {
    const problems = check(html);
    if (!problems.some((p) => expected.test(p))) {
      failures++;
      console.error(`SELFTEST FAIL: "${name}" not caught. Got: ${problems.join("; ") || "(nothing)"}`);
    }
  }
  if (failures) {
    console.error(`WEBUI SELFTEST: ${failures} case(s) not caught`);
    process.exit(1);
  }
  console.log(`WEBUI SELFTEST OK  (${cases.length} negative controls all caught)`);
}

const html = readFileSync(PAGE, "utf8");
if (process.argv.includes("--selftest")) {
  selftest();
} else {
  const problems = check(html);
  if (problems.length) {
    console.error("WEBUI PROBLEMS: " + problems.length);
    for (const p of problems) console.error("  " + p);
    process.exit(1);
  }
  console.log("WEBUI OK  (listing is windowed, built off-document, thumbnails lazy)");
}
