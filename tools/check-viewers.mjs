#!/usr/bin/env node
/**
 * Filet's viewers, as other apps see them, match the table the app routes by.
 *
 * Each viewer is offered to the rest of the phone as an `<activity-alias>` of MainActivity with
 * its own name ("Filet Video Player") and its own types, and `open/InboundViewers.kt` maps the
 * alias that was chosen to the viewer that opens. The two are written in different languages in
 * different files, and either drifting is silent:
 *
 *  - an alias with no table entry opens the routing table's choice under a name that promised a
 *    specific viewer - "Filet PDF Reader" opening a hex dump;
 *  - a table entry with no alias is a viewer nobody outside Filet can reach;
 *  - an alias whose label differs from the table's, or that is not exported, or that points at
 *    anything but MainActivity, or that claims no type at all.
 *
 *   node tools/check-viewers.mjs              check
 *   node tools/check-viewers.mjs --selftest   prove each drift is caught
 *
 * Prints "VIEWERS OK" / "VIEWERS SELFTEST OK" only after every assertion passes.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const MANIFEST = join(ROOT, "app/src/main/AndroidManifest.xml");
const TABLE = join(ROOT, "app/src/main/java/dev/niccc2007/filet/open/InboundViewers.kt");

export function aliases(manifest) {
  const out = [];
  for (const m of manifest.matchAll(/<activity-alias\b([\s\S]*?)<\/activity-alias>/g)) {
    const body = m[1];
    const attr = (n) => (body.match(new RegExp(`android:${n}\\s*=\\s*"([^"]+)"`)) || [])[1];
    out.push({
      name: attr("name"),
      label: attr("label"),
      target: attr("targetActivity"),
      exported: attr("exported"),
      mimes: [...body.matchAll(/android:mimeType\s*=\s*"([^"]+)"/g)].map((x) => x[1]),
      view: /android\.intent\.action\.VIEW/.test(body),
    });
  }
  return out;
}

export function table(src) {
  return [...src.matchAll(/^\s{4}([A-Z]+)\("(\.[^"]+)",\s*"([^"]+)",\s*HandlerId\.([A-Z]+)\)/gm)]
    .map((m) => ({ id: m[1], alias: m[2], label: m[3], handler: m[4] }));
}

export function problems(manifest, src) {
  const found = [];
  const a = aliases(manifest);
  const t = table(src);
  if (t.length === 0) found.push("no viewers found in InboundViewers.kt - has the enum moved?");
  if (a.length === 0) found.push("no <activity-alias> in the manifest - no viewer is offered by name");
  const byName = new Map(a.map((x) => [x.name, x]));
  for (const v of t) {
    const al = byName.get(v.alias);
    if (!al) {
      found.push(`${v.id} (${v.label}) has no <activity-alias android:name="${v.alias}">, so nothing outside can open it`);
      continue;
    }
    if (al.label !== v.label) found.push(`${v.alias} is labelled "${al.label}" in the manifest and "${v.label}" in the table`);
  }
  const known = new Set(t.map((v) => v.alias));
  for (const al of a) {
    if (!known.has(al.name)) found.push(`${al.name} is offered to other apps but InboundViewers has no entry, so it opens the wrong viewer`);
    if (al.target !== ".MainActivity") found.push(`${al.name} targets ${al.target}, not .MainActivity, where incoming files are opened`);
    if (al.exported !== "true") found.push(`${al.name} is not exported, so no other app can choose it`);
    if (!al.view) found.push(`${al.name} answers no VIEW, so it never appears in an Open with list`);
    if (al.mimes.length === 0) found.push(`${al.name} claims no type`);
    if (al.mimes.some((m) => m === "*/*")) found.push(`${al.name} claims every type; a named viewer must name what it reads`);
  }
  return found;
}

const manifest = readFileSync(MANIFEST, "utf8");
const src = readFileSync(TABLE, "utf8");

if (process.argv.includes("--selftest")) {
  if (problems(manifest, src).length) {
    console.error("VIEWERS SELFTEST cannot run: the tree does not pass its own check");
    for (const p of problems(manifest, src)) console.error("  " + p);
    process.exit(1);
  }
  const swap = (text, from, to) => {
    const out = text.replace(from, to);
    if (out === text) throw new Error(`mutation did not apply: ${from}`);
    return out;
  };
  const CASES = [
    ["an alias dropped from the manifest", () => [swap(manifest, 'android:name=".open.PdfReader"', 'android:name=".open.Gone"'), src]],
    ["a viewer dropped from the table", () => [manifest, swap(src, /^\s{4}PDF\(.*\r?\n/m, "")]],
    ["the label drifting", () => [swap(manifest, 'android:label="Filet Video Player"', 'android:label="Filet Player"'), src]],
    ["an alias not exported", () => [swap(manifest, /(android:name="\.open\.ImageViewer"[\s\S]*?android:exported=")true/, "$1false"), src]],
    ["an alias pointing elsewhere", () => [swap(manifest, /(android:name="\.open\.ApkInspector"\s*\n\s*android:targetActivity=")\.MainActivity/, "$1.pick.PickActivity"), src]],
    ["an alias claiming everything", () => [swap(manifest, 'android:mimeType="video/*"', 'android:mimeType="*/*"'), src]],
  ];
  let bad = 0;
  for (const [name, mutate] of CASES) {
    const [m, s] = mutate();
    if (problems(m, s).length === 0) { console.error(`SELFTEST FAILED: ${name} - not caught`); bad++; }
  }
  if (bad) process.exit(1);
  console.log(`VIEWERS SELFTEST OK  (${CASES.length} negative controls, 1 positive)`);
  process.exit(0);
}

const found = problems(manifest, src);
if (found.length) {
  console.error("The viewers other apps can choose do not match the ones Filet routes to:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
console.log(`VIEWERS OK  (${table(src).length} viewers, each offered by name, exported, and routed to its own screen)`);
