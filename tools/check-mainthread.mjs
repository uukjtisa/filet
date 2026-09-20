#!/usr/bin/env node
/**
 * Nothing slow runs on the thread that draws.
 *
 * Bug identified: pressing Start sharing did its file tracing and walked every network
 * interface on the device before it set the flag that shows the spinner - all of it inline on
 * the click. The main thread never yielded, so the frame that would have drawn the spinner did
 * not run until the slow part had already finished. The symptom is a button that looks dead
 * and a progress indicator nobody ever sees, and the instinct it provokes is to press again,
 * which is its own separate fault.
 *
 * The screen half was worse than the click half: the composable called the network-interface
 * walk directly inside composition, with a comment explaining that it was recomputed every
 * time on purpose. Composition runs on the main thread and runs often, so an unrelated state
 * change on that screen paid for an interface walk.
 *
 * This is a static check rather than a test because the failure is structural - it is *where*
 * a call appears, not what it returns - and because the next instance of it will be in a
 * different file. It can fail honestly: move a known-slow call back into a click handler or a
 * composable and it goes red.
 *
 *   node tools/check-mainthread.mjs              check
 *   node tools/check-mainthread.mjs --selftest   prove it catches the code that shipped
 *
 * Prints "MAIN THREAD OK" only after every assertion passes.
 */
import { readFileSync } from "node:fs";

const APP = "app/src/main/java/dev/niccc2007/filet";

/**
 * Calls that are known to block, with what they actually do.
 *
 * Deliberately a short list of things measured or read rather than everything that might be
 * slow. A checker that flags a hundred maybes is a checker people switch off.
 */
const SLOW = [
  [/NetAddresses\.reachable\s*\(/, "walks every network interface on the device"],
  [/\.getExternalFilesDir\s*\(/, "reaches the storage manager and can create a directory"],
  [/\breadLines\s*\(/, "reads a whole file"],
  [/\bappendText\s*\(/, "opens, writes and closes a file"],
  [/\bwriteText\s*\(/, "rewrites a whole file"],
  [/ServerSocket\s*\(/, "binds a socket"],
  [/\.stopAdvertising\s*\(/, "a binder call into the platform discovery service"],
];

/**
 * Entry points that are reached from a click or from composition, and the body of each that
 * must stay clean. `until` marks where the off-thread part begins - everything before it runs
 * on the caller's thread.
 */
const ENTRIES = [
  {
    file: `${APP}/nearby/NearbyManager.kt`,
    fn: "fun start()",
    until: "scope.launch",
    what: "the Start sharing press",
  },
  {
    file: `${APP}/nearby/NearbyManager.kt`,
    fn: "fun stop(origin: StopOrigin",
    until: "scope.launch",
    what: "the Stop sharing press",
  },
];

/** Composables that must not call anything slow anywhere in their body. */
const COMPOSABLES = [
  { file: `${APP}/nearby/NearbyScreen.kt`, what: "the Nearby screen" },
];

/** The flag a busy control reads has to be set before the slow part, not after. */
const BUSY_FIRST = [
  {
    file: `${APP}/nearby/NearbyManager.kt`,
    fn: "fun start()",
    flag: "_starting.value = true",
    until: "scope.launch",
    what: "the start spinner",
  },
  {
    file: `${APP}/nearby/NearbyManager.kt`,
    fn: "fun stop(origin: StopOrigin",
    flag: "_stopping.value = true",
    until: "scope.launch",
    what: "the stop spinner",
  },
];

/** The slice of a function that runs on the caller's thread. */
function inlineBody(src, fn, until) {
  const at = src.indexOf(fn);
  if (at < 0) return null;
  const stop = src.indexOf(until, at);
  return src.slice(at, stop < 0 ? src.length : stop);
}

/** Strip comments, so prose about a slow call is not mistaken for one. */
function code(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}

export function problems(files) {
  const found = [];

  for (const e of ENTRIES) {
    const src = files[e.file];
    if (src == null) { found.push(`${e.file} is gone - move this list with it`); continue; }
    const body = inlineBody(code(src), e.fn, e.until);
    if (body == null) { found.push(`${e.fn} is gone from ${e.file}`); continue; }
    for (const [re, why] of SLOW) {
      if (re.test(body)) found.push(`${e.what} runs something inline that ${why}`);
    }
  }

  for (const c of COMPOSABLES) {
    const src = files[c.file];
    if (src == null) { found.push(`${c.file} is gone - move this list with it`); continue; }
    for (const [re, why] of SLOW) {
      if (re.test(code(src))) found.push(`${c.what} calls something in composition that ${why}`);
    }
  }

  for (const b of BUSY_FIRST) {
    const src = files[b.file];
    if (src == null) continue;
    const body = inlineBody(code(src), b.fn, b.until);
    if (body == null) continue;
    if (!body.includes(b.flag)) {
      found.push(`${b.what} is not raised before the work starts, so the frame that draws it cannot run`);
    }
  }

  return found;
}

if (process.argv.includes("--selftest")) {
  const M = `${APP}/nearby/NearbyManager.kt`;
  const S = `${APP}/nearby/NearbyScreen.kt`;
  const good = {
    [M]: `fun start() {\n _starting.value = true\n scope.launch(Dispatchers.IO) { off() }\n}\n` +
         `fun stop(origin: StopOrigin = X) {\n stopToken.incrementAndGet()\n _stopping.value = true\n scope.launch(Dispatchers.IO) { off() }\n}\n` +
         `private fun off() { NetAddresses.reachable(); ServerSocket(1); }`,
    [S]: `@Composable fun Card() { val b by nearby.blocked.collectAsState() }`,
  };
  const CASES = [
    ["the fixed version", good, false],
    ["the interface walk back on the click", {
      ...good,
      [M]: good[M].replace("_starting.value = true", "_starting.value = true\n val b = NetAddresses.reachable()"),
    }, true],
    ["the trace writing a file on the click", {
      ...good,
      [M]: good[M].replace("_starting.value = true", "f.appendText(x)\n _starting.value = true"),
    }, true],
    ["the busy flag set after the launch", {
      ...good,
      [M]: good[M].replace("_starting.value = true\n scope.launch", "scope.launch"),
    }, true],
    ["the stop tearing down the socket inline", {
      ...good,
      [M]: good[M].replace("_stopping.value = true", "discovery.stopAdvertising()\n _stopping.value = true"),
    }, true],
    ["the screen walking interfaces in composition", {
      ...good,
      [S]: `@Composable fun Card() { val b = NetAddresses.reachable() }`,
    }, true],
    ["a comment about a slow call is not a slow call", {
      ...good,
      [S]: `@Composable fun Card() {\n // NetAddresses.reachable() used to be called here\n }`,
    }, false],
    ["the file moved away", { ...good, [M]: null }, true],
  ];
  let bad = 0;
  for (const [name, files, expect] of CASES) {
    const got = problems(files).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} - expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  const neg = CASES.filter((c) => c[2]).length;
  console.log(`MAIN THREAD SELFTEST OK  (${neg} negative controls, ${CASES.length - neg} positive)`);
  process.exit(0);
}

const files = {};
for (const f of [...new Set([...ENTRIES, ...COMPOSABLES, ...BUSY_FIRST].map((x) => x.file))]) {
  try {
    files[f] = readFileSync(f, "utf8");
  } catch {
    files[f] = null;
  }
}
const found = problems(files);
if (found.length) {
  console.error("Something slow is running on the thread that draws:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
console.log(
  `MAIN THREAD OK  (${ENTRIES.length} press paths and ${COMPOSABLES.length} screen(s) clear of ${SLOW.length} known blocking calls)`,
);
