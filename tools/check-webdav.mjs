#!/usr/bin/env node
/**
 * Hosting this phone over WebDAV, checked where it can be checked statically.
 *
 * Most of this component is covered by unit tests - path resolution, the XML, ranges and locks
 * each have their own. What tests cannot see is the set of promises the feature makes as a
 * whole, and those are exactly the ones that decay: a verb quietly dropped from the Allow
 * header, a write path that stops asking whether writing is on, a warning removed from the
 * card because it read like pedantry.
 *
 * The three warnings are checked by name and that is deliberate. Each is the only explanation
 * for something that otherwise looks exactly like a broken file or a broken app:
 *
 *  - Windows stops WebDAV downloads at 50 MB until a registry value is raised. A large file
 *    fails at precisely that size with no useful error anywhere.
 *  - Windows needs its WebClient service running, and when it is not the address simply does
 *    not open.
 *  - Nothing leaves the network and no service is involved. The phone is the server, which
 *    is why it costs nothing. The wording has moved; the promise is what is checked.
 *
 *   node tools/check-webdav.mjs              check
 *   node tools/check-webdav.mjs --selftest   prove it catches each promise being dropped
 *
 * Prints "WEBDAV OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";

const SRC = {
  server: "app/src/main/java/dev/niccc2007/filet/webdav/WebDavServer.kt",
  path: "app/src/main/java/dev/niccc2007/filet/webdav/DavPath.kt",
  xml: "app/src/main/java/dev/niccc2007/filet/webdav/DavXml.kt",
  locks: "app/src/main/java/dev/niccc2007/filet/webdav/DavLocks.kt",
  range: "app/src/main/java/dev/niccc2007/filet/webdav/DavRange.kt",
  card: "app/src/main/java/dev/niccc2007/filet/remotes/HostingCard.kt",
  screen: "app/src/main/java/dev/niccc2007/filet/remotes/RemotesScreen.kt",
};

/** The verbs Explorer needs before it will mount a drive rather than open a web page. */
const VERBS = [
  "OPTIONS", "GET", "HEAD", "PROPFIND", "PROPPATCH",
  "PUT", "DELETE", "MKCOL", "MOVE", "COPY", "LOCK", "UNLOCK",
];

/** Verbs that write. Every one must be behind the writable switch. */
const WRITE_VERBS = ["PUT", "DELETE", "MKCOL", "MOVE", "COPY"];

export function problems(src) {
  const found = [];
  const server = src.server;

  if (!server) return ["the WebDAV server is missing entirely"];

  // ── the protocol Explorer actually requires ──────────────────────────────────────────
  for (const verb of VERBS) {
    if (!new RegExp(`"${verb}"`).test(server)) {
      found.push(`the server never handles ${verb}, which Explorer sends`);
    }
    if (!new RegExp(`Allow:[^\\n]*${verb}`).test(server)) {
      found.push(`${verb} is missing from the Allow header`);
    }
  }
  // Class 2, not class 1. Windows mounts a class-1 share read-only however permissive
  // everything else about it is, so this single digit decides whether writing works at all.
  if (!/DAV: 1, 2/.test(server)) {
    found.push("the server does not advertise DAV class 2, so Windows mounts it read-only");
  }
  if (!/MS-Author-Via: DAV/.test(server)) {
    found.push("MS-Author-Via is missing, which Explorer uses to pick its WebDAV client");
  }
  if (!/207/.test(server)) found.push("nothing answers 207 Multi-Status");
  // OPTIONS has to be answered before the path is resolved: Explorer sends it at the server
  // root to find out whether WebDAV is spoken, and a 403 there ends the conversation.
  if (!/if \(method == "OPTIONS"\) return options\(out\)/.test(server)) {
    found.push("OPTIONS is answered after path resolution, so Explorer never gets to the mount");
  }

  // ── read-only by default ─────────────────────────────────────────────────────────────
  if (!/WRITE_METHODS/.test(server)) {
    found.push("there is no set of write methods, so nothing can be gated on writing");
  } else {
    const line = server.match(/WRITE_METHODS = setOf\(([^)]*)\)/);
    if (!line) {
      found.push("WRITE_METHODS is not a plain set this can read");
    } else {
      for (const verb of WRITE_VERBS) {
        if (!line[1].includes(`"${verb}"`)) {
          found.push(`${verb} writes but is not in WRITE_METHODS, so it ignores the read-only switch`);
        }
      }
    }
  }
  if (!/needsWrite && !writable.*return status\(out, 403/s.test(server)) {
    found.push("a write is not refused when writing is off");
  }
  if (!/@Volatile var writable: Boolean = false/.test(server)) {
    found.push("writing is not off by default");
  }

  // ── every path goes through the tested resolver ──────────────────────────────────────
  if (!/DavPath\.resolve\(target, code\)/.test(server)) {
    found.push("the request path does not go through DavPath");
  }
  // The Destination header is a second way in and it was the easy one to forget.
  if (!/DavPath\.resolve\(destPath, code\)/.test(server)) {
    found.push("MOVE and COPY do not resolve their Destination header, so a write can escape the share");
  }
  if (!/constantTimeEquals/.test(src.path)) {
    found.push("the access code is compared in a way that leaks where it stopped matching");
  }
  if (!/return Resolved\.Refused/.test(src.path) || !/"\.\."/.test(src.path)) {
    found.push("DavPath does not refuse traversal");
  }

  // ── the port it can actually bind ────────────────────────────────────────────────────
  const port = server.match(/DEFAULT_PORT = (\d+)/);
  if (!port) {
    found.push("no default port is declared");
  } else if (Number(port[1]) < 1024) {
    // Not a style point: Android forbids a privileged bind without root, so a port below
    // 1024 means the feature cannot start at all on the device it is written for.
    found.push(`port ${port[1]} is privileged - Android cannot bind it without root`);
  }
  if (/445/.test(port ? port[1] : "")) found.push("445 is SMB's port and cannot be bound");

  // ── the card, and the three warnings that are load-bearing ───────────────────────────
  if (!src.card) {
    found.push("the hosting card is missing, so the feature has no surface");
  } else {
    if (!/DavWWWRoot/.test(src.card)) {
      found.push("the card does not print the UNC form, so the drive cannot be mapped in Explorer");
    }
    if (!/@\$\{dav\.port\}/.test(src.card)) {
      found.push("the UNC address carries no port, and Explorer will not reach a non-default one");
    }
    for (const [what, re] of [
      ["the 50 MB Windows download limit", /50 MB/],
      ["the FileSizeLimitInBytes registry value by name", /FileSizeLimitInBytes/],
      ["the WebClient service", /WebClient/],
      // The promise, not the sentence it was first written in. The original wording read as
      // a slogan and was cut; what has to survive is the fact that this is peer to peer.
      ["that nothing leaves the network and no service is involved",
        /nothing leaves your network|nothing is hosted for you|no account anywhere/i],
    ]) {
      if (!re.test(src.card)) {
        found.push(`the card no longer explains ${what}`);
      }
    }
  }
  if (!/HostingCard\(vm\)/.test(src.screen)) {
    found.push("the hosting card is never drawn on the Remotes screen");
  }

  // ── the supporting pieces exist and are used ─────────────────────────────────────────
  if (!/DavRange\.parse/.test(server)) found.push("Range is not honoured, so large files cannot resume");
  if (!/DavLocks/.test(server)) found.push("locking is absent, so Explorer will not write");
  if (!/MAX_TIMEOUT_SECONDS/.test(src.locks)) {
    found.push("locks have no maximum timeout, so a vanished client can hold one forever");
  }
  if (!/expiresAt/.test(src.locks)) found.push("locks never expire");

  return found;
}

