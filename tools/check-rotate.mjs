// ---------------------------------------------------------------------------
//  check-rotate — several addresses per remote are actually reachable, and
//  discovery actually runs.
//
//  Usage:
//    node tools/check-rotate.mjs              check the tree
//    node tools/check-rotate.mjs --selftest   prove the rules catch what they claim
//
//  Two features looked finished and were not, and both failed the same way: a
//  switch wired to a place it can never fire from. Neither shows up in a build,
//  a test of the pieces, or a reading of the diff - only in use.
//
//  RULE 1 — ROTATION SITS WHERE THE FAILURE IS. `URL.openConnection()` does no
//  network; the connect happens at `responseCode` or the first read, in the
//  caller. Rotation used to live in the builder, so a dead address sailed past
//  it and the second address was never tried. Every operation must therefore
//  resolve its connection through the retry wrapper, and the builder must not
//  claim to rotate. `WhereConnectHappensTest` proves the premise.
//
//  RULE 3 — A CHANGE OF NETWORK IS HANDLED, NOT WAITED OUT. An mDNS
//  registration is bound to the interface it was made on, and a discovery
//  listener holds the view it built there. Nothing watched the network, so
//  moving a hosting device from mobile data to Wi-Fi left it announcing an
//  address that no longer existed - recoverable only by stopping and starting
//  hosting by hand, and then by opening a screen until the other device
//  reappeared. One watcher, one signal, three things redone.
//
//  RULE 2 — DISCOVERY IS NOT OWNED BY ONE SCREEN. Addresses are learned from
//  announcements, and the scan was started only by the Remotes screen - which
//  also stopped it on dispose. So "learns the address by itself in the
//  background" only happened on the one screen where it was not needed. The
//  activity must start a scan of its own, and the beacon must count its
//  callers or the screen's dispose stops the app's scan too.
// ---------------------------------------------------------------------------
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const NET = join(ROOT, "core-vfs/src/main/java/dev/niccc2007/filet/vfs/provider/net");
const DAV = join(NET, "WebDavProvider.kt");
const DIAL = join(NET, "NetDial.kt");
const BEACON = join(ROOT, "app/src/main/java/dev/niccc2007/filet/webdav/DavBeacon.kt");
const ACTIVITY = join(ROOT, "app/src/main/java/dev/niccc2007/filet/MainActivity.kt");
const WATCH = join(ROOT, "app/src/main/java/dev/niccc2007/filet/webdav/NetworkWatch.kt");
const GRAPH = join(ROOT, "app/src/main/java/dev/niccc2007/filet/FiletApp.kt");

// Every provider that reaches a saved remote. All four have the addresses field; all four have
// to read it, or it is a switch that does nothing on three of them.
const PROVIDERS = ["WebDavProvider.kt", "SmbProvider.kt", "SftpProvider.kt", "FtpProvider.kt"];

// Comments are blanked rather than removed so every offset stays put, and so a
// file's own prose about the rule cannot satisfy the rule it describes.
const strip = (src) =>
  src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/^([ \t]*)\/\/.*$/gm, (m, i) => i + " ".repeat(m.length - i.length));

const lineOf = (src, index) => src.slice(0, index).split("\n").length;

/**
 * Every `conn(` resolution that is NOT inside an `onAddress` block.
 *
 * Line-scoped on the way in, then brace-walked outward: a call twenty lines
 * below the wrapper that owns it still counts as inside it, and a wrapper in a
 * different function does not.
 */
