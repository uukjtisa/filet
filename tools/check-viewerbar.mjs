#!/usr/bin/env node
/**
 * Every screen a file opens into draws the viewer bar: the way back, and the file's name.
 *
 * Bug identified here: a spreadsheet opened full screen with no bar at all - no back arrow, no
 * name - because the document screen routed sheets to their own screen BEFORE it drew its bar,
 * and the sheet screen had never drawn one of its own. The PDF and web readers had their own
 * strips that looked like the bar and were not it. Every one compiled and every one passed,
 * because "has a way out" was not written down anywhere. This writes it down.
 *
 * What it does: starts from the routes in `HandlerHost` (`HandlerId.X -> SomeScreen(`) and the
 * swipe-through host, follows every call to a function named `...Screen` or `...Host` that the
 * app defines, and requires each one it reaches to draw `ViewerBar(` - in code, not in a comment
 * or a string - either in its own body or in a helper it calls (`ImageViewerContent`, a player
 * face). Helpers are followed; screens are NOT counted as a screen's bar, because the defect was
 * exactly one screen handing off to another that drew none, while the first drew its own for
 * its other branch.
 *
 * A screen that legitimately draws no bar is named in EXEMPT with the reason. An exemption for
 * a screen no longer reached, or for one that draws a bar after all, is itself an error, so the
 * list cannot quietly outlive its reasons.
 *
 *   node tools/check-viewerbar.mjs              check
 *   node tools/check-viewerbar.mjs --selftest   prove each failure is caught
 *
 * Prints "VIEWERBAR OK" / "VIEWERBAR SELFTEST OK" only after every assertion passes.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const SRC = join(ROOT, "app/src/main/java");
const HOST = join(SRC, "dev/niccc2007/filet/handlers/HandlerHost.kt");

/**
 * What counts as the bar. The music player's two bars count as well: each has the way back,
 * and the face under it states the track. Classic wore `ViewerBar` itself until its title was
 * centred, and before that it was the only face this checker could see.
 */
const BAR = /\b(?:ViewerBar|PlayerTopBar|ClassicTopBar)\s*\(/;

/** Screens reached from a route that draw no bar, and why that is right. */
export const EXEMPT = {
  MediaScreen: "chooses the audio or the video screen and draws nothing of its own",
  ViewerRunHost: "hosts the run's pages, and each page draws its own bar; a page passing under a swipe is its poster for that moment",
};

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith(".kt")) out.push(p);
  }
  return out;
}

/**
 * [text] with every comment and string literal blanked to spaces, so braces and names inside
 * them count for nothing. Lengths are kept, so an index into one is an index into the other.
 */
export function code(text) {
  const out = text.split("");
  let i = 0;
  const blank = (from, to) => { for (let k = from; k < to; k++) if (out[k] !== "\n") out[k] = " "; };
  while (i < text.length) {
    if (text.startsWith("//", i)) {
      const end = text.indexOf("\n", i);
      const stop = end < 0 ? text.length : end;
      blank(i, stop); i = stop;
    } else if (text.startsWith("/*", i)) {
      const end = text.indexOf("*/", i + 2);
      const stop = end < 0 ? text.length : end + 2;
      blank(i, stop); i = stop;
    } else if (text.startsWith('"""', i)) {
      const end = text.indexOf('"""', i + 3);
      const stop = end < 0 ? text.length : end + 3;
      blank(i, stop); i = stop;
    } else if (text[i] === '"') {
      let k = i + 1;
      while (k < text.length && text[k] !== '"' && text[k] !== "\n") k += text[k] === "\\" ? 2 : 1;
      blank(i, k + 1); i = k + 1;
    } else if (text[i] === "'" && /^'(\\.|[^\\'])'/.test(text.slice(i, i + 4))) {
      const len = text[i + 1] === "\\" ? 4 : 3;
      blank(i, i + len); i += len;
    } else i++;
  }
  return out.join("");
}

/** A screen or a host: the things a file opens into, checked one by one. */
const SCREEN = /(?:Screen|Host)$/;

/**
 * Every capitalised `fun Name(` - which in this codebase is a composable - with its body, from
 * cleaned source. An extension receiver (`fun BoxScope.HaloFace(`) is allowed.
 */
