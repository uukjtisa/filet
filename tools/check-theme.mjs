#!/usr/bin/env node
/**
 * G8 — Slate tokens exist, light is defined first, and the warm-neutral ground is the
 * documented value rather than a pure neutral (TEMPLATE.md §3).
 */
import { readFileSync, existsSync } from "node:fs";

const p = "app/src/main/java/dev/niccc2007/filet/ui/theme/Slate.kt";
const fail = (m) => { console.error("FAIL: " + m); process.exit(1); };
if (!existsSync(p)) fail("Slate.kt missing");
const s = readFileSync(p, "utf8");

for (const t of ["Bg", "Surface", "Line", "Fg", "Fg2", "Fg3", "Accent"]) {
  if (!new RegExp(`val ${t}\\b`).test(s)) fail(`light token ${t} missing`);
  if (!new RegExp(`val ${t}D\\b`).test(s)) fail(`dark token ${t}D missing`);
}
// Match the DECLARATIONS, not the bare names: imports sort alphabetically, so
// "darkColorScheme" always appears first as an import and would fail a naive scan.
const light = s.indexOf("= lightColorScheme(");
const dark = s.indexOf("= darkColorScheme(");
if (light < 0 || dark < 0) fail("could not find both scheme declarations");
if (light > dark) fail("dark scheme is declared before light; TEMPLATE.md §3 requires light first");
// the ground must be warm-neutral, not a pure grey
const bg = s.match(/val BgD = Color\(0xFF([0-9A-Fa-f]{6})\)/);
if (!bg) fail("BgD not found");
const [r, g, b] = [0, 2, 4].map(i => parseInt(bg[1].slice(i, i + 2), 16));
if (r === g && g === b) fail(`BgD #${bg[1]} is a pure neutral; Slate is warm-neutral by design`);
if (!(r >= g && g >= b)) fail(`BgD #${bg[1]} is not warm (expected r >= g >= b)`);

console.log(`THEME OK  (7 token pairs, light-first, BgD #${bg[1]} warm-neutral)`);
