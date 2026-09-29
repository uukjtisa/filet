// ---------------------------------------------------------------------------
//  check-actions — every file action goes through the same pipeline.
//
//  Usage:
//    node tools/check-actions.mjs              check the tree
//    node tools/check-actions.mjs --selftest   prove the rules catch what they claim
//
//  Two rules, both of which have already been broken once:
//
//  1. BYTES MOVE OFF THE CALLING THREAD. A provider declaring
//     `withContext(Dispatchers.IO)` moves only the CONSTRUCTION of a stream.
//     Every write after it, and the close that ends it, run on the caller -
//     which for a drag is `viewModelScope`, the main thread. A local file
//     tolerates that; a socket throws NetworkOnMainThreadException. That is
//     why a copy to a mounted share failed while the same copy between two
//     local folders worked, and neither half looked broken on its own.
//
//  2. A WRITE ASKS BEFORE IT ACTS. Every mutating operation calls the gate
//     first, so a refusal is an answer with words rather than whatever string
//     the backend happened to put in an exception.
//
//  Scope: app code. Providers are exempt from rule 1 because they ARE the
//  dispatch - each one wraps its own body.
// ---------------------------------------------------------------------------
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const APP = join(ROOT, "app/src/main/java");
const OPS = join(APP, "dev/niccc2007/filet/ops/Ops.kt");
const VFS = join(ROOT, "core-vfs/src/main/java/dev/niccc2007/filet/vfs/Vfs.kt");

// Files that run on threads they own, with the reason each one is safe.
const OWN_THREADS = {
  "nearby/HttpServer.kt": "serves on its own socket threads; never touched from a composable",
  "webdav/WebDavServer.kt": "serves each connection on its own pool thread; no composable calls it",
  "script/ScriptEngine.kt": "the Lua interpreter runs on a worker; every call is already blocking",
  "media/Thumbnails.kt": "decodes on the thumbnail pool",
  "bridge/FiletBridgeProvider.kt": "a ContentProvider; Android calls it on a binder thread",
  "apk/SplitInstall.kt": "writes into a PackageInstaller session, not the VFS",
};

const STREAM = /\bvfs\.(openRead|openWrite)\s*\(/g;

// Comments are blanked rather than deleted: every offset stays where it was,
// so a match index still maps to the right line of the real file. They are
// blanked at all because a file's own explanation of the rule would otherwise
// satisfy the rule it explains.
const strip = (src) =>
  src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/^([ \t]*)\/\/.*$/gm, (m, i) => i + " ".repeat(m.length - i.length));

/**
 * Whether the call at `index` is lexically inside a `Dispatchers.IO` block.
 *
 * Walks back through balanced braces rather than looking at a fixed window,
 * because a pump can sit twenty lines below the `withContext` that owns it.
 * Stops climbing at a non-suspend `fun`: nothing above that can be a coroutine,
 * so a dispatch found further out would not apply to it.
 */
function dispatched(src, index) {
  const before = src.slice(0, index);
  let depth = 0;
  for (let i = before.length - 1; i >= 0; i--) {
    const c = before[i];
    if (c === "}") depth++;
    else if (c === "{") {
      if (depth === 0) {
        // Only the line the brace sits on decides what the brace belongs to. A
        // wider window let a `withContext` several lines above be read as owning a
        // plain `fun` nested inside it - the one case where a dispatch does NOT
        // reach the code in question.
        const line = before.slice(before.lastIndexOf("\n", i) + 1, i);
        if (/Dispatchers\.IO/.test(line)) return true;
        if (/\bfun\s+[A-Za-z_]/.test(line) && !/\bsuspend\s+fun/.test(line)) return false;
      } else depth--;
    }
  }
  return false;
}

function walk(dir, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (e.endsWith(".kt")) out.push(p);
  }
  return out;
}

