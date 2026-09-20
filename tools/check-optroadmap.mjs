#!/usr/bin/env node
/**
 * The optimisation roadmap stays a roadmap and not a wish list.
 *
 * The failure mode for a performance document is specific and common: it turns into a list of
 * things that sound fast, with no measurement attached to any of them, and then every later
 * change can claim to be an optimisation because nothing can contradict it.
 *
 * So what is checked is the structure that makes it falsifiable - that the measure-first rule
 * is stated, that every pass declares what number ends it, and that how a number is taken is
 * written down so two runs can be compared. Whether the passes are the right passes is a
 * judgement; whether they can be checked at all is not.
 *
 *   node tools/check-optroadmap.mjs              check
 *   node tools/check-optroadmap.mjs --selftest   prove it catches a list of wishes
 *
 * Prints "OPTROADMAP OK" only after every assertion passes.
 */
import { existsSync, readFileSync } from "node:fs";

const DOC = "docs/OPTIMISATION.md";

export function problems(doc) {
  const found = [];
  if (!doc) return [`${DOC} is missing`];

  // The rule, stated rather than implied.
  if (!/measurement before and the same measurement after|measure.{0,20}first/i.test(doc)) {
    found.push("the measure-before-and-after rule is not stated, so nothing here is falsifiable");
  }

  // Passes, each with the number that closes it.
  const passes = [...doc.matchAll(/^###\s+Pass\s+\d+/gim)];
  if (passes.length < 3) {
    found.push(`only ${passes.length} pass(es): a roadmap with no stages is a list`);
  }
  const ends = [...doc.matchAll(/\*\*Ends with:\*\*/g)];
  if (ends.length < passes.length) {
    found.push(
      `${passes.length} passes but only ${ends.length} say what number ends them; ` +
        "a pass with no closing measurement cannot be finished, only abandoned",
    );
  }

  // How a number is taken. Without this, two numbers are not comparable and the rule above is
  // decorative.
  if (!/## How each number is taken/i.test(doc)) {
    found.push("nothing says how a measurement is taken, so two runs cannot be compared");
  }
  for (const [what, re] of [
    ["the same device", /same device/i],
    ["the build type", /build type|debug is not release/i],
    ["repeat runs rather than one", /median|runs/i],
    ["what cold means", /cold means cold|force-stop/i],
  ]) {
    if (!re.test(doc)) found.push(`the measurement protocol does not pin down ${what}`);
  }

  // The patterns it is taking from, with what each one catches. A pattern with no failure
  // attached is a fashion.
  const table = /\|\s*Pattern\s*\|\s*The failure it catches\s*\|/i.test(doc);
  if (!table) found.push("the patterns are not paired with the failure each one catches");

  // Guesses on the record, before measuring, so they can be wrong in public.
  if (!/ranked by suspicion|before measuring, on purpose/i.test(doc)) {
    found.push("no prior guesses are recorded, so the measurements cannot contradict anything");
  }

  return found;
}

if (process.argv.includes("--selftest")) {
  const good = [
    "Nothing is optimised without a measurement before and the same measurement after.",
    "| Pattern | The failure it catches |",
    "## Where this app is likely to be slow, ranked by suspicion",
    "written before measuring, on purpose",
    "### Pass 1 - see it", "**Ends with:** a list",
    "### Pass 2 - cold start", "**Ends with:** first-frame time",
    "### Pass 3 - the list", "**Ends with:** a histogram",
    "## How each number is taken",
    "The same device.", "The same build type. Debug is not release",
    "Ten runs, median and worst.", "Cold means cold. Force-stop",
  ].join("\n");
  const CASES = [
    ["a real roadmap", good, false],
    ["no measure-first rule", good.replace("Nothing is optimised without a measurement before and the same measurement after.", "Make it fast."), true],
    ["a pass with no closing number", good.replace("**Ends with:** a histogram", "it will be better"), true],
    ["no protocol at all", good.replace("## How each number is taken", "## Notes"), true],
    ["a protocol that never names the device", good.replace("The same device.", "somewhere"), true],
    ["patterns with no failures attached", good.replace("| Pattern | The failure it catches |", "| Pattern |"), true],
    ["no guesses on the record", good.replace("## Where this app is likely to be slow, ranked by suspicion", "## Notes").replace("written before measuring, on purpose", ""), true],
    ["two passes is a list", "Nothing is optimised without a measurement before and the same measurement after.\n### Pass 1\n**Ends with:** x\n## How each number is taken\nsame device\nbuild type\nmedian runs\ncold means cold\n| Pattern | The failure it catches |\nranked by suspicion", true],
    ["missing entirely", "", true],
  ];
  let bad = 0;
  for (const [name, doc, expect] of CASES) {
    const got = problems(doc).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} - expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  const neg = CASES.filter((c) => c[2]).length;
  console.log(`OPTROADMAP SELFTEST OK  (${neg} negative controls, ${CASES.length - neg} positive)`);
  process.exit(0);
}

const doc = existsSync(DOC) ? readFileSync(DOC, "utf8") : "";
const found = problems(doc);
if (found.length) {
  console.error("The optimisation roadmap cannot be held to anything:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const passes = [...doc.matchAll(/^###\s+Pass\s+\d+/gim)].length;
console.log(`OPTROADMAP OK  (${passes} passes, each with the measurement that closes it)`);
