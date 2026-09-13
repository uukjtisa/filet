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
/** Measured, stated, not fatal — for the things that are a judgement rather than a defect. */
const warn = (m) => console.warn("note: " + m);

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

/**
 * Sample an SVG elliptical arc, endpoint parameterisation (SVG spec F.6.5).
 *
 * Returns points along the curve, including both endpoints. Sampling rather than
 * solving for extrema: 24 points on a corner radius is well under a tenth of a
 * unit of error on this canvas, and the alternative is four more pages of algebra
 * for a bound that is already exact enough to decide an 18..90 question.
 */
function arcPoints(x1, y1, rx, ry, rotDeg, largeArc, sweep, x2, y2) {
  if (rx === 0 || ry === 0) return [[x1, y1], [x2, y2]];
  rx = Math.abs(rx); ry = Math.abs(ry);
  const phi = (rotDeg * Math.PI) / 180;
  const cosP = Math.cos(phi), sinP = Math.sin(phi);
  const dx2 = (x1 - x2) / 2, dy2 = (y1 - y2) / 2;
  const x1p = cosP * dx2 + sinP * dy2;
  const y1p = -sinP * dx2 + cosP * dy2;

  // Scale the radii up if they are too small to span the chord (spec step 3).
  const lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
  if (lambda > 1) { const k = Math.sqrt(lambda); rx *= k; ry *= k; }

  const sign = largeArc === sweep ? -1 : 1;
  const num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
  const den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
  const co = sign * Math.sqrt(Math.max(0, num / den));
  const cxp = (co * rx * y1p) / ry;
  const cyp = (-co * ry * x1p) / rx;
  const cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2;
  const cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2;

  const ang = (ux, uy, vx, vy) => {
    const dot = ux * vx + uy * vy;
    const len = Math.hypot(ux, uy) * Math.hypot(vx, vy);
    const a = Math.acos(Math.min(1, Math.max(-1, dot / len)));
    return ux * vy - uy * vx < 0 ? -a : a;
  };
  const t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
  let dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
  if (!sweep && dt > 0) dt -= 2 * Math.PI;
  if (sweep && dt < 0) dt += 2 * Math.PI;

  const out = [];
  const steps = 24;
  for (let k = 0; k <= steps; k++) {
    const t = t1 + (dt * k) / steps;
    const px = cx + cosP * rx * Math.cos(t) - sinP * ry * Math.sin(t);
    const py = cy + sinP * rx * Math.cos(t) + cosP * ry * Math.sin(t);
    out.push([px, py]);
  }
  return out;
}

