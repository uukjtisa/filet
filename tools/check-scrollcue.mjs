#!/usr/bin/env node
/**
 * Anything that scrolls sideways says so, or writes down why it does not have to.
 *
 * Nic reported this twice. Round 7 about the selection bar, round 8 about the update sheet's
 * chips: a row that scrolls sideways gave no sign that it did, and he only found out by
 * dragging it. He asked for the affordance to become the standard everywhere, not a one-off.
 *
 * The reason it needed reporting twice is the reason this file exists. The first fix was local:
 * one fade, in one composable, in one file. The next row somebody wrote had no idea it was
 * supposed to have one. "Gold standard" means the rule outlives whoever remembers it, and the
 * only thing that does that is a check.
 *
 * The rule: a raw `horizontalScroll(...)` is a finding unless the file is on the exemption list
 * below WITH a reason. `HScroll` is the sanctioned component and is exempt because it IS the
 * cue. Note the limit honestly - this sees the modifier, not the pixels. It cannot tell whether
 * a cue is visible enough, only whether one was asked for.
 *
 *   node tools/check-scrollcue.mjs              check
 *   node tools/check-scrollcue.mjs --selftest   prove it catches a bare scroller
 *
 * Prints "SCROLLCUE OK" only after every assertion passes.
 */
import { readdirSync, readFileSync, statSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { tmpdir } from "node:os";

/**
 * Files allowed a bare `horizontalScroll`, and why.
 *
 * Each of these is a case where an edge cue would be wrong, not merely inconvenient. Adding a
 * line here is a decision somebody has to defend in review; that is the point of it being a
 * list with reasons rather than a wildcard.
 */
const EXEMPT = {
  "app/src/main/java/dev/niccc2007/filet/ui/ScrollCue.kt":
    "HScroll itself. This is the component that draws the cue.",
  "app/src/main/java/dev/niccc2007/filet/handlers/ImageScreen.kt":
    "panning a zoomed image. The content IS the thing being moved and its edges are the " +
    "picture's edges; a chevron over somebody's photo would be an overlay on their content.",
  "app/src/main/java/dev/niccc2007/filet/crash/CrashActivity.kt":
    "a stack trace. Runs when the app has already died, so it must not depend on the theme " +
    "or on any component that could be the thing that crashed.",
  "app/src/main/java/dev/niccc2007/filet/browser/BrowserScreen.kt":
    "the breadcrumb, and ONLY the breadcrumb - the tab strip in the same file uses HScroll. " +
    "Nic's call after seeing it: the path strip already auto-scrolls to the deepest crumb on " +
    "every navigation, so a chevron points back at where you just came from, in the densest " +
    "strip on screen.",
  "app/src/main/java/dev/niccc2007/filet/update/ReleaseNotesView.kt":
    "a fenced code block inside release notes. Ported from Trawl unchanged, and a command " +
    "line that overflows is read by dragging it, not by being pointed at.",
};

const SANCTIONED = "HScroll";
const ROOTS = ["app/src/main/java", "core-vfs/src/main/java", "core-index/src/main/java"];

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (name.endsWith(".kt")) out.push(full);
  }
  return out;
}

