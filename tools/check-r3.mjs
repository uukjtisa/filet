#!/usr/bin/env node
/**
 * PLAN.md R3 — "Everything above L0 talks only to the VFS."
 *
 * Storage APIs are allowed ONLY inside the providers, plus a short, reasoned allow-list of
 * files where an Android API genuinely accepts nothing else. Anywhere else they are an
 * architecture violation, and this is the difference between R3 being a rule and R3 being a
 * code review nobody does.
 *
 *   node tools/check-r3.mjs              scan the repo
 *   node tools/check-r3.mjs --selftest   prove the scanner fails on known violations
 *
 * Prints "R3 OK" / "R3 SELFTEST OK" only after every assertion passes.
 */
import { readdirSync, readFileSync, statSync, mkdtempSync, writeFileSync, rmSync, mkdirSync } from "node:fs";
import { join, sep, relative } from "node:path";
import { tmpdir } from "node:os";

/** Symbols that mean "this file touches storage directly". */
const FORBIDDEN = [
  "java.io.File",
  "java.io.RandomAccessFile",
  "java.nio.file.",
  "android.provider.DocumentsContract",
  "android.provider.MediaStore",
  "androidx.documentfile",
  "android.os.Environment",
  "android.os.storage.StorageManager",
];

/**
 * Files that may use the above, and why. Each entry is a design decision, not a fix.
 *
 * Keeping this list short is the point: if it grows, the layering has drifted.
 */
const ALLOWED = [
  // L0 itself. This is what the whole rule exists to contain.
  "core-vfs/src/main/java/dev/niccc2007/filet/vfs/provider/",
  // SQLite opens a database by file handle and nothing else; it is the index's own private
  // file under the app data directory, never a user path.
  "core-index/src/main/java/dev/niccc2007/filet/index/IndexDb.kt",
  // inotify is a kernel facility that takes a real path. The path comes from the PROVIDER
  // via Vfs.osPath - the watcher never builds one.
  "core-index/src/main/java/dev/niccc2007/filet/index/Watchers.kt",
  // The app layer's single documented bridge: app-private working storage, and the
  // FileProvider hand-off that Android requires for anything leaving the process.
  "app/src/main/java/dev/niccc2007/filet/data/AppFiles.kt",
  // The package installer takes a FileProvider URI over a real file and accepts nothing
  // else, so an update has to land on disk before it can be installed. App-private
  // external storage, one filename, never a user path - and the handle stops here rather
  // than travelling up into the view model.
  "app/src/main/java/dev/niccc2007/filet/update/Updater.kt",
  // The release notes' screenshot cache. BitmapFactory decodes from a path or a stream and
  // nothing else, and the two-pass decode that keeps a 4000px screenshot from becoming an OOM
  // has to read the same bytes twice. cacheDir, hashed filenames, never a user path, and the
  // handles stop here - the composable above it only ever sees an ImageBitmap.
  "app/src/main/java/dev/niccc2007/filet/update/NoteImages.kt",
  // apksig, smali and ARSCLib all take java.io.File and refuse streams. Every path this
  // package touches came from Vfs.osPath or from AppFiles.
  "app/src/main/java/dev/niccc2007/filet/apk/",
  // Reconciling the gallery means asking MediaStore what it already holds, and MediaStore is
  // the media index rather than a filesystem - it is asked which rows exist, never used to
  // read or write a file. The paths it returns are compared against what the VFS reported and
  // handed straight to the platform scanner; no file is opened here.
  "app/src/main/java/dev/niccc2007/filet/media/MediaCatchUp.kt",
  // A debug-only diagnostic for the share server, and it cannot use the VFS because the VFS
  // is part of what it exists to watch. Compiles to nothing in a release build, writes a few
  // hundred lines to app-private external storage, one filename, never a user path. It exists
  // because some devices drop an app's own logcat output while carrying the system's, so a
  // fault that reproduces every time can otherwise leave no trace anywhere.
  "app/src/main/java/dev/niccc2007/filet/nearby/ShareTrace.kt",
  // The crash reporter runs when the graph may be the thing that died, so it cannot go
  // through the VFS to save a report. App-private storage, its own directory, text only.
  "app/src/main/java/dev/niccc2007/filet/crash/CrashReport.kt",
  // Reading a zip's central directory needs random access, which a stream cannot give. The
  // path comes from Vfs.osPath and the file is only ever read, never written.
  "app/src/main/java/dev/niccc2007/filet/index/ArchiveFacts.kt",
  // Tests construct temp trees on purpose: that is how the providers get tested at all.
  "core-vfs/src/test/",
  "core-vfs/src/androidTest/",
  "core-index/src/test/",
  "core-index/src/androidTest/",
  "app/src/test/",
  "app/src/androidTest/",
];

