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
  shares: "app/src/main/java/dev/niccc2007/filet/webdav/DavShare.kt",
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

  // Source with comments stripped, for assertions about CODE.
  //
  // An earlier version of the threading check failed on the comment that EXPLAINS the bug it
  // guards, because that comment names the call it replaced. A checker matching prose rather
  // than code is a checker that has stopped meaning anything.
  const code = server
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/(^|[^:])\/\/[^\n]*/g, "$1 ");

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
  if (!/needsWrite && !sh\.writable.*return status\(out, 403/s.test(server)) {
    found.push("a write is not refused when writing is off");
  }
  // Off by default moved, and got stronger on the way. It used to be a field initialiser; now
  // several shares each carry their own flag, so the guarantee is that the DATA CLASS defaults to
  // false and that loading a stored share forces it false regardless of what was written. Checking
  // only the field initialiser would now pass a build that happily restored writable=true.
  if (!/val writable: Boolean = false/.test(src.shares)) {
    found.push("writing is not off by default for a new share");
  }
  // Writing PERSISTS now, deliberately - it used to load as false always, which made the
  // editor's toggle a dead switch for any share that starts itself with the app. The safety it
  // used to provide is paid in visibility instead, so that is what gets asserted: a share that
  // will accept changes has to SAY so where somebody reads it.
  if (!/changes allowed/.test(src.card)) {
    found.push("the card does not say when a share will accept changes, so a writable share can be up unannounced");
  }

  // ── a PUT body belongs to the handler, never to the connection loop ──────────────────
  //
  // This was data loss with a size threshold. The loop buffered any body under 256 KB - PUT
  // included - and `put()` then read from the drained stream AFTER truncating the target. Files
  // under 256 KB were emptied; files over it saved correctly, which is why it read as a
  // permissions fault rather than a broken write.
  if (!/DavBody\.plan\(method, headers, MAX_BODY_READ\)/.test(code)) {
    found.push("the connection loop decides for itself how much body to read, so it can eat a PUT body again");
  }
  if (/method != "PUT"/.test(code)) {
    found.push("the loop still special-cases PUT by name rather than asking DavBody who owns the body");
  }
  // A failed or short transfer must not be able to destroy the file that was already there.
  if (!/\.filet-part-/.test(code)) {
    found.push("PUT writes straight to the target, so a dropped connection replaces a good file with a truncated one");
  }
  if (!/written == declared/.test(code)) {
    found.push("a short body is accepted as a complete write, so a truncated file is reported as success");
  }
  // A refused write still has its body on the wire; leaving it there means the next request line
  // is read out of the file contents.
  if (!/drainAfterRefusal/.test(code)) {
    found.push("a refused write leaves its body on the connection, desynchronising the next request");
  }

  // ── every path goes through the tested resolver ──────────────────────────────────────
  if (!/DavPath\.resolve\(target, live\.values\.map \{ it\.code \}\)/.test(server)) {
    found.push("the request path does not go through DavPath");
  }
  // And the code that matched has to select the share, or every request would be served from
  // whichever root happened to be first.
  if (!/live\.values\.firstOrNull \{ it\.code == r\.code \}/.test(server)) {
    found.push("the resolved code does not select the share, so a request could reach another share's root");
  }
  // The Destination header is a second way in and it was the easy one to forget. It resolves
  // against THIS share's code alone: against the live set, a MOVE could write across a share
  // boundary using nothing but a header.
  if (!/DavPath\.resolve\(destPath, sh\.code\)/.test(server)) {
    found.push("MOVE and COPY do not resolve their Destination header, so a write can escape the share");
  }
  // Traffic is attributed after the code is known. Before it, one share's activity would reset
  // every other share's idle clock, and a wrong-code probe would hold all of them open.
  if (!/sh\.lastActivity\.set\(System\.currentTimeMillis\(\)\)/.test(server)) {
    found.push("activity is not attributed to the share that was addressed, so the per-share idle clock means nothing");
  }
  // A share that opens itself needs a fixed code, or it comes up on a new address every launch
  // and the switch cannot do the thing it says it does.
  if (!/fun canAutoStart\(share: DavShare\): Boolean = !share\.code\.isNullOrBlank\(\)/.test(src.shares)) {
    found.push("a share can open itself without a pinned code, so its address moves every launch");
  }
  // ── connections must not queue behind each other ─────────────────────────────────────
  //
  // Measured against the code with comments removed. The first version of this check failed on
  // the comment that EXPLAINS the bug, because it names the call it replaced - a checker reading
  // prose instead of code, which is the same way a checker stops meaning anything.
  //
  //
  // The fault this catches presented as "the network is slow" and was neither the network nor
  // the protocol: a fixed pool of four, minus one eaten permanently by the accept loop, served
  // THREE connections. Windows opens a new connection per request and leaves them open, so the
  // fourth onwards waited for a 30s socket timeout. Listing a six-entry folder took 59,998ms in
  // Explorer while the same PROPFIND took 34ms over curl.
  if (/newFixedThreadPool/.test(code)) {
    found.push("the connection pool is fixed-size, so connections queue behind each other and a listing waits for a socket timeout");
  }
  if (!/SynchronousQueue/.test(code)) {
    found.push("the connection pool does not hand work straight to a thread, so connections can be queued rather than served");
  }
  // The accept loop on a worker is half the bug and does not show up as a pool size.
  if (/pool\.execute \{ accept\(/.test(code)) {
    found.push("the accept loop runs on the connection pool, so it consumes a worker for the life of the server");
  }
  if (!/Thread\(\{ accept\(s, mine\) \}, "filet-dav-accept"\)/.test(code)) {
    found.push("the accept loop is not on its own thread");
  }
  // An idle keep-alive connection holding a worker for the full timeout is what made three
  // workers behave like none.
  if (!/socket\.soTimeout = KEEPALIVE_IDLE_MS/.test(code)) {
    found.push("an already-served connection waits the full socket timeout for another request, holding a worker while it does");
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
    if (!/@\$port\\\\DavWWWRoot/.test(src.card)) {
      found.push("the UNC address carries no port, and Explorer will not reach a non-default one");
    }
    for (const [what, re] of [
      ["the 50 MB Windows download limit", /50 MB/],
      ["the FileSizeLimitInBytes registry value by name", /FileSizeLimitInBytes/],
      ["the WebClient service", /WebClient/],
      // The promise, not the sentence it was first written in. Two wordings have now been
      // cut from the card - one read as a slogan, one was collateral in a length pass - and
      // both times this rule caught it, which is the argument for keeping it keyed on meaning
      // and listing the forms rather than demanding one.
      ["that nothing leaves the network and no service is involved",
        /nothing leaves your network|nothing is hosted for you|no account anywhere|phone is the server/i],
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
    ["writing on by default for a new share", () =>
      swap("shares", "val writable: Boolean = false", "val writable: Boolean = true")],
    ["a writable share going unannounced on the card", () =>
      swap("card", "changes allowed", "sharing")],
    ["the loop reading a PUT body again", () =>
      swap("server", "DavBody.plan(method, headers, MAX_BODY_READ)", "DavBody.Plan.None")],
    ["PUT writing straight over the target", () =>
      swap("server", '".filet-part-" + java.lang.Long.toHexString(System.nanoTime())', '""')],
    ["a short body accepted as complete", () =>
      swap("server", "written == declared", "true")],
    ["a refusal leaving its body on the wire", () =>
      swap("server", "DavBody.drainAfterRefusal(headers)", "DavBody.Plan.None")],
    ["the Destination header bypassing the resolver", () =>
      swap("server", "DavPath.resolve(destPath, sh.code)", "DavPath.Resolved.Ok(destPath)")],
    ["the Destination header resolving against every live code", () =>
      swap("server", "DavPath.resolve(destPath, sh.code)", "DavPath.resolve(destPath, live.values.map { it.code })")],
    ["the matched code no longer selecting the share", () =>
      swap("server", "live.values.firstOrNull { it.code == r.code }", "live.values.firstOrNull()")],
    ["activity attributed before the share is known", () =>
      swap("server", "sh.lastActivity.set(System.currentTimeMillis())", "// not attributed")],
    ["auto-start without a pinned code", () =>
      swap("shares", "fun canAutoStart(share: DavShare): Boolean = !share.code.isNullOrBlank()",
           "fun canAutoStart(share: DavShare): Boolean = true")],
    ["a privileged port", () => swap("server", "DEFAULT_PORT = 8321", "DEFAULT_PORT = 445")],
    ["the connection pool going back to a fixed size", () =>
      swap("server", "java.util.concurrent.SynchronousQueue()", "java.util.concurrent.LinkedBlockingQueue()")],
    ["the accept loop back on a worker", () =>
      swap("server", 'Thread({ accept(s, mine) }, "filet-dav-accept").apply { isDaemon = true }.start()',
           "pool.execute { accept(s, mine) }")],
    ["an idle connection holding its worker for the full timeout", () =>
      swap("server", "socket.soTimeout = KEEPALIVE_IDLE_MS", "// left at the full timeout")],
    ["the constant-time compare removed", () => swap("path", "constantTimeEquals", "plainEquals")],
    ["the UNC form dropped from the card", () => swap("card", "DavWWWRoot", "NotTheMarker")],
    ["the UNC address losing its port", () => swap("card", "@$port\\\\DavWWWRoot", "\\\\DavWWWRoot")],
    ["the 50 MB warning removed", () => swap("card", "50 MB", "some size")],
    ["the WebClient warning removed", () => swap("card", "WebClient", "TheService")],
    ["the no-service promise removed", () => {
      const c = { ...good };
      c.card = c.card
        .replace(/nothing leaves your network/gi, "x")
        .replace(/nothing is hosted for you/gi, "x")
        .replace(/no account anywhere/gi, "x")
        // Every accepted form has to go, not just the first one written. With one left behind
        // the control mutated the card and changed nothing the rule looks at, so it "passed"
        // by removing a synonym - which is how a negative control stops being one.
        .replace(/phone is the server/gi, "x");
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