export function check(files, readFile, exempt) {
  const problems = [];
  let scanned = 0;
  let usingComponent = 0;

  for (const file of files) {
    const rel = file.split(sep).join("/");
    const text = readFile(file);
    scanned++;

    // A CALL, not the declaration. The file that defines HScroll obviously mentions it, and
    // counting that would let the component survive with nobody actually using it.
    const declares = new RegExp(`fun\\s+${SANCTIONED}\\s*\\(`).test(text);
    if (!declares && new RegExp(`\\b${SANCTIONED}\\s*\\(`).test(text)) usingComponent++;

    // The import alone is not a finding; the call is.
    const calls = [...text.matchAll(/\.horizontalScroll\s*\(/g)].length +
      [...text.matchAll(/\bModifier\.horizontalScroll\s*\(/g)].length;
    if (calls === 0) continue;
    if (Object.prototype.hasOwnProperty.call(exempt, rel)) continue;

    problems.push(
      `${rel} scrolls sideways with no cue. Use ${SANCTIONED} from ui/ScrollCue.kt, or add ` +
        `the file to EXEMPT in this checker with the reason a cue would be wrong there.`,
    );
  }

  // A checker that finds nothing because it is looking in the wrong place passes silently.
  if (scanned < 40) problems.push(`only scanned ${scanned} files; the source layout changed`);
  if (usingComponent === 0) problems.push(`nothing uses ${SANCTIONED}; the component is dead`);

  // An exemption for a file that no longer exists is a comment pretending to be a rule.
  const present = new Set(files.map((f) => f.split(sep).join("/")));
  for (const rel of Object.keys(exempt)) {
    if (!present.has(rel)) problems.push(`EXEMPT lists ${rel}, which does not exist any more`);
    if (!exempt[rel] || exempt[rel].length < 20) problems.push(`EXEMPT ${rel} has no real reason`);
  }

  return { problems, scanned, usingComponent };
}

if (process.argv.includes("--selftest")) {
  const dir = mkdtempSync(join(tmpdir(), "scrollcue-"));
  try {
    const make = (rel, body) => {
      const full = join(dir, ...rel.split("/"));
      mkdirSync(join(full, ".."), { recursive: true });
      writeFileSync(full, body);
      return rel;
    };

    const good = [
      make("ok/Component.kt", "fun HScroll() { Modifier.horizontalScroll(state) }"),
      make("ok/User.kt", "HScroll(ground = c) { Text(\"x\") }"),
      ...Array.from({ length: 45 }, (_, i) => make(`ok/Filler${i}.kt`, "// nothing")),
    ];
    const read = (f) => readFileSync(join(dir, ...f.split("/")), "utf8");
    const exempt = { "ok/Component.kt": "HScroll itself, the component that draws the cue." };

    // Positive control first: a checker whose regex never matches "passes" everything.
    const clean = check(good, read, exempt);
    if (clean.problems.length) {
      console.error("SELFTEST FAIL: the good tree did not pass: " + clean.problems.join("; "));
      process.exit(1);
    }

    const cases = [
      [
        "a new row that scrolls with no cue",
        good.concat([make("ok/Naked.kt", "Row(Modifier.horizontalScroll(rememberScrollState()))")]),
        exempt,
        /scrolls sideways with no cue/,
      ],
      [
        "an exemption for a file that was deleted",
        good,
        { ...exempt, "ok/Gone.kt": "a reason long enough to look real" },
        /does not exist any more/,
      ],
      [
        "an exemption with no reason",
        good,
        { ...exempt, "ok/User.kt": "because" },
        /no real reason/,
      ],
      [
        "the component falling out of use",
        good.filter((f) => f !== "ok/User.kt"),
        exempt,
        /the component is dead/,
      ],
      [
        "looking in the wrong place",
        good.slice(0, 3),
        exempt,
        /source layout changed/,
      ],
    ];

    let failures = 0;
    for (const [name, files, ex, expected] of cases) {
      const { problems } = check(files, read, ex);
      if (!problems.some((p) => expected.test(p))) {
        failures++;
        console.error(`SELFTEST FAIL: "${name}" not caught. Got: ${problems.join("; ") || "(nothing)"}`);
      }
    }
    if (failures) {
      console.error(`SCROLLCUE SELFTEST: ${failures} case(s) not caught`);
      process.exit(1);
    }
    console.log(
      `SCROLLCUE SELFTEST OK  (1 positive control, ${cases.length} negative controls all caught)`,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
} else {
  const files = ROOTS.flatMap((r) => walk(r)).map((f) => relative(".", f));
  const { problems, scanned, usingComponent } = check(
    files,
    (f) => readFileSync(f, "utf8"),
    EXEMPT,
  );
  if (problems.length) {
    console.error("SCROLLCUE PROBLEMS: " + problems.length);
    for (const p of problems) console.error("  " + p);
    process.exit(1);
  }
  console.log(
    `SCROLLCUE OK  (${scanned} files scanned, ${usingComponent} use HScroll, ` +
      `${Object.keys(EXEMPT).length} exemptions each with a reason)`,
  );
}
