#!/usr/bin/env node
/**
 * One table of file types, and no second opinion.
 *
 * Every extension Filet knows is declared once, in `FileTypes` (core-vfs, vfs/types). Before
 * that table the same picture, video and sound extensions were listed in more than ten places,
 * and the lists disagreed: `.jfif` was a JPEG to the rotation code and unknown to the file
 * kind, so it got no thumbnail and no picture viewer. Nothing failed to compile and nothing
 * looked broken - the only symptom was a file type that quietly did less than its neighbours.
 *
 * So this fails when Kotlin source outside the table holds its own media extension list:
 *
 *   - a collection literal (`setOf`, `listOf`, `arrayOf`, `mapOf`, ...) naming two or more
 *     picture, video or sound extensions, or
 *   - a `when` whose branches test two or more of them (`"png" -> ...`), which is the same list
 *     written as a lookup.
 *
 * A short allow-list holds the places where a list is a CAPABILITY rather than a statement of
 * what a type is - which formats the tag writer can edit, which rows Settings shows first.
 * Each has a reason, and the list is meant to stay short.
 *
 *   node tools/check-filetypes.mjs              scan the repo
 *   node tools/check-filetypes.mjs --selftest   prove the scanner fails on known violations
 *
 * Prints "FILETYPES OK" / "FILETYPES SELFTEST OK" only after every assertion passes.
 */
import { readdirSync, readFileSync, statSync, mkdtempSync, writeFileSync, rmSync, mkdirSync } from "node:fs";
import { join, sep, relative } from "node:path";
import { tmpdir } from "node:os";

/** The extensions a private list would be made of. Two of them together is a list. */
const MEDIA = new Set([
  "jpg", "jpeg", "jpe", "jfif", "jif", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng",
  "tif", "tiff", "ico", "svg",
  "mp4", "m4v", "mkv", "webm", "mov", "3gp", "avi", "ts", "mpg", "mpeg", "flv", "wmv",
  "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "mid", "midi", "wma", "mka",
]);

const SCAN_ROOTS = ["app/src/main", "core-vfs/src/main", "core-index/src/main"];

/** Files that may keep a list, and why. */
const ALLOWED = [
  // The table itself.
  "core-vfs/src/main/java/dev/niccc2007/filet/vfs/types/FileTypes.kt",
  // Which formats the tag writer can edit and embed a cover in: a capability of that writer,
  // decided by what its code can parse, not by what the types are.
  "app/src/main/java/dev/niccc2007/filet/metadata/MetadataSupport.kt",
  // The pictures a cover can be - the same capability as above, seen from the picker.
  "app/src/main/java/dev/niccc2007/filet/pick/InAppPick.kt",
  // Which rows Settings > Default openers shows first: a choice of display, not a type
  // declaration. MimeTableTest holds every row to the table's kind for its heading.
  "app/src/main/java/dev/niccc2007/filet/handlers/DefaultOpeners.kt",
];

function kotlinFiles(dir) {
  let out = [];
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const e of entries) {
    const p = join(dir, e);
    const s = statSync(p);
    if (s.isDirectory()) out = out.concat(kotlinFiles(p));
    else if (e.endsWith(".kt")) out.push(p);
  }
  return out;
}

/** Source with every comment blanked, line breaks kept so line numbers still mean something. */
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/\/\/[^\n]*/g, "");
}

const lineAt = (src, index) => src.slice(0, index).split("\n").length;

/** The media extensions quoted, as whole literals, in [text]. */
function mediaIn(text) {
  const found = new Set();
  for (const m of text.matchAll(/"\.?([A-Za-z0-9]+)"/g)) {
    const e = m[1].toLowerCase();
    if (MEDIA.has(e)) found.add(e);
  }
  return found;
}

/** The text between the bracket at [open] and its partner. */
function bracketed(src, open, left, right) {
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === left) depth++;
    else if (src[i] === right && --depth === 0) return src.slice(open + 1, i);
  }
  return src.slice(open + 1);
}

