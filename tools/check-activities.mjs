#!/usr/bin/env node
/**
 * Every Activity in the app is declared in the manifest.
 *
 * An undeclared Activity compiles, installs and runs, right up to the first startActivity - which
 * throws ActivityNotFoundException and takes the app down. That is exactly how "Report a problem"
 * shipped: the form was written, linked from About, and never declared, so the first tap crashed
 * the app the form exists to report on. Nothing caught it, because nothing can until a finger
 * lands on the row.
 *
 * Found the way a reader would: a class is an Activity if it extends one of the platform bases, or
 * extends another class this scan has already found to be one (CrashReportActivity extends
 * ReportActivity). Declared means an <activity android:name> that resolves to it.
 *
 *   node tools/check-activities.mjs              scan the app
 *   node tools/check-activities.mjs --selftest   prove the scanner catches the failure
 *
 * Prints "ACTIVITIES OK" / "ACTIVITIES SELFTEST OK" only after every assertion passes.
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const SRC = join(ROOT, "app/src/main/java");
const MANIFEST = join(ROOT, "app/src/main/AndroidManifest.xml");
const NAMESPACE = "dev.niccc2007.filet";

const BASES = ["Activity", "ComponentActivity", "AppCompatActivity", "FragmentActivity"];

function walk(dir, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith(".kt")) out.push(p);
  }
  return out;
}

/** [{ fqcn, name, parent }] for every class declaration with a supertype call. */
function classesIn(files) {
  const decl = /^\s*(?:(?:open|abstract|internal|private|public|final|data)\s+)*class\s+(\w+)[^:{\n]*:\s*([\w.]+)\s*\(/gm;
  const out = [];
  for (const { pkg, text } of files) {
    for (const m of text.matchAll(decl)) {
      out.push({ fqcn: `${pkg}.${m[1]}`, name: m[1], parent: m[2].split(".").pop() });
    }
  }
  return out;
}

function activities(classes) {
  const known = new Set(BASES);
  const found = new Map();
  let grew = true;
  while (grew) {
    grew = false;
    for (const c of classes) {
      if (!found.has(c.fqcn) && known.has(c.parent)) {
        found.set(c.fqcn, c);
        known.add(c.name);
        grew = true;
      }
    }
  }
  return [...found.values()];
}

function declared(manifest) {
  const out = new Set();
  for (const m of manifest.matchAll(/<activity(?:-alias)?\b[^>]*?android:name="([^"]+)"/gs)) {
    const n = m[1];
    out.add(n.startsWith(".") ? NAMESPACE + n : n.includes(".") ? n : `${NAMESPACE}.${n}`);
  }
  return out;
}

function missing(sources, manifest) {
  const acts = activities(classesIn(sources));
  const have = declared(manifest);
  return { acts, missing: acts.filter((a) => !have.has(a.fqcn)) };
}

function read() {
  return walk(SRC).map((p) => {
    const text = readFileSync(p, "utf8");
    const pkg = (text.match(/^package\s+([\w.]+)/m) || [])[1] || "";
    return { pkg, text, file: relative(ROOT, p).split(sep).join("/") };
  });
}

if (process.argv.includes("--selftest")) {
  const src = (pkg, text) => ({ pkg, text });
  const files = [
    src("dev.niccc2007.filet.report", "open class ReportActivity : ComponentActivity() {}\nclass CrashReportActivity : ReportActivity()"),
    src("dev.niccc2007.filet", "class MainActivity : ComponentActivity() {"),
    src("dev.niccc2007.filet.x", "class NotOne : SomethingElse()\nclass Model(val a: Int) : ViewModel()"),
  ];
  const cases = [
    ["nothing declared", "", ["dev.niccc2007.filet.report.ReportActivity", "dev.niccc2007.filet.report.CrashReportActivity", "dev.niccc2007.filet.MainActivity"]],
    ["the subclass forgotten", `<activity android:name=".report.ReportActivity"/><activity android:name=".MainActivity"/>`, ["dev.niccc2007.filet.report.CrashReportActivity"]],
    ["all declared", `<activity android:name=".report.ReportActivity"/><activity\n android:exported="false"\n android:name="dev.niccc2007.filet.report.CrashReportActivity"/><activity android:name=".MainActivity"/>`, []],
  ];
  for (const [what, manifest, want] of cases) {
    const got = missing(files, manifest).missing.map((a) => a.fqcn).sort();
    if (JSON.stringify(got) !== JSON.stringify([...want].sort())) {
      console.error(`ACTIVITIES SELFTEST FAILED (${what}): got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`);
      process.exit(1);
    }
  }
  console.log(`ACTIVITIES SELFTEST OK  (${cases.length} cases, a subclass of a subclass found, a non-Activity ignored)`);
  process.exit(0);
}

const sources = read();
const { acts, missing: gone } = missing(sources, readFileSync(MANIFEST, "utf8"));
if (acts.length < 3) {
  console.error(`ACTIVITIES FAILED: only ${acts.length} Activity classes found - the pattern is stale`);
  process.exit(1);
}
if (gone.length) {
  console.error("Activities that would crash the app on their first startActivity - not in the manifest:\n");
  for (const a of gone) console.error(`  ${a.fqcn}`);
  process.exit(1);
}
console.log(`ACTIVITIES OK  (${acts.length} Activity classes, every one declared)`);