// --- selftest -------------------------------------------------------------
if (process.argv.includes("--selftest")) {
  // [name, source, should the detector call it dispatched]
  const CASES = [
    [
      "a bare pump in a suspend fun is caught",
      "suspend fun go() {\n  vfs.openWrite(d).use { }\n}",
      false,
    ],
    [
      "a pump inside withContext is fine",
      "suspend fun go() {\n  withContext(Dispatchers.IO) {\n    vfs.openWrite(d).use { }\n  }\n}",
      true,
    ],
    [
      "a pump twenty lines below its withContext is still fine",
      "suspend fun go() {\n  withContext(Dispatchers.IO) {\n" +
        "    val x = 1\n".repeat(20) +
        "    vfs.openRead(d).use { }\n  }\n}",
      true,
    ],
    [
      "a dispatch on a SIBLING block does not count",
      "suspend fun go() {\n  withContext(Dispatchers.IO) { val a = 1 }\n  vfs.openRead(d).use { }\n}",
      false,
    ],
    [
      "a dispatch outside a plain fun does not reach into it",
      "suspend fun outer() {\n  withContext(Dispatchers.IO) {\n    fun inner() {\n" +
        "      vfs.openRead(d).use { }\n    }\n  }\n}",
      false,
    ],
    [
      "runBlocking that names IO counts",
      "fun go() = runBlocking(Dispatchers.IO) {\n  vfs.openRead(d)\n}",
      true,
    ],
    [
      "runBlocking that names no dispatcher is not a dispatch",
      "fun go() = runBlocking {\n  vfs.openRead(d)\n}",
      false,
    ],
    [
      "a nested block inside the dispatch is still inside it",
      "suspend fun go() {\n  withContext(Dispatchers.IO) {\n    items.forEach {\n" +
        "      vfs.openWrite(d).use { }\n    }\n  }\n}",
      true,
    ],
    [
      "a commented-out dispatch does not count",
      "suspend fun go() {\n  // withContext(Dispatchers.IO)\n  vfs.openRead(d).use { }\n}",
      false,
    ],
  ];

  let bad = 0;
  for (const [name, src, want] of CASES) {
    const s = strip(src);
    const m = [...s.matchAll(STREAM)][0];
    if (!m) {
      console.error(`  MISS  ${name} — the sample has no stream call`);
      bad++;
      continue;
    }
    if (dispatched(s, m.index) !== want) {
      console.error(`  FAIL  ${name} — wanted ${want ? "dispatched" : "caught"}, got the other`);
      bad++;
    }
  }

  // Rule 2's shape checks, each run against source that should trip it.
  const SHAPE = [
    ["a gate that was removed", "suspend fun copy(a: Int) { batch() }", (s) => !/permit\(/.test(s)],
    ["a refusal reported as a toast", "onFailure { toast(readable(it)) }", (s) => !/showDenial/.test(s)],
    [
      "a move that only checks one end",
      "suspend fun move() { permit(FileAction.CREATE_FILE, into) }",
      (s) => !/FileAction\.DELETE/.test(s),
    ],
    [
      "a wire refusal never classified",
      "runCatching { }.onFailure { failed += it }",
      (s) => !/ActionGate\.fromFailure\(/.test(s),
    ],
  ];
  for (const [name, sample, trips] of SHAPE) {
    if (!trips(sample)) {
      console.error(`  FAIL  shape control did not trip: ${name}`);
      bad++;
    }
  }

  if (bad) {
    console.error(`ACTIONS SELFTEST FAILED (${bad})`);
    process.exit(1);
  }
  console.log(
    `ACTIONS SELFTEST OK  (${CASES.length} dispatch cases, ${SHAPE.length} shape controls)`,
  );
  process.exit(0);
}

// --- rule 1: stream work is dispatched ------------------------------------
const fails = [];
let streamSites = 0;
let exempted = 0;

for (const file of walk(APP)) {
  const short = relative(APP, file).replace(/\\/g, "/").replace("dev/niccc2007/filet/", "");
  const src = strip(readFileSync(file, "utf8"));
  for (const m of src.matchAll(STREAM)) {
    streamSites++;
    if (OWN_THREADS[short]) {
      exempted++;
      continue;
    }
    if (!dispatched(src, m.index)) {
      const line = src.slice(0, m.index).split("\n").length;
      fails.push(`${short}:${line} — ${m[0]} not inside withContext(Dispatchers.IO)`);
    }
  }
}

// The VFS itself holds the one copy loop both streams pass through.
const vfs = strip(readFileSync(VFS, "utf8"));
if (!/withContext\(Dispatchers\.IO\)[\s\S]{0,400}?counter\.pump/.test(vfs)) {
  fails.push("Vfs.copyNode — the copy pump is not on the IO dispatcher");
}
if (!/fun\s+isRemote\s*\(/.test(vfs)) {
  fails.push("Vfs — no isRemote; the pipeline cannot detect a network place");
}
if (!/fun\s+permit\s*\(/.test(vfs)) fails.push("Vfs — no permit; there is no single place to ask");

// --- rule 2: mutating operations ask first --------------------------------
const ops = strip(readFileSync(OPS, "utf8"));
for (const [fn, action] of [
  ["copy", "CREATE_FILE"],
  ["move", "CREATE_FILE"],
  ["delete", "DELETE"],
  ["compress", "CREATE_FILE"],
]) {
  const body = ops.split(new RegExp(`suspend fun ${fn}\\(`))[1];
  if (!body) {
    fails.push(`Ops.${fn} — not found`);
    continue;
  }
  const head = body.slice(0, 900);
  if (!/permit\(/.test(head)) fails.push(`Ops.${fn} — does not call permit() before acting`);
  else if (!head.includes(`FileAction.${action}`)) {
    fails.push(`Ops.${fn} — gates on the wrong action (wanted FileAction.${action})`);
  }
}
if (!/ActionGate\.fromFailure\(/.test(ops)) {
  fails.push("Ops — a refusal that arrives from the wire is never classified");
}
// A move must check both ends: somewhere to put it, and permission to take it.
const moveBody = ops.split(/suspend fun move\(/)[1]?.slice(0, 900) ?? "";
if (!/FileAction\.DELETE/.test(moveBody)) {
  fails.push("Ops.move — checks the destination but not permission to remove the source");
}

// --- rule 2b: a refusal reaches a dialogue, not a toast -------------------
const vm = strip(readFileSync(join(APP, "dev/niccc2007/filet/browser/BrowserViewModel.kt"), "utf8"));
if (!/fun showDenial\(/.test(vm)) fails.push("BrowserViewModel — no showDenial");
if (!/denied\s*:\s*dev\.niccc2007\.filet\.vfs\.Denial\?/.test(vm)) {
  fails.push("BrowserViewModel — the state has nowhere to hold a refusal");
}
// Extraction lives in its own class and is the one that writes the most files, so a
// refusal found halfway through is the most expensive one to discover late.
const ext = strip(readFileSync(join(APP, "dev/niccc2007/filet/ops/ExtractOps.kt"), "utf8"));
if (!/vfs\.permit\(FileAction\.CREATE_FILE/.test(ext)) {
  fails.push("ExtractOps.run — writes without asking the destination first");
}
if (!/ActionGate\.fromFailure\(/.test(ext)) {
  fails.push("ExtractOps.run — a refusal from the wire is never classified");
}

const dialogs = strip(readFileSync(join(APP, "dev/niccc2007/filet/browser/Dialogs.kt"), "utf8"));
if (!/fun DeniedDialog\(/.test(dialogs)) fails.push("Dialogs — no DeniedDialog");
if (!/denied\?\.let/.test(dialogs)) fails.push("Dialogs — DeniedDialog is defined but never shown");

// The three that used to speak to the VFS directly and report in a toast.
for (const fn of ["rename", "createFolder", "createFile"]) {
  const body = vm.split(new RegExp(`fun ${fn}\\(`))[1]?.slice(0, 1200) ?? "";
  if (!/runAction\(/.test(body)) fails.push(`BrowserViewModel.${fn} — bypasses runAction`);
}

if (fails.length) {
  console.error("ACTIONS FAILED:");
  for (const f of fails) console.error("  - " + f);
  process.exit(1);
}
console.log(
  `ACTIONS OK  (${streamSites} stream site(s), ${exempted} on threads they own; every copy pump ` +
    `dispatched, every mutating op gated, and a refusal reaches a dialogue)`,
);
