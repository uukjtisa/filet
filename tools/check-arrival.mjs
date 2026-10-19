#!/usr/bin/env node
/**
 * Arriving expands, walking moves. Every `navigateTo` says which it is.
 *
 * The rule: `jumpTo` for a path that arrives from OUTSIDE the pane - a shortcut, a widget, a
 * bookmark, a recent, a tracked folder, a rail row, a new tab opened at a path. `navigateTo`
 * for a step taken INSIDE it - a row tap, the breadcrumb, the path bar, going up, descending
 * into a mount. With the tree view on, `jumpTo` opens the volume's trunk down to the target;
 * `navigateTo` re-roots the pane there. Use the wrong one on an arrival and the tree has no
 * trunk: the folder appears at the top with no parents above it and no way to walk out.
 *
 * Why this is a check and not a convention. The rule was written down in CLAUDE.md, and an
 * audit then found SEVENTEEN arrival sites still calling `navigateTo` - bookmarks, both rail
 * rows, tracked folders, recents, the logs/extracted/received folders, the decompile output,
 * go-to-containing-folder, saved connections and two tab-open paths. All seventeen compiled,
 * none was caught by review, and each produced a trunkless tree for whoever used that door.
 * A rule that costs nothing to break is not a rule.
 *
 * So a bare `navigateTo` outside `PaneController` is a finding unless the line, or the line
 * above it, carries a `// walking:` marker saying why this one is a step inside the pane.
 * The marker goes at the call site on purpose rather than in a list here: the next person to
 * add a door needs the reasoning where they are typing, not in a tools file.
 *
 * Known limit, stated honestly: this sees the call, not the intent. A marker on a genuine
 * arrival will pass. It cannot decide correctness - only that somebody decided.
 *
 *   node tools/check-arrival.mjs              check
 *   node tools/check-arrival.mjs --selftest   prove it catches an unmarked call
 *
 * Prints "ARRIVAL OK" only after every assertion passes.
 */
import { readdirSync, readFileSync, statSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { tmpdir } from "node:os";

const MARKER = "// walking:";

/**
 * Files exempt entirely, and why.
 *
 * Only the controller that declares both verbs. Everywhere else marks its call sites, because
 * everywhere else is a door somebody can add another one beside.
 */
const EXEMPT = {
  "app/src/main/java/dev/niccc2007/filet/browser/PaneController.kt":
    "declares navigateTo, jumpTo and revealInTree. jumpTo delegates to navigateTo, so the " +
    "calls here are the implementation of the rule rather than uses of it.",
};

function kotlinFiles(root) {
  const out = [];
  (function walk(dir) {
    for (const name of readdirSync(dir)) {
      if (name === "build" || name === ".git" || name === "node_modules") continue;
      const full = join(dir, name);
      if (statSync(full).isDirectory()) walk(full);
      else if (name.endsWith(".kt")) out.push(full);
    }
  })(root);
  return out;
}

/**
 * Find unmarked `navigateTo` calls in one file's text.
 *
 * Exported shape rather than inlined so the selftest exercises the same function the check
 * does - a selftest against a second copy of the logic proves nothing about the first.
 */
export function findings(rel, text) {
  if (EXEMPT[rel]) return [];
  const lines = text.split("\n");
  const hits = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.includes("navigateTo(")) continue;
    // The declaration itself, and any KDoc or comment that merely names it.
    if (/\bfun\s+navigateTo\s*\(/.test(line)) continue;
    const code = line.split("//")[0];
    if (!code.includes("navigateTo(")) continue;
    // Scan UP through the contiguous comment block, not just one line. A reason worth
    // giving often needs two lines, and requiring the keyword to sit immediately above the
    // call would push authors into cramming it onto one - which is how a rule starts
    // producing worse comments than no rule at all.
    let marked = line.includes(MARKER);
    for (let j = i - 1; j >= 0 && !marked; j--) {
      const t = lines[j].trim();
      if (t === "" || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
        if (t.includes(MARKER)) marked = true;
        if (t === "") break;
      } else break;
    }
    if (marked) continue;
    hits.push({ line: i + 1, text: line.trim() });
  }
  return hits;
}

function selftest() {
  const dir = mkdtempSync(join(tmpdir(), "arrival-"));
  try {
    const bare = "fun open(p: VPath) {\n    pane.navigateTo(p)\n}\n";
    const marked = "fun open(p: VPath) {\n    // walking: a row tap, inside the pane.\n    pane.navigateTo(p)\n}\n";
    const inline = "fun open(p: VPath) {\n    pane.navigateTo(p) // walking: the breadcrumb.\n}\n";
    const decl = "fun navigateTo(path: VPath, push: Boolean = true) {\n}\n";
    // Two lines of reason, keyword on the first - the shape the real call sites use.
    const block = [
      "fun open(p: VPath) {",
      "    // walking: a row tap.",
      "    // The pane is already here.",
      "    pane.navigateTo(p)",
      "}",
    ].join("\n");
    const unmarked = [
      "fun open(p: VPath) {",
      "    // just a note.",
      "    pane.navigateTo(p)",
      "}",
    ].join("\n");
    const checks = [
      ["a bare call is caught", findings("x/A.kt", bare).length === 1],
      ["a marker above it clears it", findings("x/A.kt", marked).length === 0],
      ["a marker on the line clears it", findings("x/A.kt", inline).length === 0],
      ["a marker higher in the comment block clears it", findings("x/A.kt", block).length === 0],
      ["a comment block with no marker is still caught", findings("x/A.kt", unmarked).length === 1],
      ["the declaration is not a use", findings("x/A.kt", decl).length === 0],
      ["an exempt file is skipped", findings(Object.keys(EXEMPT)[0], bare).length === 0],
    ];
    let bad = 0;
    for (const [what, ok] of checks) {
      if (!ok) { console.error("SELFTEST FAILED: " + what); bad++; }
    }
    if (bad) process.exit(1);
    console.log("ARRIVAL SELFTEST OK  (" + checks.length + " assertions)");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

if (process.argv.includes("--selftest")) {
  selftest();
} else {
  const root = process.cwd();
  let total = 0;
  const report = [];
  for (const full of kotlinFiles(join(root, "app", "src", "main"))) {
    const rel = relative(root, full).split(sep).join("/");
    const hits = findings(rel, readFileSync(full, "utf8"));
    for (const h of hits) report.push("  " + rel + ":" + h.line + "  " + h.text);
    total += hits.length;
  }
  if (total) {
    console.error("UNMARKED navigateTo CALLS: " + total);
    console.error(report.join("\n"));
    console.error(
      "\nArriving expands, walking moves. If this is a step inside the pane, put\n" +
      "`" + MARKER + " <why>` on the line or the line above. If it is a path arriving from\n" +
      "outside the pane, it should be jumpTo - see PaneController.jumpTo.",
    );
    process.exit(1);
  }
  console.log("ARRIVAL OK  (every navigateTo is marked as walking or is in PaneController)");
}
