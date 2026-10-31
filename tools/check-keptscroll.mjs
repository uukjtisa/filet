#!/usr/bin/env node
/**
 * Every screen a tab can show keeps its scroll with the tab.
 *
 * Bug identified here: only the folder listing put its position back. Home, the categories,
 * New files, Settings, the playlists and the rest each held a bare `rememberLazyListState()`,
 * which lives as long as the composition - so a tab switch, and any restart, put them all back
 * at the top. The folder had been fixed on its own, and nothing told the next screen it had the
 * same problem. `PaneMemory` is the one place a screen's position goes now; this makes the next
 * screen use it.
 *
 * The rule, for every file that draws a screen routed from PaneView's `SpecialBodyContent`, and
 * the few that screen delegates its list to (EXTRA below):
 *
 *  - a `LazyColumn(` or `LazyVerticalGrid(` names its `state =`, and
 *  - no state is made with `rememberLazyListState()` / `rememberLazyGridState()`, which forget.
 *
 * Either is allowed where the three lines above say `Not kept:` and why - a list of search
 * results that re-runs in a different order, a dialogue that is not part of the tab.
 *
 * The limit, said plainly: this sees the call, not the behaviour. It cannot tell whether the
 * place a screen names is the right one, only that the screen named one.
 *
 *   node tools/check-keptscroll.mjs              check
 *   node tools/check-keptscroll.mjs --selftest   prove it catches a forgetting screen
 *
 * Prints "KEPTSCROLL OK" only after every assertion passes.
 */
import { readdirSync, readFileSync, statSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { join, sep } from "node:path";
import { tmpdir } from "node:os";

const SRC = "app/src/main/java";
const ROUTER = "app/src/main/java/dev/niccc2007/filet/browser/PaneView.kt";

/** Files a routed screen hands its list to, and which screen. */
const EXTRA = {
  "app/src/main/java/dev/niccc2007/filet/home/CategoryGallery.kt": "the gallery layout of CategoryScreen",
};

/** Called in the router but not screens: they draw no list of their own. */
const NOT_SCREENS = new Set(["EmptyNote", "ActivityActions", "Modifier"]);

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (name.endsWith(".kt")) out.push(full);
  }
  return out;
}

/** The body of `fun name(` in text: from its first brace to the one that closes it. */
function bodyOf(text, name) {
  const at = text.search(new RegExp(`fun\\s+${name}\\s*\\(`));
  if (at < 0) return null;
  const open = text.indexOf("{", text.indexOf(")", at));
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === "{") depth++;
    else if (text[i] === "}" && --depth === 0) return text.slice(open, i + 1);
  }
  return null;
}

/** The argument list of the call whose name ends at [from], up to its closing parenthesis. */
function argsAt(text, from) {
  let depth = 0;
  for (let i = from; i < text.length; i++) {
    if (text[i] === "(") depth++;
    else if (text[i] === ")" && --depth === 0) return text.slice(from, i + 1);
  }
  return text.slice(from);
}

function excused(lines, lineIndex) {
  for (let k = Math.max(0, lineIndex - 3); k <= lineIndex; k++) if (/Not kept:/.test(lines[k])) return true;
  return false;
}

