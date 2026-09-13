#!/usr/bin/env node
/**
 * G3 — the E4 adaptive icon is installed, the generated raster set is gone, and the
 * monochrome layer exists.
 *
 * The monochrome check is the one that matters: it is the layer that collapses both shapes
 * to a single colour, and it is where a faked cut turns the mark into a blob.
 */
import { readFileSync, existsSync, readdirSync } from "node:fs";
import { join } from "node:path";

const res = "app/src/main/res";
const fail = (m) => { console.error("FAIL: " + m); process.exit(1); };

for (const f of ["drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml"]) {
  if (!existsSync(join(res, f))) fail(`missing ${f}`);
}

// adaptive config must reference all three layers
let found = 0;
for (const d of ["mipmap-anydpi", "mipmap-anydpi-v26"]) {
  const p = join(res, d, "ic_launcher.xml");
  if (!existsSync(p)) continue;
  const x = readFileSync(p, "utf8");
  for (const layer of ["<background", "<foreground", "<monochrome"]) {
    if (!x.includes(layer)) fail(`${d}/ic_launcher.xml has no ${layer}`);
  }
  found++;
}
if (found === 0) fail("no mipmap-anydpi*/ic_launcher.xml");

// the gap must be real geometry, not a background-coloured shape on top
const fg = readFileSync(join(res, "drawable/ic_launcher_foreground.xml"), "utf8");
const paths = [...fg.matchAll(/android:pathData="([^"]+)"/g)];
if (paths.length !== 2) fail(`foreground should be exactly 2 paths (body + flap), found ${paths.length}`);
if (/#1E1F1D/i.test(fg)) fail("foreground paints the ground colour — that is a faked cut and dies in mono");

const mono = readFileSync(join(res, "drawable/ic_launcher_monochrome.xml"), "utf8");
const monoFills = [...mono.matchAll(/android:fillColor="([^"]+)"/g)].map(x => x[1].toUpperCase());
if (new Set(monoFills).size !== 1) fail("monochrome layer must use exactly one fill colour");

let raster = 0;
for (const d of readdirSync(res)) {
  if (!/^mipmap-.*dpi$/.test(d)) continue;
  raster += readdirSync(join(res, d)).filter(f => /\.(webp|png)$/.test(f)).length;
}
if (raster > 0) fail(`${raster} generated raster launcher icon(s) still present`);

console.log(`ICON OK  (2 foreground paths, 1-colour monochrome, ${found} adaptive config(s), 0 raster)`);