const SCAN_ROOTS = ["app/src", "core-vfs/src", "core-index/src"];

function kotlinFiles(dir, out = []) {
  let entries;
  try { entries = readdirSync(dir); } catch { return out; }
  for (const e of entries) {
    const p = join(dir, e);
    let st;
    try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) kotlinFiles(p, out);
    else if (e.endsWith(".kt") || e.endsWith(".java")) out.push(p);
  }
  return out;
}

/**
 * @returns {{file:string, line:number, symbol:string, how:string}[]}
 *
 * Both forms are checked. An import is the obvious one; a fully-qualified reference
 * (`java.io.File(...)` mid-expression) needs no import at all, and a scanner that only reads
 * import lines can be walked straight past. Ask how this rule would be broken, then check
 * for that.
 */
function scan(roots, root = ".") {
  const violations = [];
  for (const r of roots) {
    for (const file of kotlinFiles(join(root, r))) {
      const rel = relative(root, file).split(sep).join("/");
      if (ALLOWED.some((a) => rel.startsWith(a))) continue;
      const lines = readFileSync(file, "utf8").split(/\r?\n/);
      lines.forEach((text, i) => {
        // Comments may name a forbidden symbol while explaining why it is absent, so every
        // comment form is stripped before matching - line, block, and KDoc continuation.
        const code = text
          .replace(/\/\*.*?\*\//g, "")
          .replace(/\/\/.*$/, "")
          .replace(/^\s*\*.*$/, "")
          .replace(/^\s*\/\*.*$/, "");
        const hit = FORBIDDEN.find((f) => code.includes(f));
        if (!hit) return;
        violations.push({
          file: rel,
          line: i + 1,
          symbol: hit,
          how: code.trimStart().startsWith("import ") ? "imports" : "references",
        });
      });
    }
  }
  return violations;
}

function selftest() {
  // A negative assertion is worthless without a positive control: prove the scanner reports
  // the violations it is supposed to catch, in a throwaway tree.
  const tmp = mkdtempSync(join(tmpdir(), "r3-"));
  const fail = (m, extra) => {
    console.error("SELFTEST FAILED: " + m);
    if (extra) console.error(JSON.stringify(extra));
    rmSync(tmp, { recursive: true, force: true });
    process.exit(1);
  };
  try {
    const dir = join(tmp, "app", "src", "main", "java");
    mkdirSync(dir, { recursive: true });

    writeFileSync(join(dir, "Bad.kt"), "import java.io.File\n\nclass Bad\n");
    let found = scan(["app/src"], tmp);
    if (found.length !== 1 || found[0].how !== "imports") fail("did not flag a forbidden import", found);

    // The evasion the first version of this checker missed entirely.
    writeFileSync(join(dir, "Bad.kt"), "class Sneaky {\n  val f = java.io.File(\"/etc/passwd\")\n}\n");
    found = scan(["app/src"], tmp);
    if (found.length !== 1 || found[0].how !== "references") {
      fail("did not flag a fully-qualified reference", found);
    }

    // A comment explaining why a symbol is absent must not itself be a violation.
    writeFileSync(join(dir, "Bad.kt"), "// deliberately no java.io.File here\nclass Fine\n");
    if (scan(["app/src"], tmp).length !== 0) fail("flagged a comment");

    writeFileSync(join(dir, "Bad.kt"), "import kotlin.math.min\n\nclass Fine\n");
    if (scan(["app/src"], tmp).length !== 0) fail("flagged a clean file");

    console.log("R3 SELFTEST OK  (flags imports and fully-qualified uses, ignores comments and clean source)");
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
}

if (process.argv.includes("--selftest")) {
  selftest();
} else {
  const v = scan(SCAN_ROOTS);
  if (v.length) {
    console.error(`R3 VIOLATIONS: ${v.length}`);
    for (const x of v) console.error(`  ${x.file}:${x.line}  ${x.how} ${x.symbol}`);
    console.error("\nStorage APIs belong in a provider, or in the allow-list with a stated reason. See PLAN.md R3.");
    process.exit(1);
  }
  const scanned = SCAN_ROOTS.flatMap((r) => kotlinFiles(r)).length;
  if (scanned === 0) {
    console.error("R3 INCONCLUSIVE: no source files found — the scan proved nothing.");
    process.exit(1);
  }
  console.log(`R3 OK  (${scanned} source files scanned, ${ALLOWED.length} allowed paths, 0 violations)`);
}
