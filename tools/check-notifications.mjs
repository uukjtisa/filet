#!/usr/bin/env node
/**
 * No two notifications share an id, and none is posted with a number typed in place.
 *
 * Round 9, from a phone: *"filet debug lkeeps trying to start but isnt working.. hte
 * notification keeps appearing and disappearing too"*.
 *
 * `NearbyService` had `private const val ID = 4201`. `UpdateNotifier`, added a round later, had
 * `private const val ID = 4_201`. The same number, written two different ways - so a grep for
 * `4201` while writing the second one found nothing.
 *
 * A notification id is a key across the whole application. Nearby's belongs to a FOREGROUND
 * SERVICE, so the updater cancelling that id pulled the notification out from under a running
 * service; Android stops a foreground service whose notification disappears, it restarted,
 * re-posted, and that loop is what he could see flickering.
 *
 * Two rules, both of which that bug broke:
 *
 *  1. Every id is declared in `Notifications.kt` and they are all distinct - compared as
 *     NUMBERS, so `4201` and `4_201` are caught.
 *  2. A `notify(...)` or `startForeground(...)` call does not pass a literal number. An id
 *     typed at the call site is one that was never compared against anything.
 *
 *   node tools/check-notifications.mjs              check
 *   node tools/check-notifications.mjs --selftest   prove it fails on each way this breaks
 *
 * Prints "NOTIFICATIONS OK" only after every assertion passes.
 */
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const REGISTRY = "app/src/main/java/dev/niccc2007/filet/Notifications.kt";
const SOURCE = "app/src/main/java/dev/niccc2007/filet";

/** `const val NEARBY = 4201` and `const val NEARBY = 4_201` both come back as 4201. */
export function idsIn(text) {
  const out = [];
  for (const m of text.matchAll(/const\s+val\s+([A-Z][A-Z0-9_]*)\s*=\s*([0-9_]+)\b/g)) {
    out.push([m[1], Number(m[2].replace(/_/g, ""))]);
  }
  return out;
}

/** Every `notify(N, …)` / `startForeground(N, …)` where N is written as a number. */
export function literalPosts(text) {
  const out = [];
  for (const m of text.matchAll(/\b(?:notify|startForeground|cancel)\s*\(\s*([0-9][0-9_]*)\s*[,)]/g)) {
    out.push(Number(m[1].replace(/_/g, "")));
  }
  return out;
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith(".kt")) out.push(p);
  }
  return out;
}

export function check({ registry, files }) {
  const problems = [];

  const ids = idsIn(registry);
  if (ids.length === 0) {
    problems.push("Notifications.kt declares no ids at all");
    return problems;
  }

  // 1. distinct, as numbers.
  const byValue = new Map();
  for (const [name, value] of ids) {
    if (byValue.has(value)) {
      problems.push(
        `${name} and ${byValue.get(value)} are both ${value} — ` +
          `posting one replaces the other, and cancelling one cancels the other`,
      );
    } else {
      byValue.set(value, name);
    }
  }

  // 2. nothing posts a number typed at the call site.
  for (const [path, text] of files) {
    if (path.replace(/\\/g, "/").endsWith("Notifications.kt")) continue;
    for (const literal of literalPosts(text)) {
      problems.push(
        `${path} posts notification ${literal} as a literal — ` +
          `ids are declared in Notifications.kt so they can be compared`,
      );
    }
  }

  return problems;
}

if (process.argv.includes("--selftest")) {
  const cases = [
    [
      "two ids with the same value, spelled differently",
      {
        registry: "const val NEARBY = 4201\nconst val UPDATE = 4_201\n",
        files: [],
      },
      /both 4201/,
    ],
    [
      "a literal id at a notify call",
      {
        registry: "const val NEARBY = 4201\n",
        files: [["Foo.kt", "NotificationManagerCompat.from(c).notify(4201, n)"]],
      },
      /literal/,
    ],
    [
      "a literal id at a startForeground call",
      {
        registry: "const val NEARBY = 4201\n",
        files: [["Bar.kt", "startForeground(4301, notification)"]],
      },
      /literal/,
    ],
    [
      "a registry with nothing in it",
      { registry: "// nothing here\n", files: [] },
      /no ids/,
    ],
  ];
  let failures = 0;
  for (const [name, input, expected] of cases) {
    const problems = check(input);
    const hit = problems.some((p) => expected.test(p));
    if (!hit) {
      console.error(`SELFTEST FAIL: "${name}" was not caught (got: ${problems.join("; ") || "nothing"})`);
      failures++;
    }
  }
  // The positive control. Without it, a checker that reported everything would pass above.
  const clean = check({
    registry: "const val NEARBY = 4201\nconst val INDEX = 4301\nconst val UPDATE = 4401\n",
    files: [["Ok.kt", "notify(Notifications.NEARBY, n)"]],
  });
  if (clean.length) {
    console.error("SELFTEST FAIL: a clean set was reported as broken: " + clean.join("; "));
    failures++;
  }
  if (failures) process.exit(1);
  console.log(`NOTIFICATIONS SELFTEST OK  (${cases.length} negative controls and 1 positive)`);
  process.exit(0);
}

if (!existsSync(REGISTRY)) {
  console.error(`FAIL: ${REGISTRY} is missing — notification ids have nowhere to be compared`);
  process.exit(1);
}
const files = walk(SOURCE).map((p) => [p.replace(/\\/g, "/"), readFileSync(p, "utf8")]);
const problems = check({ registry: readFileSync(REGISTRY, "utf8"), files });
if (problems.length) {
  console.error(`NOTIFICATION PROBLEMS: ${problems.length}`);
  for (const p of problems) console.error("  " + p);
  process.exit(1);
}
const count = idsIn(readFileSync(REGISTRY, "utf8")).length;
console.log(`NOTIFICATIONS OK  (${count} ids, all distinct, none posted as a literal)`);