function scanSource(src) {
  const code = stripComments(src);
  const hits = [];
  // Collection literals.
  for (const m of code.matchAll(/\b(setOf|listOf|arrayOf|mutableSetOf|mutableListOf|hashSetOf|linkedSetOf|mapOf|hashMapOf|mutableMapOf)\s*\(/g)) {
    const inner = bracketed(code, m.index + m[0].length - 1, "(", ")");
    const found = mediaIn(inner);
    if (found.size >= 2) hits.push({ line: lineAt(code, m.index), how: m[1], exts: [...found] });
  }
  // `when` tables: branch conditions made of quoted extensions.
  for (const m of code.matchAll(/\bwhen\s*(\([^)]*\))?\s*\{/g)) {
    const body = bracketed(code, m.index + m[0].length - 1, "{", "}");
    const found = new Set();
    for (const b of body.matchAll(/^\s*((?:"[^"\n]*"\s*,\s*)*"[^"\n]*")\s*->/gm)) {
      for (const e of mediaIn(b[1])) found.add(e);
    }
    if (found.size >= 2) hits.push({ line: lineAt(code, m.index), how: "when", exts: [...found] });
  }
  return hits;
}

function scan(roots, root = ".") {
  const violations = [];
  let scanned = 0;
  for (const r of roots) {
    for (const file of kotlinFiles(join(root, r))) {
      scanned++;
      const rel = relative(root, file).split(sep).join("/");
      if (ALLOWED.includes(rel)) continue;
      for (const h of scanSource(readFileSync(file, "utf8"))) violations.push({ file: rel, ...h });
    }
  }
  return { violations, scanned };
}

function selftest() {
  const tmp = mkdtempSync(join(tmpdir(), "filetypes-"));
  const fail = (m, extra) => {
    console.error("SELFTEST FAILED: " + m);
    if (extra) console.error(JSON.stringify(extra));
    rmSync(tmp, { recursive: true, force: true });
    process.exit(1);
  };
  try {
    const dir = join(tmp, "app", "src", "main", "java");
    mkdirSync(dir, { recursive: true });
    const file = join(dir, "Bad.kt");
    const run = () => scan(["app/src/main"], tmp).violations;

    // The shape every one of the old lists had.
    writeFileSync(file, 'object Bad {\n  private val IMAGE = setOf(\n    "jpg", "jpeg",\n    "png",\n  )\n}\n');
    let v = run();
    if (v.length !== 1 || v[0].how !== "setOf" || v[0].line !== 2) fail("did not flag a multi-line set of picture extensions", v);

    // Two is already a list: the EXIF check was `in setOf("jpg", "jpeg")`, and it was the one
    // that forgot `.jfif`.
    writeFileSync(file, 'fun f(e: String) = e in setOf("jpg", "jpeg")\n');
    if (run().length !== 1) fail("did not flag an inline two-item set");

    // The same list written as a lookup.
    writeFileSync(file, 'fun mime(e: String) = when (e) {\n  "png" -> "image/png"\n  "jpg", "jpeg" -> "image/jpeg"\n  else -> null\n}\n');
    v = run();
    if (v.length !== 1 || v[0].how !== "when") fail("did not flag a when table", v);

    // A comment naming the old list is history, not a list.
    writeFileSync(file, '// it was setOf("jpg", "png", "gif") here\n/* and when { "png" -> 1; "gif" -> 2 } */\nclass Fine\n');
    if (run().length !== 0) fail("flagged a comment", run());

    // One media extension beside other things is not a type list.
    writeFileSync(file, 'val x = setOf("apk", "xapk", "zip")\nval y = setOf("png", "txt")\nfun g(e: String) = when (e) { "mp4" -> 1; else -> 0 }\n');
    if (run().length !== 0) fail("flagged a list that is not a media list", run());

    // A media extension that is a branch's RESULT, not its condition, is not a lookup by it.
    writeFileSync(file, 'fun h(png: Boolean) = when { png -> "png"\n  else -> "jpg" }\n');
    if (run().length !== 0) fail("flagged branch results as conditions", run());

    console.log("FILETYPES SELFTEST OK  (flags sets, inline sets and when tables; ignores comments, mixed lists and results)");
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
}

if (process.argv.includes("--selftest")) {
  selftest();
} else {
  const { violations, scanned } = scan(SCAN_ROOTS);
  if (scanned === 0) {
    console.error("FILETYPES INCONCLUSIVE: no source files found - the scan proved nothing.");
    process.exit(1);
  }
  if (violations.length) {
    console.error(`FILETYPES VIOLATIONS: ${violations.length}`);
    for (const x of violations) console.error(`  ${x.file}:${x.line}  ${x.how} lists ${x.exts.join(", ")}`);
    console.error("\nAn extension belongs in FileTypes (core-vfs, vfs/types), read from there - or in the allow-list with a stated reason.");
    process.exit(1);
  }
  console.log(`FILETYPES OK  (${scanned} source files scanned, ${ALLOWED.length} allowed, 0 private media lists)`);
}
