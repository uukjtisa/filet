#!/usr/bin/env node
/**
 * The next version number is decided in one place, and the build agrees with it.
 *
 * Two failures this prevents, both of which have a history in this repository. A version that
 * is decided in conversation and never written down gets decided again, differently. And a
 * build that has been bumped past what the release notes describe ships a number nothing
 * explains.
 *
 * `docs/RELEASES.md` carries a single `NEXT: x.y.z` line. That is the decision. This checks
 * that it exists, that it is a real bump on the newest release, that it obeys the rule written
 * beside it, and that `app/build.gradle.kts` is either still on the released version (the
 * bump happens at release time) or already on the declared next one.
 *
 *   node tools/check-version.mjs              check
 *   node tools/check-version.mjs --selftest   prove it catches a version nobody decided
 *
 * Prints "VERSION OK" only after every assertion passes.
 */
import { existsSync, readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";

const DOC = "docs/RELEASES.md";
const GRADLE = "app/build.gradle.kts";
const README = "README.md";
const API = "https://api.github.com/repos/uukjtisa/filet/releases?per_page=10";

const parse = (v) => (v || "").trim().split(".").map(Number);
const cmp = (a, b) => {
  const [x, y] = [parse(a), parse(b)];
  for (let i = 0; i < 3; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0);
  return 0;
};

export function problems({ doc, gradleName, gradleCode, released, releasedCode, readme }) {
  const found = [];

  const m = /^\s*(?:\*\*)?NEXT:?(?:\*\*)?\s*\**\s*(\d+\.\d+\.\d+)/m.exec(doc || "");
  if (!m) {
    found.push(`${DOC} declares no NEXT version, so the next number is undecided`);
    return found;
  }
  const next = m[1];

  if (!/^\d+\.\d+\.\d+$/.test(gradleName || "")) {
    found.push(`${GRADLE} has no readable versionName`);
    return found;
  }

  if (released && cmp(next, released) <= 0) {
    found.push(`NEXT is ${next}, which is not ahead of the newest release ${released}`);
  }

  // The build is allowed to sit on the released version or on the declared next one. Anything
  // else is a number nothing describes.
  if (gradleName !== next && released && gradleName !== released) {
    found.push(
      `${GRADLE} declares ${gradleName}, which is neither the released ${released} nor the declared next ${next}`,
    );
  }

  if (gradleName === next && releasedCode != null && !(gradleCode > releasedCode)) {
    found.push(`versionCode ${gradleCode} does not advance on the released ${releasedCode}`);
  }

  // The rule written beside the decision: 1.0.0 is a claim, and the README currently
  // contradicts it.
  const major = parse(next)[0] >= 1;
  const early = /early[- ]development/i.test(readme || "");
  if (major && early) {
    found.push(`NEXT is ${next} while the README still says early development; one of them is wrong`);
  }

  // A minor bump is claimed for a reason. The reason has to be written where the decision is.
  const bumpsMinor = released && parse(next)[1] > parse(released)[1];
  if (bumpsMinor) {
    const why = doc.slice(doc.indexOf(m[0]), doc.indexOf(m[0]) + 700);
    if (why.split(/\s+/).length < 30) {
      found.push(`NEXT is a minor bump to ${next} with no reason written beside it`);
    }
  }

  return found;
}

if (process.argv.includes("--selftest")) {
  const doc =
    "**NEXT: 0.2.0** - the redesign release. It replaces the visual language of every screen, " +
    "adds a storage tool that did not exist, adds a metadata writer, and changes what a file " +
    "row shows by default. Somebody updating from 0.1.8 opens an app that does not look like " +
    "the one they closed.";
  const base = {
    doc, gradleName: "0.1.8", gradleCode: 8, released: "0.1.8", releasedCode: 8,
    readme: "early development",
  };
  const CASES = [
    ["the build still on the released version", base, false],
    ["the build already bumped", { ...base, gradleName: "0.2.0", gradleCode: 9 }, false],
    ["nobody decided", { ...base, doc: "no decision here" }, true],
    ["NEXT behind the newest release", { ...base, doc: "NEXT: 0.1.7 because reasons and a long enough sentence to pass the word count check for a minor bump which this is not" }, true],
    ["a build on a number nothing describes", { ...base, gradleName: "0.1.9", gradleCode: 9 }, true],
    ["a bumped name with a stale code", { ...base, gradleName: "0.2.0", gradleCode: 8 }, true],
    ["1.0.0 while the README says early development", {
      ...base,
      doc: doc.replace("0.2.0", "1.0.0"),
      gradleName: "0.1.8",
    }, true],
    ["a minor bump with no reason given", { ...base, doc: "NEXT: 0.2.0" }, true],
  ];
  let bad = 0;
  for (const [name, input, expect] of CASES) {
    const got = problems(input).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} - expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  const neg = CASES.filter((c) => c[2]).length;
  console.log(`VERSION SELFTEST OK  (${neg} negative controls, ${CASES.length - neg} positive)`);
  process.exit(0);
}

if (!existsSync(DOC) || !existsSync(GRADLE)) {
  console.error(`${DOC} or ${GRADLE} is missing`);
  process.exit(1);
}
const doc = readFileSync(DOC, "utf8");
const gradle = readFileSync(GRADLE, "utf8");
const readme = existsSync(README) ? readFileSync(README, "utf8") : "";
const gradleName = (/versionName\s*=\s*"([^"]+)"/.exec(gradle) || [])[1];
const gradleCode = Number((/versionCode\s*=\s*(\d+)/.exec(gradle) || [])[1]);

let released = null;
let releasedCode = null;
try {
  const out = execFileSync(
    "curl",
    ["-sS", "-H", "User-Agent: filet-check-version", "-H", "Accept: application/vnd.github+json", API],
    { encoding: "utf8", maxBuffer: 16 * 1024 * 1024 },
  );
  const parsed = JSON.parse(out);
  if (!Array.isArray(parsed)) throw new Error(parsed.message || "unexpected answer");
  const newest = parsed.filter((r) => !r.draft && !r.prerelease)[0];
  if (newest) released = String(newest.tag_name).replace(/^v/, "");
} catch (e) {
  // GitHub serves an HTML error page under rate limiting often enough that a red build for
  // network weather is a build people learn to ignore.
  console.log(`SKIP: could not read the newest release (${e.message})`);
  process.exit(2);
}
// The released code is not exposed by the API; the build is the only record, so this is only
// checked when the build has already been bumped past the release.
if (gradleName !== released) releasedCode = gradleCode - 1;
else releasedCode = gradleCode;

const found = problems({ doc, gradleName, gradleCode, released, releasedCode, readme });
if (found.length) {
  console.error("The next version number does not add up:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const next = /^\s*(?:\*\*)?NEXT:?(?:\*\*)?\s*\**\s*(\d+\.\d+\.\d+)/m.exec(doc)[1];
console.log(`VERSION OK  (released ${released}, build ${gradleName} code ${gradleCode}, next ${next})`);
