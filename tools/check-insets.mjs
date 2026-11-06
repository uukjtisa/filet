#!/usr/bin/env node
/**
 * Nothing the app draws against the bottom of the screen goes under the navigation bar.
 *
 * Bug identified here (issues #39, #40, #44): with three-key navigation, the selection bar's
 * Copy, Move and Delete sat under Back, Home and Recents, and so did the player's Up next. The
 * root only ever cleared the status bar; below it each surface had to clear the navigation bar
 * itself, six did, and nothing said the rest had to. On a gesture phone the strip is thin
 * enough to miss, which is how it shipped. This writes the rule down.
 *
 * Two rules.
 *
 * EDGES: the surfaces drawn against an edge of the screen keep what clears them. Each entry
 * names a file, optionally a composable in it, and what must (or, for the browser's stack of
 * bars, must NOT) be there: that stack is cleared by ONE strip after its last bar, so a bar in
 * it that pads itself would pad twice.
 *
 * ANCHORS: every `Alignment.BottomCenter/Start/End` in the app's code is either cleared - a
 * navigation-bar inset in the same stretch of code, from 8 lines above it to 15 below - or
 * listed in KNOWN for its file, with the reason it needs none:
 *   inner  not at the screen's edge at all: a badge on a tile, a mark on a card;
 *   host   at the bottom of something that is itself already clear: a pane (above the
 *          browser's bottom strip), a viewer (HandlerHost stops it at the bar), a dialogue;
 *   self   at the screen's edge, cleared further into its own composable `fn`, which this
 *          then requires to carry the inset.
 * The counts must match exactly. A new bottom anchor nobody has looked at fails, and so does an
 * entry for one that has gone, so the list cannot quietly outlive its reasons.
 *
 * What it cannot see: a bar that is simply the last child of a full-height column, with no
 * alignment to find. Those are what EDGES is for, and a new one has to be added there.
 *
 *   node tools/check-insets.mjs              check
 *   node tools/check-insets.mjs --selftest   prove each failure is caught
 *
 * Prints "INSETS OK" / "INSETS SELFTEST OK" only after every assertion passes.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const SRC = join(ROOT, "app/src/main/java/dev/niccc2007/filet");

/** What clears the navigation bar. The keyboard's inset alone does not: it is nothing while it is down. */
const INSET = /WindowInsets\.(?:navigationBars|safeDrawing|systemBars|safeContent)\b|\b(?:navigationBarsPadding|systemBarsPadding|safeDrawingPadding|safeContentPadding)\s*\(/;

const ANCHOR = /\bAlignment\.Bottom(?:Center|Start|End)\b/g;

/** Lines before and after an anchor searched for its inset. */
const ABOVE = 8;
const BELOW = 15;

/** The surfaces at an edge of the screen, and what keeps them clear. Paths are under SRC. */
export const EDGES = [
  { file: "browser/BrowserScreen.kt", must: /windowInsetsBottomHeight\(\s*WindowInsets\.navigationBars\s*\)/, why: "the one strip under the browser's last bar" },
  { file: "browser/BrowserScreen.kt", must: /WindowInsets\.safeDrawing\.only\(\s*WindowInsetsSides\.Horizontal\s*\)/, why: "the browser clear of a side navigation bar and the cutout, sideways" },
  { file: "browser/BrowserScreen.kt", fn: "BottomNav", mustNot: INSET, why: "in the stack: the strip after the last bar clears it, an inset of its own pads twice" },
  { file: "browser/BrowserScreen.kt", fn: "SelectionBar", mustNot: INSET, why: "in the stack: the strip after the last bar clears it, an inset of its own pads twice" },
  { file: "playback/NowPlayingStrip.kt", fn: "NowPlayingStrip", mustNot: INSET, why: "in the browser's stack: the strip after the last bar clears it" },
  { file: "browser/BrowserScreen.kt", fn: "Toast", must: INSET, why: "measured from above the bar, not from the screen's edge" },
  { file: "handlers/HandlerHost.kt", fn: "HandlerHost", must: /WindowInsets\.navigationBars/, why: "every viewer but the picture and the player stops at the bar" },
  { file: "handlers/PlayerParts.kt", fn: "FaceFrame", must: /WindowInsets\.safeDrawing/, why: "every player face: the queue handle, the transport, the top bar under a cutout" },
  { file: "handlers/PlayerParts.kt", fn: "QueueSheet", must: /WindowInsets\.navigationBars/, why: "Up next, drawn over the face rather than inside its frame" },
  { file: "handlers/VideoScreen.kt", must: /WindowInsets\.navigationBars/, why: "the video's chrome" },
  { file: "handlers/VideoScreen.kt", must: /WindowInsets\.displayCutout\s*\.union\(\s*WindowInsets\.statusBars\s*\)/, why: "the video's top bar below the camera, and below the status bar while a swipe shows it" },
  { file: "handlers/FrameBar.kt", fn: "FrameBar", must: INSET, why: "the frame bar is the video's bottom edge when it is up" },
  { file: "ui/dialogs/DlgKit.kt", fn: "Dlg", must: /WindowInsets\.safeDrawing/, why: "every dialogue and sheet drawn through Dlg" },
];

/** Bottom anchors that need no inset where they are, by file under SRC. See the header. */
export const KNOWN = {
  "about/AboutPage.kt": [{ n: 1, kind: "inner", why: "the mark drawn over the corner of the About card" }],
  "browser/Dock.kt": [{ n: 2, kind: "host", why: "the paste pill and its scrim, inside a pane: the browser's strip is under it" }],
  "browser/ExtractSheet.kt": [{ n: 1, kind: "host", why: "a card inside a pane like PickDock (it has no caller today)" }],
  "browser/PickDock.kt": [{ n: 1, kind: "host", why: "the pick card, inside a pane: the browser's strip is under it" }],
  "browser/RowViews.kt": [{ n: 1, kind: "inner", why: "the select mark on a row's icon" }],
  "browser/ThumbIcon.kt": [{ n: 1, kind: "inner", why: "a badge on a thumbnail" }],
  "ui/dialogs/DlgKit.kt": [{ n: 1, kind: "host", why: "a dialogue drawn as a sheet, inside the room Dlg has already cleared by safeDrawing" }],
  "docs/DocCode.kt": [{ n: 1, kind: "inner", why: "the chip row inside a code block" }],
  "docs/PdfScreen.kt": [{ n: 1, kind: "host", why: "the page counter, in a viewer: HandlerHost stops it at the bar" }],
  "handlers/ImageScreen.kt": [
    { n: 1, kind: "self", fn: "PictureActions", why: "the picture's action row, cleared in PictureActions" },
    { n: 1, kind: "self", fn: "ToolStrip", why: "the editor's tool row, cleared in ToolStrip" },
  ],
  "handlers/TilePeek.kt": [{ n: 1, kind: "inner", why: "the segment bar on the peek's card" }],
  "home/CategoryGallery.kt": [{ n: 1, kind: "inner", why: "the video badge on a gallery tile" }],
  "playback/PlaylistsScreen.kt": [
    { n: 1, kind: "inner", why: "the play button on a playlist's cover" },
    { n: 2, kind: "host", why: "the selection bars, inside the playlists pane: the browser's strip is under them" },
  ],
  "qr/ScanScreen.kt": [{ n: 1, kind: "host", why: "the camera buttons, inside the scan pane: the browser's strip is under them" }],
  "report/ReportActivity.kt": [{ n: 1, kind: "inner", why: "the size label on an attached picture's thumbnail" }],
  "signet/OnboardingScreen.kt": [{ n: 1, kind: "inner", why: "a glow drawn off the bottom corner, behind everything" }],
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
 * [text] with every comment and string literal blanked to spaces, so what is written about an
 * inset counts for nothing. Lengths and line breaks are kept.
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

/** The bodies of every `fun [Receiver.]name(` in cleaned [src]. */
export function bodies(src, name) {
  const out = [];
  const re = new RegExp(`\\bfun\\s+(?:[A-Z]\\w*\\.)?${name}\\s*\\(`, "g");
  for (const m of src.matchAll(re)) {
    let i = m.index + m[0].length;
    let depth = 1;
    while (i < src.length && depth > 0) { if (src[i] === "(") depth++; else if (src[i] === ")") depth--; i++; }
    const open = src.indexOf("{", i);
    if (open < 0) continue;
    let j = open + 1;
    depth = 1;
    while (j < src.length && depth > 0) { if (src[j] === "{") depth++; else if (src[j] === "}") depth--; j++; }
    out.push(src.slice(open, j));
  }
  return out;
}

/** The bottom anchors in cleaned [src] with no inset near them, as 1-based line numbers. */
export function uncleared(src) {
  const lines = src.split("\n");
  const out = [];
  lines.forEach((line, i) => {
    if (!line.match(ANCHOR)) return;
    const near = lines.slice(Math.max(0, i - ABOVE), i + BELOW + 1).join("\n");
    if (!INSET.test(near)) for (const _ of line.matchAll(ANCHOR)) out.push(i + 1);
  });
  return out;
}

/** [files]: path under SRC (forward slashes) to raw text. */
export function problems(files, edges = EDGES, known = KNOWN) {
  const found = [];
  const clean = new Map([...files].map(([f, t]) => [f, code(t)]));
  for (const e of edges) {
    const src = clean.get(e.file);
    if (src == null) { found.push(`EDGES names ${e.file}, which does not exist - move or drop the entry`); continue; }
    const scopes = e.fn ? bodies(src, e.fn) : [src];
    if (e.fn && scopes.length === 0) { found.push(`EDGES names ${e.fn} in ${e.file}, which is not defined there - move or drop the entry`); continue; }
    for (const s of scopes) {
      if (e.must && !e.must.test(s)) found.push(`${e.file}${e.fn ? ` ${e.fn}` : ""} no longer clears the navigation bar: ${e.why}`);
      if (e.mustNot && e.mustNot.test(s)) found.push(`${e.file} ${e.fn} has an inset of its own, and must not: ${e.why}`);
    }
  }
  for (const [file, src] of clean) {
    const open = uncleared(src);
    const listed = known[file] ?? [];
    const want = listed.reduce((a, k) => a + k.n, 0);
    if (open.length !== want) {
      if (open.length > want) {
        found.push(`${file}: ${open.length} bottom anchor(s) with no navigation-bar inset (lines ${open.join(", ")}), ${want} listed in KNOWN. At the screen's edge: clear it. Not at the edge: list it in KNOWN with why.`);
      } else {
        found.push(`${file}: KNOWN lists ${want} uncleared bottom anchor(s), the file has ${open.length}${open.length ? ` (lines ${open.join(", ")})` : ""} - update or drop the entry`);
      }
    }
    for (const k of listed) {
      if (k.kind === "self" && !bodies(src, k.fn).some((b) => INSET.test(b))) {
        found.push(`${file}: KNOWN says ${k.fn} clears its own anchor (${k.why}), and it has no inset`);
      }
    }
  }
  for (const file of Object.keys(known)) {
    if (!clean.has(file)) found.push(`KNOWN names ${file}, which does not exist - drop the entry`);
  }
  return found;
}

const files = new Map(walk(SRC).map((f) => [relative(SRC, f).replaceAll("\\", "/"), readFileSync(f, "utf8")]));

if (process.argv.includes("--selftest")) {
  const base = problems(files);
  if (base.length) {
    console.error("INSETS SELFTEST cannot run: the tree does not pass its own check");
    for (const p of base) console.error("  " + p);
    process.exit(1);
  }
  const edit = (file, from, to) => {
    const t = files.get(file);
    const out = t.replace(from, to);
    if (out === t) throw new Error(`mutation did not apply in ${file}: ${from}`);
    return new Map([...files, [file, out]]);
  };
  const CASES = [
    // The defect itself, both halves: the stack's strip gone, and a bar in it padding twice.
    ["the browser's strip removed", () => edit("browser/BrowserScreen.kt", /windowInsetsBottomHeight\(WindowInsets\.navigationBars\)/, "height(0.dp)")],
    ["the selection bar padding itself", () => edit("browser/BrowserScreen.kt", /(private fun SelectionBar\([^)]*\)\s*\{)/, "$1\n    Modifier.windowInsetsPadding(WindowInsets.navigationBars)")],
    ["the player's frame losing its inset", () => edit("handlers/PlayerParts.kt", /\.windowInsetsPadding\(WindowInsets\.safeDrawing\)/, "")],
    ["the inset only in a comment", () => edit("handlers/HandlerHost.kt", /Modifier\.windowInsetsPadding\(WindowInsets\.navigationBars\)/, "Modifier /* .windowInsetsPadding(WindowInsets.navigationBars) */")],
    ["Up next losing its inset", () => edit("handlers/PlayerParts.kt", /\.windowInsetsPadding\(WindowInsets\.navigationBars\),/, ",")],
    ["the video's top bar under the camera again", () => edit("handlers/VideoScreen.kt", /WindowInsets\.displayCutout\.union\(WindowInsets\.statusBars\)/, "WindowInsets.statusBars")],
    ["a new bar anchored to the bottom", () => new Map([...files, ["x/NewBar.kt", "@Composable\nfun BoxScope.NewBar() {\n    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {}\n}\n"]])],
  ];
  let bad = 0;
  for (const [name, mutate] of CASES) {
    if (problems(mutate()).length === 0) { console.error(`SELFTEST FAILED: ${name} - not caught`); bad++; }
  }
  // Positive: the same new bar with its inset passes.
  const ok = new Map([...files, ["x/NewBar.kt", "@Composable\nfun BoxScope.NewBar() {\n    Row(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)) {}\n}\n"]]);
  if (problems(ok).length) { console.error("SELFTEST FAILED: a new bar WITH its inset was refused"); bad++; }
  // A stale entry, which needs KNOWN itself changed.
  const stale = { ...KNOWN, "about/AboutPage.kt": [{ n: 2, kind: "inner", why: "test" }] };
  if (problems(files, EDGES, stale).length === 0) { console.error("SELFTEST FAILED: a stale KNOWN count - not caught"); bad++; }
  if (bad) process.exit(1);
  console.log(`INSETS SELFTEST OK  (${CASES.length + 1} negative controls, 1 positive)`);
  process.exit(0);
}

const found = problems(files);
if (found.length) {
  console.error("Something drawn at the bottom of the screen can sit under the navigation bar:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const listed = Object.values(KNOWN).flat().reduce((a, k) => a + k.n, 0);
console.log(`INSETS OK  (${EDGES.length} edge rules, ${listed} bottom anchors listed with a reason, every other one cleared)`);