function read() {
  const out = {};
  for (const [k, p] of Object.entries(SRC)) out[k] = existsSync(p) ? readFileSync(p, "utf8") : "";
  return out;
}

if (process.argv.includes("--selftest")) {
  const good = read();
  const base = problems(good);
  if (base.length) {
    console.error("SELFTEST cannot run: the tree does not pass its own check");
    for (const p of base) console.error("  " + p);
    process.exit(1);
  }
  const swap = (key, from, to) => {
    const c = { ...good };
    c[key] = c[key].split(from).join(to);
    if (c[key] === good[key]) throw new Error(`mutation did not apply: ${from}`);
    return c;
  };

  const CASES = [
    ["LOCK dropped", () => swap("server", '"LOCK" ->', '"NOPE" ->')],
    ["PROPFIND dropped from Allow", () => swap("server", "PROPFIND, PROPPATCH", "PROPPATCH")],
    ["class 1 instead of class 2", () => swap("server", "DAV: 1, 2", "DAV: 1")],
    ["MS-Author-Via removed", () => swap("server", "MS-Author-Via: DAV", "X-Nothing: 1")],
    ["OPTIONS moved behind the path check", () =>
      swap("server", 'if (method == "OPTIONS") return options(out)', "// moved")],
    ["a write verb slipping out of the gated set", () =>
      swap("server", '"MKCOL", "MOVE", "COPY", "PROPPATCH"', '"MOVE", "COPY", "PROPPATCH"')],
    ["writing on by default", () =>
      swap("server", "@Volatile var writable: Boolean = false", "@Volatile var writable: Boolean = true")],
    ["the Destination header bypassing the resolver", () =>
      swap("server", "DavPath.resolve(destPath, code)", "DavPath.Resolved.Ok(destPath)")],
    ["a privileged port", () => swap("server", "DEFAULT_PORT = 8321", "DEFAULT_PORT = 445")],
    ["the constant-time compare removed", () => swap("path", "constantTimeEquals", "plainEquals")],
    ["the UNC form dropped from the card", () => swap("card", "DavWWWRoot", "NotTheMarker")],
    ["the 50 MB warning removed", () => swap("card", "50 MB", "some size")],
    ["the WebClient warning removed", () => swap("card", "WebClient", "TheService")],
    ["the no-service promise removed", () => {
      const c = { ...good };
      c.card = c.card
        .replace(/nothing leaves your network/gi, "x")
        .replace(/nothing is hosted for you/gi, "x")
        .replace(/no account anywhere/gi, "x");
      if (c.card === good.card) throw new Error("mutation did not apply: the no-service promise");
      return c;
    }],
    ["the card never drawn", () => swap("screen", "HostingCard(vm)", "Spacer(Modifier)")],
    ["Range support dropped", () => swap("server", "DavRange.parse", "noRange")],
    ["locks made immortal", () => swap("locks", "expiresAt", "neverExpires")],
  ];

  let bad = 0;
  for (const [name, mutate] of CASES) {
    if (problems(mutate()).length === 0) {
      console.error(`SELFTEST FAILED: ${name} — not caught`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(`WEBDAV SELFTEST OK  (${CASES.length} negative controls, 1 positive)`);
  process.exit(0);
}

const found = problems(read());
if (found.length) {
  console.error("Hosting does not hold up:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
console.log(
  `WEBDAV OK  (${VERBS.length} verbs, class 2, read-only by default, ` +
    `every path through the tested resolver, and the three warnings still on the card)`,
);
