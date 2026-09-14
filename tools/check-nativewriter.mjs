#!/usr/bin/env node
/**
 * The native 7z writer was costed, and the answer was no. This keeps that answer honest.
 *
 * "How hard can a native writer be?" was a fair question and it got a measured answer rather
 * than a shrug: libarchive 3.7.7's 7z writer contains no encryption at all, and encryption was
 * the entire point, because Filet already writes 7z in Kotlin. The measurement lives in
 * `core-native/README.md`.
 *
 * An abandonment decays in two directions and both are silent:
 *
 *  - **The reason stops being written down.** Somebody trims the README, and what is left is a
 *    missing feature with no explanation, which the next person re-investigates from scratch.
 *  - **Half of it lands anyway.** A write source appears in `CMakeLists.txt`, or the
 *    `nativeWriter` switch comes back, and then the capability table has a branch claiming 7z
 *    AES-256 that nothing can reach - the dead switch R1 exists to forbid, in the one table the
 *    whole compress window reads.
 *
 *   node tools/check-nativewriter.mjs              check
 *   node tools/check-nativewriter.mjs --selftest   prove it fails on each way this breaks
 *
 * Prints "NATIVEWRITER OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";

const README = "core-native/README.md";
const CMAKE = "core-native/src/main/cpp/CMakeLists.txt";
const CAP = "core-vfs/src/main/java/dev/niccc2007/filet/vfs/provider/ArchiveCapability.kt";

/** The measurement has to still be on the page, with the numbers that make it checkable. */
const WRITTEN = [
  { what: "the section itself", from: /native 7z writer/i },
  { what: "the file that was measured", from: /archive_write_set_format_7zip\.c/ },
  { what: "the zip writer it was measured against", from: /archive_write_set_format_zip\.c/ },
  { what: "the version it was measured at", from: /3\.7\.7/ },
  { what: "the route that would work, named rather than hidden", from: /LGPL/ },
];

/**
 * A libarchive WRITE source in the build.
 *
 * `archive_write_private.h` and friends are headers the reader already pulls in, so this looks
 * for compiled `.c` files only - the thing that would mean a writer had actually started
 * landing.
 */
const WRITE_SOURCE = /archive_write[A-Za-z0-9_]*\.c/;

/** The switch that must not come back while nothing can set it. */
const DEAD_SWITCH = /nativeWriter/;

/** The refusal has to name the real cause and say what to do instead. */
const REFUSAL = [
  { what: "that AES-256 is real and 7z supports it", from: /AES-256/ },
  { what: "what to do instead", from: /Compress to zip/ },
];

export function check(readme, cmake, cap) {
  const problems = [];

  for (const w of WRITTEN) {
    if (!w.from.test(readme)) {
      problems.push(
        `E7: ${README} no longer carries ${w.what} (${w.from.source}) — an abandonment with no ` +
          "reason left on the page gets re-investigated from scratch",
      );
    }
  }

  const source = cmake.match(WRITE_SOURCE);
  if (source) {
    problems.push(
      `E8: ${source[0]} is compiled into core-native — a write path is landing, and this ` +
        "checker is asserting it did not. Either finish it and meet E8-E12, or take it out",
    );
  }

  if (DEAD_SWITCH.test(cap)) {
    problems.push(
      "E13: the nativeWriter switch is back in the capability table. Nothing can set it, so its " +
        "true branch is a dead claim that 7z takes a password — in the one table the compress " +
        "window reads to decide what to draw",
    );
  }

  for (const r of REFUSAL) {
    if (!r.from.test(cap)) {
      problems.push(`E13: the 7z password refusal no longer says ${r.what} (${r.from.source})`);
    }
  }

  return problems;
}

if (process.argv.includes("--selftest")) {
  const files = [README, CMAKE, CAP];
  if (files.some((f) => !existsSync(f))) {
    console.error("SELFTEST FAIL: a real file is missing, so the positive control is meaningless");
    process.exit(1);
  }
  const [readme, cmake, cap] = files.map((f) => readFileSync(f, "utf8"));

  // The positive control first. Without it, a checker that reported everything would "pass"
  // every negative case below.
  const clean = check(readme, cmake, cap);
  if (clean.length) {
    console.error("SELFTEST FAIL: the real files are reported as broken: " + clean.join("; "));
    process.exit(1);
  }

  const cases = [
    ["the measurement deleted from the README", readme.replace(/3\.7\.7/g, "some version"), cmake, cap, /E7/],
    ["the file that was measured no longer named", readme.replace(/archive_write_set_format_7zip\.c/g, "it"), cmake, cap, /E7/],
    [
      "a writer source quietly added to the build",
      readme,
      cmake + "\n    ${LA}/archive_write_set_format_7zip.c\n",
      cap,
      /write path is landing/,
    ],
    [
      "the dead switch put back",
      readme,
      cmake,
      cap + "\nfun of(f: ArchiveFormat, nativeWriter: Boolean = false) = 1\n",
      /dead claim/,
    ],
    ["a refusal that stops saying what to do instead", readme, cmake, cap.replace(/Compress to zip/g, "Sorry"), /E13/],
  ];
  let failures = 0;
  for (const [name, r, c, k, expected] of cases) {
    const problems = check(r, c, k);
    if (!problems.some((p) => expected.test(p))) {
      console.error(`SELFTEST FAIL: "${name}" was not caught (got: ${problems.join("; ") || "nothing"})`);
      failures++;
    }
  }
  if (failures) process.exit(1);
  console.log(`NATIVEWRITER SELFTEST OK  (${cases.length} negative controls and 1 positive)`);
  process.exit(0);
}

for (const f of [README, CMAKE, CAP]) {
  if (!existsSync(f)) {
    console.error(`FAIL: ${f} is missing — the abandonment has nowhere to be checked`);
    process.exit(1);
  }
}
const problems = check(readFileSync(README, "utf8"), readFileSync(CMAKE, "utf8"), readFileSync(CAP, "utf8"));
if (problems.length) {
  console.error(`NATIVEWRITER PROBLEMS: ${problems.length}`);
  for (const p of problems) console.error("  " + p);
  process.exit(1);
}
console.log(
  "NATIVEWRITER OK  (the measurement is still written down, no write source is compiled, the " +
    "dead switch is gone, and the refusal names the real reason)",
);