// ── the safe zone, measured rather than asserted ──
//
// An adaptive icon's foreground is 108x108 and only the middle 72x72 (18..90 on
// both axes) survives every launcher mask. The drawable's own comment used to
// claim the mark was inside it; the mark reached x=16 and x=91.9, and nothing
// checked. So this computes the bounds and decides.
//
// The parser is deliberately crude - it walks the pen and records every endpoint
// and control point rather than flattening curves. That OVER-estimates a bezier's
// extent, never under-estimates it, which is the safe direction for a bounds check.
function bounds(pathData) {
  const toks = pathData.match(/[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+/g) || [];
  let x = 0, y = 0, cmd = null, i = 0;
  const xs = [], ys = [];
  const num = (k) => parseFloat(toks[i + k]);
  while (i < toks.length) {
    if (/[A-Za-z]/.test(toks[i])) { cmd = toks[i]; i++; continue; }
    const rel = cmd === cmd.toLowerCase();
    switch (cmd.toUpperCase()) {
      case "M": case "L":
        x = rel ? x + num(0) : num(0); y = rel ? y + num(1) : num(1); i += 2; break;
      case "H": x = rel ? x + num(0) : num(0); i += 1; break;
      case "V": y = rel ? y + num(0) : num(0); i += 1; break;
      case "C":
        for (const k of [0, 2, 4]) {
          xs.push(rel ? x + num(k) : num(k));
          ys.push(rel ? y + num(k + 1) : num(k + 1));
        }
        { const nx = rel ? x + num(4) : num(4), ny = rel ? y + num(5) : num(5); x = nx; y = ny; }
        i += 6; break;
      case "S": case "Q":
        for (const k of [0, 2]) {
          xs.push(rel ? x + num(k) : num(k));
          ys.push(rel ? y + num(k + 1) : num(k + 1));
        }
        { const nx = rel ? x + num(2) : num(2), ny = rel ? y + num(3) : num(3); x = nx; y = ny; }
        i += 4; break;
      case "T":
        { const nx = rel ? x + num(0) : num(0), ny = rel ? y + num(1) : num(1); x = nx; y = ny; }
        i += 2; break;
      case "A": {
        // Arcs are FLATTENED, not bounded by their radii.
        //
        // The obvious shortcut - "an arc reaches at most rx,ry from where it
        // started" - is true and useless: on a rounded 6-unit corner it claims
        // twelve units of travel in every direction and reports the mark as
        // twelve units outside a zone it is comfortably inside. A checker that
        // cries wolf gets deleted, so this does the real parameterisation.
        const [rx0, ry0, rot] = [num(0), num(1), num(2)];
        const [laf, sf] = [num(3), num(4)];
        const ex = rel ? x + num(5) : num(5);
        const ey = rel ? y + num(6) : num(6);
        for (const [px, py] of arcPoints(x, y, rx0, ry0, rot, laf, sf, ex, ey)) {
          xs.push(px); ys.push(py);
        }
        x = ex; y = ey;
        i += 7; break;
      }
      case "Z": i = toks.length; break;
      default: i++; continue;
    }
    xs.push(x); ys.push(y);
  }
  return { x0: Math.min(...xs), x1: Math.max(...xs), y0: Math.min(...ys), y1: Math.max(...ys) };
}

/** The uniform scale a <group> applies about the canvas centre, or 1 when there is none. */
function groupScale(xml) {
  const sx = xml.match(/android:scaleX="([\d.]+)"/);
  const sy = xml.match(/android:scaleY="([\d.]+)"/);
  if (!sx || !sy) return 1;
  if (sx[1] !== sy[1]) fail("icon group scales X and Y differently — that distorts the mark");
  const pivot = xml.match(/android:pivotX="([\d.]+)"/);
  if (!pivot || parseFloat(pivot[1]) !== 54) fail("icon group must pivot on the canvas centre (54)");
  return parseFloat(sx[1]);
}

for (const [name, xml] of [["foreground", fg], ["monochrome", mono]]) {
  const k = groupScale(xml);
  let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
  for (const [, d] of xml.matchAll(/android:pathData="([^"]+)"/g)) {
    const b = bounds(d);
    x0 = Math.min(x0, b.x0); x1 = Math.max(x1, b.x1);
    y0 = Math.min(y0, b.y0); y1 = Math.max(y1, b.y1);
  }
  const at = (v) => 54 + (v - 54) * k;
  const [sx0, sx1, sy0, sy1] = [at(x0), at(x1), at(y0), at(y1)];
  const out = [];
  if (sx0 < 18) out.push(`left edge at ${sx0.toFixed(1)}`);
  if (sx1 > 90) out.push(`right edge at ${sx1.toFixed(1)}`);
  if (sy0 < 18) out.push(`top edge at ${sy0.toFixed(1)}`);
  if (sy1 > 90) out.push(`bottom edge at ${sy1.toFixed(1)}`);
  if (out.length) {
    fail(`${name} leaves the 18..90 safe zone: ${out.join(", ")}. A launcher mask will crop it.`);
  }

  // The square is the floor, not the guarantee.
  //
  // 72x72 is what a SQUARE mask shows. Every mask - circle, squircle, teardrop -
  // is inscribed in that viewport, and the shape common to all of them is the
  // 66dp circle: radius 33 about the centre. A mark that fills the square still
  // loses its extremes to a round launcher, which is what the splash screen was
  // showing. Reported, not failed: how bold the mark is against how much of it
  // survives a circle is a design decision, and a checker does not get to make
  // it. It does get to stop anyone guessing at the number.
  const halfDiag = Math.hypot((sx1 - sx0) / 2, (sy1 - sy0) / 2);
  if (halfDiag > 33) {
    warn(
      `${name} fills the square but not the 66dp circle every mask shares ` +
      `(half-diagonal ${halfDiag.toFixed(1)} against 33). A round launcher crops the ` +
      `sides; scale ${(33 / halfDiag).toFixed(3)} of current would fit it entirely.`
    );
  }
}

let raster = 0;
for (const d of readdirSync(res)) {
  if (!/^mipmap-.*dpi$/.test(d)) continue;
  raster += readdirSync(join(res, d)).filter(f => /\.(webp|png)$/.test(f)).length;
}
if (raster > 0) fail(`${raster} generated raster launcher icon(s) still present`);

console.log(`ICON OK  (2 foreground paths, 1-colour monochrome, ${found} adaptive config(s), 0 raster, both layers inside the 18..90 safe zone)`);
