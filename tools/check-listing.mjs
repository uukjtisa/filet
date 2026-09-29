#!/usr/bin/env node
/**
 * What it costs to turn a folder into a list of rows.
 *
 * The fault this guards against is not a wrong answer, which is why no unit test was ever going
 * to notice it. A row built from seven filesystem calls and a row built from one hold exactly
 * the same values; the only difference is that on a FUSE mount - which is how an app sees
 * `/storage/emulated/0` - seven calls cost seven trips into another process, and a folder of a
 * thousand files takes seconds instead of a tenth of one.
 *
 * So what is checked here is shape rather than behaviour, and each rule names the specific way
 * the old shape came back:
 *
 *  - a row is built from ONE stat, not from a pile of `java.io.File` accessors
 *  - `isDirectory` is not asked twice for the same entry, which is how the old code spent two of
 *    its seven calls on one question
 *  - permission answers are memoised per listing rather than asked per entry
 *  - the pane consumes the listing in chunks, so the first rows can be drawn early
 *  - the pane clears its rows when the folder changes, so the wait does not render as the
 *    previous folder sitting there looking like the tap did nothing
 *
 *   node tools/check-listing.mjs              check
 *   node tools/check-listing.mjs --selftest   prove each rule catches its own fault
 *
 * Prints "LISTING OK" when the shape holds.
 */
import { readFileSync, existsSync } from "node:fs";

const LOCAL = "core-vfs/src/main/java/dev/niccc2007/filet/vfs/provider/LocalProvider.kt";
const STATS = "core-vfs/src/main/java/dev/niccc2007/filet/vfs/provider/Stats.kt";
const CHUNKS = "core-vfs/src/main/java/dev/niccc2007/filet/vfs/ListChunks.kt";
const PROVIDER = "core-vfs/src/main/java/dev/niccc2007/filet/vfs/FileSystemProvider.kt";
const PANE = "app/src/main/java/dev/niccc2007/filet/browser/PaneController.kt";

/**
 * The body of the fast row builder, which is the function whose call count is the whole point.
 *
 * Found by name and brace-matched rather than by a line range: a range would silently start
 * checking a different function the first time anything above it moved.
 */
export function fastBody(src) {
  const at = src.indexOf("private fun File.toNode(");
  if (at < 0) return null;
  const open = src.indexOf("{", at);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}") {
      depth--;
      if (depth === 0) return src.slice(open, i + 1);
    }
  }
  return null;
}