export function definitions(files) {
  const defs = new Map();
  for (const [file, raw] of files) {
    const src = code(raw);
    for (const m of src.matchAll(/\bfun\s+(?:[A-Z]\w*\.)?([A-Z]\w*)\s*\(/g)) {
      let i = m.index + m[0].length;
      let depth = 1;
      while (i < src.length && depth > 0) { if (src[i] === "(") depth++; else if (src[i] === ")") depth--; i++; }
      const open = src.indexOf("{", i);
      if (open < 0) continue;
      let j = open + 1;
      depth = 1;
      while (j < src.length && depth > 0) { if (src[j] === "{") depth++; else if (src[j] === "}") depth--; j++; }
      const list = defs.get(m[1]) ?? [];
      list.push({ file, body: src.slice(open, j) });
      defs.set(m[1], list);
    }
  }
  return defs;
}

/** The screens HandlerHost opens a file into. */
export function routes(hostRaw) {
  const src = code(hostRaw);
  const out = new Set();
  for (const m of src.matchAll(/HandlerId\.[A-Z]+\s*->\s*(?:[\w.]+\.)?([A-Z]\w*(?:Screen|Host))\s*\(/g)) out.add(m[1]);
  for (const m of src.matchAll(/\b(ViewerRunHost)\s*\(/g)) out.add(m[1]);
  return [...out];
}

/**
 * Whether [d] draws the bar in its own body or through a helper. Helper names are common -
 * `BarButton` is defined privately in several files - so a helper in the caller's own file is
 * preferred, and only a name with none there is looked up everywhere.
 */
function drawsBar(d, defs, depth = 0, seen = new Set()) {
  if (BAR.test(d.body)) return true;
  if (depth >= 5) return false;
  for (const c of d.body.matchAll(/\b([A-Z]\w*)\s*\(/g)) {
    const name = c[1];
    if (SCREEN.test(name) || seen.has(name)) continue;
    const all = defs.get(name);
    if (!all) continue;
    seen.add(name);
    const local = all.filter((x) => x.file === d.file);
    for (const h of local.length ? local : all) if (drawsBar(h, defs, depth + 1, seen)) return true;
  }
  return false;
}

export function problems(files, hostRaw) {
  const found = [];
  const defs = definitions(files);
  const start = routes(hostRaw);
  if (start.length < 5) found.push(`only ${start.length} routes found in HandlerHost - has the when moved?`);
  const seen = new Set();
  const queue = [...start];
  while (queue.length) {
    const name = queue.shift();
    if (seen.has(name)) continue;
    seen.add(name);
    const list = defs.get(name);
    if (!list) { found.push(`${name} is routed to but defined nowhere under app/src/main/java`); continue; }
    for (const d of list) {
      const where = relative(ROOT, d.file).replaceAll("\\", "/");
      const bar = drawsBar(d, defs);
      if (!EXEMPT[name] && !bar) {
        found.push(`${name} (${where}) opens a file and draws no ViewerBar - there is no way back and no name`);
      }
      if (EXEMPT[name] && bar) found.push(`EXEMPT names ${name} (${where}), which draws a ViewerBar after all - drop the exemption`);
      for (const c of d.body.matchAll(/\b([A-Z]\w*)\s*\(/g)) {
        if (SCREEN.test(c[1]) && defs.has(c[1]) && !seen.has(c[1])) queue.push(c[1]);
      }
    }
  }
  for (const name of Object.keys(EXEMPT)) {
    if (!seen.has(name)) found.push(`EXEMPT names ${name}, which no route reaches any more - drop the exemption`);
  }
  return { found, reached: seen };
}

const files = walk(SRC).map((f) => [f, readFileSync(f, "utf8")]);
const host = readFileSync(HOST, "utf8");

if (process.argv.includes("--selftest")) {
  const base = problems(files, host);
  if (base.found.length) {
    console.error("VIEWERBAR SELFTEST cannot run: the tree does not pass its own check");
    for (const p of base.found) console.error("  " + p);
    process.exit(1);
  }
  const edit = (suffix, from, to) => files.map(([f, t]) => {
    if (!f.replaceAll("\\", "/").endsWith(suffix)) return [f, t];
    const out = t.replace(from, to);
    if (out === t) throw new Error(`mutation did not apply in ${suffix}: ${from}`);
    return [f, out];
  });
  const CASES = [
    // The defect itself: a screen reached only through another screen, with no bar.
    ["the sheet screen losing its bar", () => [edit("docs/SheetScreen.kt", /\bViewerBar\s*\(/g, "Spacer("), host]],
    ["a bar mentioned only in a comment", () => [edit("docs/PdfScreen.kt", /\bViewerBar\s*\(/g, "// ViewerBar(\n Spacer("), host]],
    ["a new route to a screen with no bar", () => [
      [...files, [join(SRC, "x/NewThing.kt"), "@Composable\nfun NewThingScreen(vm: Any) {\n    Box {}\n}\n"]],
      host.replace("HandlerId.HEX ->", "HandlerId.ARCHIVE -> NewThingScreen(vm)\n                    HandlerId.HEX ->"),
    ]],
    ["a route to nothing", () => [files, host.replace("HandlerId.HEX ->", "HandlerId.ARCHIVE -> GoneScreen(vm)\n                    HandlerId.HEX ->")]],
  ];
  let bad = 0;
  for (const [name, mutate] of CASES) {
    const [f, h] = mutate();
    if (problems(f, h).found.length === 0) { console.error(`SELFTEST FAILED: ${name} - not caught`); bad++; }
  }
  // A stale exemption, which needs EXEMPT itself changed.
  EXEMPT.RetiredScreen = "test";
  if (problems(files, host).found.length === 0) { console.error("SELFTEST FAILED: a stale exemption - not caught"); bad++; }
  delete EXEMPT.RetiredScreen;
  if (bad) process.exit(1);
  console.log(`VIEWERBAR SELFTEST OK  (${CASES.length + 1} negative controls, 1 positive)`);
  process.exit(0);
}

const { found, reached } = problems(files, host);
if (found.length) {
  console.error("A screen a file opens into has no viewer bar:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const exempt = Object.keys(EXEMPT).length;
console.log(`VIEWERBAR OK  (${reached.size} screens reached from the routes, ${reached.size - exempt} with a bar, ${exempt} exempt with a reason)`);