export function unwrappedConnSites(src) {
  const out = [];
  const re = /\bconn\s*\(/g;
  let m;
  while ((m = re.exec(src))) {
    // The declaration itself.
    const line = src.slice(src.lastIndexOf("\n", m.index) + 1, src.indexOf("\n", m.index));
    if (/fun\s+conn\s*\(/.test(line)) continue;
    // The wrapper's own resolution. It is the one place that is ALLOWED to ask for an address
    // directly - it is the thing doing the retrying - so exempting it by name is the rule, not
    // a hole in it.
    if (enclosingFun(src, m.index) === "onAddress") continue;
    if (!insideOnAddress(src, m.index)) out.push(lineOf(src, m.index));
  }
  return out;
}

/** The name of the nearest function declared before `index`. */
function enclosingFun(src, index) {
  const re = /\bfun\s+(?:<[^>]*>\s*)?([A-Za-z0-9_]+)\s*\(/g;
  let name = null;
  let m;
  while ((m = re.exec(src)) && m.index < index) name = m[1];
  return name;
}

function insideOnAddress(src, index) {
  let depth = 0;
  for (let i = index; i >= 0; i--) {
    const ch = src[i];
    if (ch === "}") depth++;
    else if (ch === "{") {
      if (depth === 0) {
        // The opening brace of the block this call is in. Whose is it?
        const head = src.slice(Math.max(0, i - 160), i);
        if (/onAddress\s*\([^)]*\)\s*$/.test(head)) return true;
        // A `fun` boundary: nothing further out can be the wrapper for this
        // call, because the wrapper would have to be inside the function.
        if (/\bfun\s+[A-Za-z0-9_<>]+\s*\([\s\S]*$/.test(head) && !/onAddress/.test(head)) {
          // Keep climbing only while the brace belongs to a lambda or a block,
          // not to a function declaration.
          if (/\)\s*(:\s*[^={]+)?\s*$/.test(head)) return false;
        }
      } else depth--;
    }
  }
  return false;
}

const fails = [];

if (!process.argv.includes("--selftest")) {
  const dav = strip(readFileSync(DAV, "utf8"));

  // Rule 1a: one dial, shared, and it asks the tested classifier what a failure means.
  const dial = strip(readFileSync(DIAL, "utf8"));
  if (!/inline fun <T> over\(/.test(dial)) fails.push("NetDial — no over(), so nothing dials");
  if (!/NetFailure\.isAddressFailure\(/.test(dial)) {
    fails.push("NetDial — nothing decides whether a failure is the address");
  }
  if (!/onGood\(/.test(dial)) {
    fails.push("NetDial — the address that answered is never remembered, so it is swept for again");
  }
  if (!/private inline fun <T> onAddress\(/.test(dav)) {
    fails.push("WebDavProvider — no onAddress wrapper, so nothing owns address rotation");
  }

  // Rule 1a2: every provider dials. Three of them read only the primary address, which is the
  // half of this bug that was never reported because nobody has an SMB server to test it on.
  for (const name of PROVIDERS) {
    const src = strip(readFileSync(join(NET, name), "utf8"));
    if (!/NetDial\.over\(/.test(src)) {
      fails.push(
        `${name} — never calls NetDial.over, so a remote's extra addresses are offered in the ` +
          "form and read by nothing",
      );
    }
  }

  // Rule 1b: nothing rotates from inside the builder, which does no network.
  const builder = dav.split(/private fun open\(/)[1]?.split(/private fun request\(/)[0] ?? "";
  if (/\brotate\s*\(|\bmarkBad\s*\(/.test(builder)) {
    fails.push(
      "WebDavProvider.open — rotates from the connection BUILDER, which performs no network: " +
        "the connect happens at responseCode, so this can never fire (see WhereConnectHappensTest)",
    );
  }

  // Rule 1c: every operation goes through the wrapper.
  const loose = unwrappedConnSites(dav);
  if (loose.length) {
    fails.push(
      `WebDavProvider — ${loose.length} operation(s) resolve an address outside onAddress ` +
        `(line ${loose.join(", ")}): a dead address there is never rotated past`,
    );
  }

  // Rule 2: discovery has an owner that is not a screen, and is refcounted.
  const beacon = strip(readFileSync(BEACON, "utf8"));
  if (!/scanners/.test(beacon) || !/AtomicInteger/.test(beacon)) {
    fails.push(
      "DavBeacon — startScan/stopScan are not counted, so one caller's stop ends another's scan",
    );
  }
  const activity = strip(readFileSync(ACTIVITY, "utf8"));
  if (!/override fun onStart\(\)/.test(activity) || !/davBeacon\.startScan\(\)/.test(activity)) {
    fails.push(
      "MainActivity — nothing starts discovery for the app, so addresses are only learned " +
        "while the Remotes screen is open",
    );
  }
  if (!/override fun onStop\(\)/.test(activity) || !/davBeacon\.stopScan\(\)/.test(activity)) {
    fails.push("MainActivity — starts a scan and never stops it");
  }

  // Rule 3: something watches the network, and all three stale things are redone.
  const watch = strip(readFileSync(WATCH, "utf8"));
  if (!/registerNetworkCallback\(/.test(watch)) {
    fails.push("NetworkWatch — registers no callback, so a change of network is never noticed");
  }
  if (!/NetIdentity\.changed\(/.test(watch)) {
    fails.push(
      "NetworkWatch — decides for itself what a network change is; that decision is pure and " +
        "tested in NetIdentity, and one association fires several callbacks",
    );
  }
  const graph = strip(readFileSync(GRAPH, "utf8"));
  if (!/netWatch\.moves/.test(graph)) {
    fails.push("FiletApp — nothing collects the network moves, so the watcher reports to nobody");
  }
  for (const [needle, what] of [
    [/readvertiseAll\(\)/, "re-announce the shares, whose records name the old address"],
    [/restartScan\(forget = true\)/, "listen again, since mDNS does not re-announce on request"],
  ]) {
    if (!needle.test(graph)) fails.push(`FiletApp — a network move does not ${what}`);
  }
  if (!/fun readvertiseAll\(/.test(beacon) || !/private val ads[ =]/.test(beacon)) {
    fails.push(
      "DavBeacon — cannot announce a share again: re-registering needs the details it was given, " +
        "and only the listener was kept",
    );
  }
  if (!/RESOLVE_TRIES/.test(beacon)) {
    fails.push(
      "DavBeacon — a failed resolve is dropped; the platform resolves one service at a time, so " +
        "a device announcing several loses all but the first and never reaches the list",
    );
  }
}

if (process.argv.includes("--selftest")) {
  const good = `
    private inline fun <T> onAddress(id: String, path: VPath, block: (NetConnection) -> T): T {
        val c = conn(id, path)
        return block(c)
    }
    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        onAddress(id, path) { c ->
            request(c, remote, "PROPFIND", path = path)
        }
    }
  `;
  const bad = `
    private inline fun <T> onAddress(id: String, path: VPath, block: (NetConnection) -> T): T {
        val c = conn(id, path)
        return block(c)
    }
    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val c = conn(id, path)
        request(c, remote, "PROPFIND", path = path)
    }
  `;
  const cases = [
    ["every op inside the wrapper", good, 0],
    ["an op resolving its own address", bad, 1],
  ];
  let bad_ = 0;
  for (const [name, src, want] of cases) {
    const got = unwrappedConnSites(src).length;
    const ok = got === want;
    if (!ok) bad_++;
    console.log(`  ${ok ? "ok  " : "FAIL"}  ${name} (expected ${want}, found ${got})`);
  }
  if (bad_) {
    console.error("ROTATE SELFTEST FAILED");
    process.exit(1);
  }
  console.log("ROTATE SELFTEST OK");
  process.exit(0);
}

if (fails.length) {
  console.error("ROTATE FAILED:");
  for (const f of fails) console.error("  - " + f);
  process.exit(1);
}
console.log(
  "ROTATE OK  (rotation sits at the response, every operation goes through it, discovery runs " +
    "for the app rather than for one screen, and a change of network re-announces and re-listens)",
);