export function check(root, readFile = (f) => readFileSync(f, "utf8")) {
  const problems = [];
  const files = walk(join(root, SRC));
  const rel = (f) => f.slice(root.length + 1).split(sep).join("/");
  const byRel = new Map(files.map((f) => [rel(f), f]));

  const router = byRel.get(ROUTER);
  if (!router) return { problems: [`${ROUTER} is gone; the router moved`], screens: [] };
  const route = bodyOf(readFile(router), "SpecialBodyContent");
  if (!route) return { problems: ["SpecialBodyContent is gone from PaneView.kt; the router moved"], screens: [] };

  // Every screen the router calls, found by where it is defined.
  const names = new Set([...route.matchAll(/\b([A-Z][A-Za-z0-9]+)\s*\(/g)].map((m) => m[1]).filter((n) => !NOT_SCREENS.has(n)));
  const screens = new Set(Object.keys(EXTRA));
  for (const name of names) {
    const def = new RegExp(`fun\\s+${name}\\s*\\(`);
    const home = files.find((f) => def.test(readFile(f)));
    if (home) screens.add(rel(home));
  }
  for (const r of Object.keys(EXTRA)) if (!byRel.has(r)) problems.push(`EXTRA lists ${r}, which does not exist any more`);
  // Fewer than this and the router was misread, which would pass everything.
  if (screens.size < 12) problems.push(`only found ${screens.size} screens behind the router; it was misread`);

  for (const r of screens) {
    const f = byRel.get(r);
    if (!f || r === ROUTER) continue;
    const text = readFile(f);
    const lines = text.split("\n");
    const lineOf = (index) => text.slice(0, index).split("\n").length - 1;
    for (const m of text.matchAll(/\b(LazyColumn|LazyVerticalGrid)\s*\(/g)) {
      const at = m.index + m[0].length - 1;
      const line = lineOf(m.index);
      if (/^\s*(import|\*|\/\/)/.test(lines[line])) continue;
      if (/state\s*=/.test(argsAt(text, at))) continue;
      if (excused(lines, line)) continue;
      problems.push(`${r}:${line + 1} ${m[1]} with no state: it forgets its scroll on every tab switch. ` +
        `Give it state = rememberKeptListState("<place>") (or the grid one), or say "Not kept:" and why above it.`);
    }
    for (const m of text.matchAll(/\b(rememberLazyListState|rememberLazyGridState)\s*\(/g)) {
      const line = lineOf(m.index);
      if (/^\s*(import|\*|\/\/)/.test(lines[line])) continue;
      if (excused(lines, line)) continue;
      problems.push(`${r}:${line + 1} ${m[1]}() forgets with the composition. Use the rememberKept one from PaneMemory, ` +
        `or say "Not kept:" and why above it.`);
    }
  }
  return { problems, screens: [...screens] };
}

if (process.argv.includes("--selftest")) {
  const dir = mkdtempSync(join(tmpdir(), "keptscroll-"));
  const fails = [];
  try {
    const put = (r, body) => {
      const full = join(dir, ...r.split("/"));
      mkdirSync(join(full, ".."), { recursive: true });
      writeFileSync(full, body);
    };
    const screen = (i, body) => put(`${SRC}/dev/niccc2007/filet/s/S${i}.kt`, `@Composable\nfun Screen${i}() {\n${body}\n}\n`);
    const calls = Array.from({ length: 13 }, (_, i) => `PaneKind.K${i} -> Screen${i}()`).join("\n        ");
    put(ROUTER, `private fun SpecialBodyContent() {\n    when (k) {\n        ${calls}\n        else -> EmptyNote("x")\n    }\n}\n`);
    put("app/src/main/java/dev/niccc2007/filet/home/CategoryGallery.kt", "fun CategoryGallery() { LazyVerticalGrid(state = rememberKeptGridState(\"g\")) {} }\n");
    for (let i = 0; i < 13; i++) screen(i, `    LazyColumn(Modifier.fillMaxSize(), state = rememberKeptListState("s${i}")) { item {} }`);

    let r = check(dir);
    if (r.problems.length) fails.push("a tree where every screen keeps its scroll failed: " + r.problems.join("; "));

    screen(3, "    LazyColumn(Modifier.fillMaxSize()) { item {} }");
    r = check(dir);
    if (!r.problems.some((p) => p.includes("S3.kt") && p.includes("no state"))) fails.push("a LazyColumn with no state was not caught");

    screen(3, "    val s = rememberLazyListState()\n    LazyColumn(state = s) {}");
    r = check(dir);
    if (!r.problems.some((p) => p.includes("S3.kt") && p.includes("rememberLazyListState"))) fails.push("a bare rememberLazyListState was not caught");

    screen(3, "    // Not kept: search results re-run in a new order.\n    LazyColumn(Modifier.fillMaxSize()) {}");
    r = check(dir);
    if (r.problems.length) fails.push("a list excused with a reason was still flagged: " + r.problems.join("; "));

    put(`${SRC}/dev/niccc2007/filet/s/S7.kt`, "@Composable\nfun Screen7() {\n    LazyVerticalGrid(columns = x) {}\n}\n");
    r = check(dir);
    if (!r.problems.some((p) => p.includes("S7.kt") && p.includes("LazyVerticalGrid"))) fails.push("a grid with no state was not caught");

    put(ROUTER, "private fun SomethingElse() {}\n");
    r = check(dir);
    if (!r.problems.some((p) => p.includes("SpecialBodyContent"))) fails.push("a moved router passed silently");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
  if (fails.length) {
    console.error("KEPTSCROLL SELFTEST FAILED:\n  " + fails.join("\n  "));
    process.exit(1);
  }
  console.log("KEPTSCROLL OK (selftest)");
} else {
  const { problems, screens } = check(process.cwd());
  if (problems.length) {
    console.error(`KEPTSCROLL: ${problems.length} problem(s)\n  ` + problems.join("\n  "));
    process.exit(1);
  }
  console.log(`KEPTSCROLL OK  (${screens.length} screens keep their scroll)`);
}
