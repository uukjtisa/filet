#!/usr/bin/env node
/**
 * The README has to say what Filet is FOR, not how it is put together.
 *
 * The opening section used to lead with the mechanism - one storage interface, a zip is a
 * folder, an APK is a folder. That is the most interesting thing about the codebase and close
 * to the least interesting thing about the app: nobody installs a file manager because of its
 * provider abstraction, and the repository description does not sell it that way either.
 *
 * This checks the section against the description the repository actually advertises, so the
 * two cannot drift apart again, and fails on the mechanism-first phrasings specifically.
 *
 *   node tools/check-readme.mjs              check
 *   node tools/check-readme.mjs --selftest   prove it catches the opening that shipped
 *
 * Prints "README OK" only after every assertion passes.
 */
import { readFileSync } from "node:fs";

const README = "README.md";

/** What the app is for. Every one of these has to appear in the opening section. */
const PROMISES = ["productivity", "power", "aesthetic", "convenience"];

/** Mechanism-first phrasings. True of the code, and not why anybody would want the app. */
const MECHANISM = [
  "a zip is a folder",
  "an apk is a folder",
  "one storage interface",
  "every backend is a plugin",
];

function section(md) {
  const at = md.indexOf("## What Filet is");
  if (at < 0) return null;
  const next = md.indexOf("\n## ", at + 5);
  return md.slice(at, next < 0 ? md.length : next);
}

function problems(md) {
  const found = [];
  const body = section(md);
  if (!body) return ["README has no \"What Filet is\" section"];

  const lower = body.toLowerCase();
  for (const p of PROMISES) {
    if (!lower.includes(p)) found.push(`the opening never mentions ${p}`);
  }
  for (const m of MECHANISM) {
    if (lower.includes(m)) found.push(`"${m}" leads with the mechanism rather than the point`);
  }
  if (body.split(/\s+/).length < 60) found.push("the opening is too short to say anything");
  return found;
}

if (process.argv.includes("--selftest")) {
  const NL = String.fromCharCode(10);
  const good =
    "## What Filet is" + NL + NL + "Productivity, power, aesthetics and convenience, and it " +
    "refuses to trade any one of them against the others. Find the file, move it, be done, " +
    "with search that answers across the whole device in milliseconds. Edit a file inside " +
    "an archive without unpacking the archive first. Read and rebuild an app package on " +
    "the phone. Share to any browser on your network with nothing installed on the other " +
    "end. It is not a spreadsheet of filenames, and it never asks you to pay for a feature " +
    "or sit through an advert to finish what you started." + NL + NL + "## Next" + NL;
  const CASES = [
    ["the opening that shipped", "## What Filet is\n\nFilet has one storage interface underneath everything. A zip is a folder. An APK is a folder, and that is the whole design of it, repeated at length to pass the length rule here.\n\n## Next\n", true],
    ["no section at all", "# Filet\n\nSomething else entirely.\n", true],
    ["a promise missing", good.replace("aesthetics", "tidiness"), true],
    ["too short", "## What Filet is\n\nProductivity, power, aesthetics, convenience.\n\n## Next\n", true],
    ["a good opening", good, false],
  ];
  let bad = 0;
  for (const [name, md, expect] of CASES) {
    const got = problems(md).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} — expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(`README SELFTEST OK  (${CASES.filter((c) => c[2]).length} negative controls, 1 positive)`);
  process.exit(0);
}

const md = readFileSync(README, "utf8");
const found = problems(md);
if (found.length) {
  console.error("The README opening does not say what Filet is for:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
console.log(`README OK  (the opening names all ${PROMISES.length} promises and leads with none of the mechanism)`);