export function rules({ local, stats, chunks, provider, pane }) {
  const found = [];

  // ── one stat per row ──

  const body = fastBody(local);
  if (body === null) {
    found.push("LocalProvider has no File.toNode(..) - the row builder was renamed or removed");
  } else {
    if (!/stats\.of\(/.test(body)) {
      found.push("the row builder does not call stats.of(..): it is back to per-field reads");
    }
    // Each of these is one round trip. On the fast path the stat has already answered all of
    // them, so any occurrence means a call that is being paid for twice.
    for (const acc of ["isDirectory", "lastModified()", "length()"]) {
      if (body.includes(acc)) {
        found.push(`the row builder still calls ${acc} - the stat already answered that`);
      }
    }
    // canRead/canWrite are allowed here ONLY as the no-cache fallback for a one-off stat, which
    // is the `?:` branch. Anything else means the per-entry access calls are back.
    const perm = (body.match(/can(Read|Write)\(\)/g) || []).length;
    if (perm > 0 && !/\?:\s*Perm\(canRead\(\), canWrite\(\)\)/.test(body)) {
      found.push("canRead/canWrite in the row builder outside the single-stat fallback");
    }
  }

  // ── the memoised probe ──

  if (!/class PermProbe/.test(stats)) {
    found.push("PermProbe is gone - permission answers are per-entry again");
  }
  if (!/PermProbe\s*\{/.test(local)) {
    found.push("LocalProvider.list does not build a PermProbe for the listing");
  }
  // The key has to be the attributes, not the path. Keying on the path is a cache that can never
  // hit, which is the same number of syscalls as no cache at all wearing a cache's name.
  if (!/Sig\(StatFacts\.permBits\([^)]*\), *[^,]*\.uid, *[^)]*\.gid\)/.test(stats)) {
    found.push("PermProbe is not keyed on (mode, uid, gid) - a path-keyed cache never hits");
  }

  // ── streaming ──

  if (!/suspend fun list\(path: VPath, onChunk:/.test(provider)) {
    found.push("FileSystemProvider has no chunked list(..) - the contract was removed");
  }
  if (!/override suspend fun list\(path: VPath, onChunk:/.test(local)) {
    found.push("LocalProvider does not override the chunked list - big folders do not stream");
  }
  if (!/fun plan\(total: Int\)/.test(chunks)) {
    found.push("ListChunks.plan is gone - the chunk sizes are hardcoded somewhere");
  }
  // An empty folder that sends no chunk hangs any caller that waits for one before drawing.
  if (!/if \(kids\.isEmpty\(\)\)/.test(local)) {
    found.push("the chunked list does not special-case an empty folder");
  }

  // ── the pane ──

  const streams = (pane.match(/vfs\.list\([^)]*\) \{/g) || []).length;
  if (streams < 2) {
    found.push(
      `only ${streams} of the pane's listings stream - navigateTo and restore both have to`,
    );
  }
  if (/runCatching \{ vfs\.list\((path|target\.cwd)\) \}/.test(pane)) {
    found.push("a pane listing still reads the whole folder before drawing anything");
  }
  // The rows of the folder you just left must not stand in for the one you are waiting for.
  if (!/entries = if \(moved\) emptyList\(\)/.test(pane)) {
    found.push("navigateTo does not clear entries on a move - the old folder stays on screen");
  }

  return found;
}

if (process.argv.includes("--selftest")) {
  const real = {
    local: readFileSync(LOCAL, "utf8"),
    stats: readFileSync(STATS, "utf8"),
    chunks: readFileSync(CHUNKS, "utf8"),
    provider: readFileSync(PROVIDER, "utf8"),
    pane: readFileSync(PANE, "utf8"),
  };

  /** Each case breaks exactly one thing and must be caught. */
  const CASES = [
    ["the row builder loses its stat", (f) => {
      f.local = f.local.replace("stats.of(absolutePath)", "null.also { }");
    }],
    ["isDirectory comes back into the fast row", (f) => {
      f.local = f.local.replace(
        "val dir = StatFacts.isDir(st.mode)",
        "val dir = isDirectory",
      );
    }],
    ["lastModified comes back into the fast row", (f) => {
      f.local = f.local.replace("mtime = st.mtimeMillis,", "mtime = lastModified(),");
    }],
    ["length comes back into the fast row", (f) => {
      f.local = f.local.replace("else st.size,", "else length(),");
    }],
    ["the probe is keyed on the path", (f) => {
      f.stats = f.stats.replace(
        /val sig = Sig\([^\n]*\)/,
        'val sig = Sig(path.hashCode(), st.uid, st.gid)',
      );
    }],
    ["PermProbe is deleted", (f) => {
      f.stats = f.stats.replace("class PermProbe", "class WasPermProbe");
    }],
    ["the listing stops building a probe", (f) => {
      f.local = f.local.replace(/val perms = PermProbe \{/g, "val perms = noProbe(");
    }],
    ["the chunked contract is removed from the provider", (f) => {
      f.provider = f.provider.replace("suspend fun list(path: VPath, onChunk:", "suspend fun listX(path: VPath, onChunk:");
    }],
    ["LocalProvider stops overriding the chunked list", (f) => {
      f.local = f.local.replace("override suspend fun list(path: VPath, onChunk:", "suspend fun listSomething(path: VPath, onChunk:");
    }],
    ["the empty-folder case is dropped", (f) => {
      f.local = f.local.replace("if (kids.isEmpty()) {", "if (false) {");
    }],
    ["the plan is inlined away", (f) => {
      f.chunks = f.chunks.replace("fun plan(total: Int)", "fun planX(total: Int)");
    }],
    ["the pane waits for the whole folder again", (f) => {
      f.pane = f.pane
        .replace(/vfs\.list\(path\) \{ chunk ->/, "runCatching { vfs.list(path) }.onSuccess { chunk ->")
        .replace(/vfs\.list\(target\.cwd\) \{ chunk ->/, "runCatching { vfs.list(target.cwd) }.onSuccess { chunk ->");
    }],
    ["the pane keeps the previous folder's rows", (f) => {
      f.pane = f.pane.replace("entries = if (moved) emptyList()", "entries = if (false) emptyList()");
    }],
  ];

  let bad = 0;
  if (rules(real).length !== 0) {
    console.error("SELFTEST FAILED: the real sources do not pass, so no case means anything");
    for (const p of rules(real)) console.error("  " + p);
    process.exit(1);
  }
  for (const [name, breakIt] of CASES) {
    const f = { ...real };
    breakIt(f);
    if (rules(f).length === 0) {
      console.error(`SELFTEST FAILED: not caught - ${name}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(`LISTING SELFTEST OK  (${CASES.length} negative controls, 1 positive)`);
  process.exit(0);
}

for (const p of [LOCAL, STATS, CHUNKS, PROVIDER, PANE]) {
  if (!existsSync(p)) {
    console.error(`LISTING: ${p} is missing`);
    process.exit(1);
  }
}

const faults = rules({
  local: readFileSync(LOCAL, "utf8"),
  stats: readFileSync(STATS, "utf8"),
  chunks: readFileSync(CHUNKS, "utf8"),
  provider: readFileSync(PROVIDER, "utf8"),
  pane: readFileSync(PANE, "utf8"),
});

if (faults.length) {
  console.error("A listing costs more than it should:\n");
  for (const p of faults) console.error("  " + p);
  process.exit(1);
}

console.log("LISTING OK  (one stat per row, probes memoised per listing, big folders stream)");
