#!/usr/bin/env node
// Writes the tab icons, one adaptive launcher icon per tab and per app action, from the tab icon
// mock, the page they were designed and approved in.
//
// The mock draws each icon as SVG on the 108-unit adaptive canvas. Android's vector drawables
// read the same path data, so nothing is redrawn: this runs the mock's own code, takes each icon's
// markup, and writes it out as vector drawables. Shapes become paths, transforms become groups,
// and each gradient is pinned to its shape's own bounds, which is how the mock's gradients are
// measured. For every icon it writes:
//
//   drawable/ic_tab_<id>_fg.xml     the art, the adaptive icon's foreground
//   drawable/ic_tab_<id>_mono.xml   the one-colour line glyph a themed home screen draws
//   mipmap-anydpi/ic_tab_<id>.xml   the adaptive icon: Filet's tile, the art, the glyph
//
// plus drawable/ic_tab_bg.xml, the tile they share, and shortcuts/TabIconRes.kt, which maps the
// mock's ids to the resources.
//
//   node tools/make-tab-icons.mjs            write them
//   node tools/make-tab-icons.mjs --check    exit 1 when a written file is not what the mock gives,
//                                            or an icon's ink leaves the 33-unit safe circle
//
// The mock lives outside the repo, beside the other mocks. FILET_TAB_MOCK points at it elsewhere.

import { existsSync, readFileSync, writeFileSync, mkdirSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const MOCK = process.env.FILET_TAB_MOCK || join(homedir(), "Desktop", "filet-tab-icons-mock.html");
const RES = join(ROOT, "app/src/main/res");
const KT = join(ROOT, "app/src/main/java/dev/niccc2007/filet/shortcuts/TabIconRes.kt");
const check = process.argv.includes("--check");

/** The circle every launcher's mask keeps, on the 108 canvas. */
const SAFE = 33;

/** The least a shape must paint to count as ink, as the mock's own safe-zone check counts it. */
const INK_ALPHA = 40 / 255;

if (!existsSync(MOCK)) {
  console.log(`SKIP: the tab icon mock is not on this machine (${MOCK}). Set FILET_TAB_MOCK to point at it.`);
  process.exit(0);
}

// ── run the mock's script with a page that answers everything and does nothing ──
const html = readFileSync(MOCK, "utf8");
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
if (!scripts.length) throw new Error("no script in the mock");
const inert = () => {
  const fn = function () {};
  const p = new Proxy(fn, {
    get(_, key) {
      if (key === Symbol.toPrimitive) return () => "";
      if (key === Symbol.iterator) return function* () {};
      if (key === "then") return undefined;
      return p;
    },
    set() { return true; },
    apply() { return p; },
    construct() { return p; },
  });
  return p;
};
const page = inert();
const sandbox = {
  document: page, localStorage: { getItem: () => null, setItem() {} },
  location: { hash: "" }, navigator: page, console, URLSearchParams,
  // The mock measures its icons by drawing them. There is nothing to draw on here, so the picture
  // never arrives and its check never runs; this script measures the ink itself.
  Image: class { decode() { return new Promise(() => {}); } },
};
sandbox.window = sandbox;
vm.createContext(sandbox);
vm.runInContext(`${scripts.join("\n")}\n;globalThis.__tabs = { ICONS, ACC, DEFS, resolve };`, sandbox, { filename: MOCK });
const { ICONS, DEFS, resolve } = sandbox.__tabs;

// ── markup ──
function parseMarkup(src) {
  const root = { tag: "root", attrs: {}, children: [] };
  const stack = [root];
  const re = /<(\/?)([a-zA-Z]+)((?:\s+[a-zA-Z][a-zA-Z0-9:-]*="[^"]*")*)\s*(\/?)>/g;
  for (const m of src.matchAll(re)) {
    const [, close, tag, rawAttrs, self] = m;
    if (close) {
      const top = stack.pop();
      if (top.tag !== tag) throw new Error(`</${tag}> closes <${top.tag}>`);
      continue;
    }
    const attrs = {};
    for (const a of rawAttrs.matchAll(/([a-zA-Z][a-zA-Z0-9:-]*)="([^"]*)"/g)) attrs[a[1]] = a[2];
    const node = { tag, attrs, children: [] };
    stack[stack.length - 1].children.push(node);
    if (!self) stack.push(node);
  }
  if (stack.length !== 1) throw new Error("unclosed element in markup");
  return root;
}

// ── gradients, from the mock's own <defs> ──
const GRADS = {};
for (const g of parseMarkup(DEFS).children) {
  const stops = g.children.filter((s) => s.tag === "stop").map((s) => ({
    offset: +s.attrs.offset,
    color: colour(s.attrs["stop-color"], s.attrs["stop-opacity"] != null ? +s.attrs["stop-opacity"] : 1),
  }));
  if (g.tag === "linearGradient") {
    GRADS[g.attrs.id] = { type: "linear", x1: +(g.attrs.x1 ?? 0), y1: +(g.attrs.y1 ?? 0), x2: +(g.attrs.x2 ?? 1), y2: +(g.attrs.y2 ?? 0), stops };
  } else if (g.tag === "radialGradient") {
    GRADS[g.attrs.id] = { type: "radial", cx: +(g.attrs.cx ?? 0.5), cy: +(g.attrs.cy ?? 0.5), r: +(g.attrs.r ?? 0.5), stops };
  }
}

function colour(c, alpha = 1) {
  const names = { white: "#fff", black: "#000" };
  c = names[c] ?? c;
  let hex = c.replace("#", "");
  if (hex.length === 3) hex = hex.split("").map((ch) => ch + ch).join("");
  if (!/^[0-9a-fA-F]{6}$/.test(hex)) throw new Error(`not a colour: ${c}`);
  const a = Math.round(Math.max(0, Math.min(1, alpha)) * 255).toString(16).padStart(2, "0");
  return `#${a}${hex}`.toUpperCase();
}

// ── path data: parsed to absolute commands, measured, written back out plainly ──
function parsePath(d) {
  const out = [];
  let i = 0;
  const ws = () => { while (i < d.length && /[\s,]/.test(d[i])) i++; };
  const num = () => {
    ws();
    const m = /^[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?/.exec(d.slice(i));
    if (!m) throw new Error(`number expected at ${i} in ${d}`);
    i += m[0].length;
    return +m[0];
  };
  const flag = () => { ws(); const f = d[i++]; if (f !== "0" && f !== "1") throw new Error(`flag expected in ${d}`); return +f; };
  let cx = 0, cy = 0, sx = 0, sy = 0, lastC = null, lastQ = null, cmd = null;
  for (;;) {
    ws();
    if (i >= d.length) break;
    if (/[a-zA-Z]/.test(d[i])) cmd = d[i++];
    else if (!cmd) throw new Error(`path starts without a command: ${d}`);
    const rel = cmd === cmd.toLowerCase();
    const C = cmd.toUpperCase();
    const ox = rel ? cx : 0, oy = rel ? cy : 0;
    if (C === "Z") {
      out.push({ c: "Z" }); cx = sx; cy = sy; lastC = lastQ = null;
      cmd = null;
      continue;
    }
    if (C === "M") {
      cx = num() + ox; cy = num() + oy; sx = cx; sy = cy;
      out.push({ c: "M", p: [cx, cy] });
      cmd = rel ? "l" : "L"; lastC = lastQ = null; continue;
    }
    if (C === "L") { cx = num() + ox; cy = num() + oy; out.push({ c: "L", p: [cx, cy] }); lastC = lastQ = null; continue; }
    if (C === "H") { cx = num() + ox; out.push({ c: "L", p: [cx, cy] }); lastC = lastQ = null; continue; }
    if (C === "V") { cy = num() + oy; out.push({ c: "L", p: [cx, cy] }); lastC = lastQ = null; continue; }
    if (C === "C" || C === "S") {
      let x1, y1;
      if (C === "C") { x1 = num() + ox; y1 = num() + oy; }
      else if (lastC) { x1 = 2 * cx - lastC[0]; y1 = 2 * cy - lastC[1]; }
      else { x1 = cx; y1 = cy; }
      const x2 = num() + ox, y2 = num() + oy, x = num() + ox, y = num() + oy;
      out.push({ c: "C", p: [x1, y1, x2, y2, x, y], from: [cx, cy] });
      lastC = [x2, y2]; lastQ = null; cx = x; cy = y; continue;
    }
    if (C === "Q" || C === "T") {
      let x1, y1;
      if (C === "Q") { x1 = num() + ox; y1 = num() + oy; }
      else if (lastQ) { x1 = 2 * cx - lastQ[0]; y1 = 2 * cy - lastQ[1]; }
      else { x1 = cx; y1 = cy; }
      const x = num() + ox, y = num() + oy;
      out.push({ c: "Q", p: [x1, y1, x, y], from: [cx, cy] });
      lastQ = [x1, y1]; lastC = null; cx = x; cy = y; continue;
    }
    if (C === "A") {
      const rx = num(), ry = num(), rot = num(), large = flag(), sweep = flag();
      const x = num() + ox, y = num() + oy;
      out.push({ c: "A", p: [rx, ry, rot, large, sweep, x, y], from: [cx, cy] });
      cx = x; cy = y; lastC = lastQ = null; continue;
    }
    throw new Error(`unknown command ${cmd} in ${d}`);
  }
  return out;
}

const fmt = (n) => {
  const r = Math.round(n * 1000) / 1000;
  return Object.is(r, -0) ? "0" : String(r);
};
function writePath(segs) {
  return segs.map((s) => {
    if (s.c === "Z") return "Z";
    if (s.c === "A") {
      const [rx, ry, rot, large, sweep, x, y] = s.p;
      return `A${fmt(rx)},${fmt(ry)} ${fmt(rot)} ${large},${sweep} ${fmt(x)},${fmt(y)}`;
    }
    const pts = [];
    for (let k = 0; k < s.p.length; k += 2) pts.push(`${fmt(s.p[k])},${fmt(s.p[k + 1])}`);
    return s.c + pts.join(" ");
  }).join(" ");
}

/** Points along the outline, enough to measure it by: curves and arcs are sampled. */
function samples(segs) {
  const pts = [];
  for (const s of segs) {
    if (s.c === "Z") continue;
    if (s.c === "M" || s.c === "L") { pts.push([s.p[0], s.p[1]]); continue; }
    const N = 32;
    if (s.c === "C") {
      const [x0, y0] = s.from, [x1, y1, x2, y2, x, y] = s.p;
      for (let k = 0; k <= N; k++) {
        const t = k / N, u = 1 - t;
        pts.push([u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x,
          u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y]);
      }
      continue;
    }
    if (s.c === "Q") {
      const [x0, y0] = s.from, [x1, y1, x, y] = s.p;
      for (let k = 0; k <= N; k++) {
        const t = k / N, u = 1 - t;
        pts.push([u * u * x0 + 2 * u * t * x1 + t * t * x, u * u * y0 + 2 * u * t * y1 + t * t * y]);
      }
      continue;
    }
    if (s.c === "A") pts.push(...arcPoints(s.from, s.p, 64));
  }
  return pts;
}

// SVG 1.1 appendix F.6.5: an arc from its endpoints to its centre, then sampled.
function arcPoints([x1, y1], [rx0, ry0, rotDeg, large, sweep, x2, y2], N) {
  let rx = Math.abs(rx0), ry = Math.abs(ry0);
  if (rx === 0 || ry === 0) return [[x2, y2]];
  const phi = (rotDeg * Math.PI) / 180, cos = Math.cos(phi), sin = Math.sin(phi);
  const dx = (x1 - x2) / 2, dy = (y1 - y2) / 2;
  const x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
  const lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
  if (lam > 1) { rx *= Math.sqrt(lam); ry *= Math.sqrt(lam); }
  const num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
  const den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
  let co = Math.sqrt(Math.max(0, num / den));
  if (large === sweep) co = -co;
  const cxp = (co * rx * y1p) / ry, cyp = (-co * ry * x1p) / rx;
  const cx = cos * cxp - sin * cyp + (x1 + x2) / 2, cy = sin * cxp + cos * cyp + (y1 + y2) / 2;
  const ang = (ux, uy, vx, vy) => {
    const a = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
    return a;
  };
  const t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
  let dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
  if (!sweep && dt > 0) dt -= 2 * Math.PI;
  if (sweep && dt < 0) dt += 2 * Math.PI;
  const pts = [];
  for (let k = 0; k <= N; k++) {
    const t = t1 + (dt * k) / N;
    const ex = rx * Math.cos(t), ey = ry * Math.sin(t);
    pts.push([cos * ex - sin * ey + cx, sin * ex + cos * ey + cy]);
  }
  return pts;
}

function bounds(pts) {
  let l = Infinity, t = Infinity, r = -Infinity, b = -Infinity;
  for (const [x, y] of pts) { l = Math.min(l, x); t = Math.min(t, y); r = Math.max(r, x); b = Math.max(b, y); }
  return { l, t, r, b, w: r - l, h: b - t };
}

// ── shapes as path data ──
function rectPath(a) {
  const x = +(a.x ?? 0), y = +(a.y ?? 0), w = +a.width, h = +a.height;
  let rx = a.rx != null ? +a.rx : a.ry != null ? +a.ry : 0;
  let ry = a.ry != null ? +a.ry : rx;
  rx = Math.min(rx, w / 2); ry = Math.min(ry, h / 2);
  if (!rx || !ry) return `M${x},${y} H${x + w} V${y + h} H${x}Z`;
  return `M${x + rx},${y} H${x + w - rx} A${rx},${ry} 0 0 1 ${x + w},${y + ry} V${y + h - ry} ` +
    `A${rx},${ry} 0 0 1 ${x + w - rx},${y + h} H${x + rx} A${rx},${ry} 0 0 1 ${x},${y + h - ry} ` +
    `V${y + ry} A${rx},${ry} 0 0 1 ${x + rx},${y}Z`;
}
const ellipsePath = (cx, cy, rx, ry) =>
  `M${cx - rx},${cy} A${rx},${ry} 0 1 0 ${cx + rx},${cy} A${rx},${ry} 0 1 0 ${cx - rx},${cy}Z`;

// ── transforms: each function one group, outermost first, as SVG composes them ──
function parseTransform(t) {
  if (!t) return [];
  return [...t.matchAll(/([a-z]+)\(([^)]*)\)/g)].map(([, fn, args]) => {
    const a = args.trim().split(/[\s,]+/).map(Number);
    if (fn === "translate") return { attrs: { translateX: a[0], translateY: a[1] ?? 0 }, m: [1, 0, 0, 1, a[0], a[1] ?? 0] };
    if (fn === "scale") return { attrs: { scaleX: a[0], scaleY: a[1] ?? a[0] }, m: [a[0], 0, 0, a[1] ?? a[0], 0, 0] };
    if (fn === "rotate") {
      const r = (a[0] * Math.PI) / 180, c = Math.cos(r), s = Math.sin(r), px = a[1] ?? 0, py = a[2] ?? 0;
      return { attrs: { rotation: a[0], pivotX: px, pivotY: py }, m: [c, s, -s, c, px - c * px + s * py, py - s * px - c * py] };
    }
    throw new Error(`transform ${fn} has no vector drawable form`);
  });
}
const mul = (A, B) => [
  A[0] * B[0] + A[2] * B[1], A[1] * B[0] + A[3] * B[1],
  A[0] * B[2] + A[2] * B[3], A[1] * B[2] + A[3] * B[3],
  A[0] * B[4] + A[2] * B[5] + A[4], A[1] * B[4] + A[3] * B[5] + A[5],
];
const apply = (m, [x, y]) => [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
const ID = [1, 0, 0, 1, 0, 0];

const INHERITED = ["fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "opacity"];

/**
 * One icon's markup as vector drawable elements, and how far its ink reaches from the centre.
 */
function convert(markup) {
  const lines = [];
  let reach = 0;
  const walk = (node, inh, m, depth) => {
    const style = { ...inh };
    for (const k of INHERITED) {
      if (node.attrs[k] == null) continue;
      // Opacity multiplies down: a vector drawable group has none, so each shape carries it.
      style[k] = k === "opacity" ? (inh.opacity ?? 1) * +node.attrs[k] : node.attrs[k];
    }
    if (node.attrs["stroke-dasharray"]) throw new Error("stroke-dasharray has no vector drawable form");
    const tf = parseTransform(node.attrs.transform);
    let mm = m;
    const pad = "    ".repeat(depth);
    let d = depth;
    for (const t of tf) {
      lines.push(`${"    ".repeat(d)}<group ${Object.entries(t.attrs).map(([k, v]) => `android:${k}="${fmt(v)}"`).join(" ")}>`);
      mm = mul(mm, t.m);
      d++;
    }
    if (node.tag === "g" || node.tag === "root") {
      for (const c of node.children) walk(c, style, mm, d);
    } else {
      reach = Math.max(reach, shape(node, style, mm, d, lines));
    }
    for (let k = tf.length - 1; k >= 0; k--) lines.push(`${"    ".repeat(depth + k)}</group>`);
    void pad;
  };
  walk(parseMarkup(markup), {}, ID, 1);
  return { xml: lines.join("\n"), reach };
}

function shape(node, style, m, depth, lines) {
  const a = node.attrs;
  let d;
  let ellipse = null;
  if (node.tag === "rect") d = rectPath(a);
  else if (node.tag === "circle") { ellipse = [+a.cx, +a.cy, +a.r, +a.r]; d = ellipsePath(...ellipse); }
  else if (node.tag === "ellipse") { ellipse = [+a.cx, +a.cy, +a.rx, +a.ry]; d = ellipsePath(...ellipse); }
  else if (node.tag === "path") d = a.d;
  else throw new Error(`<${node.tag}> has no vector drawable form`);
  const segs = parsePath(d);
  const pts = samples(segs);
  const box = bounds(pts);

  const opacity = style.opacity ?? 1;
  const fill = style.fill ?? "#000";
  const stroke = style.stroke && style.stroke !== "none" ? style.stroke : null;
  const sw = stroke ? +(style["stroke-width"] ?? 1) : 0;

  // How far the ink reaches, after every transform, the stroke's half width included. Measured
  // as the mock measures it: only what paints at least 40 of 255 counts, so a shadow fading out on
  // the ground or a faint glaze is not ink.
  const solid = (paint, alpha) => {
    if (!paint || paint === "none") return false;
    if (paint.startsWith("url(")) {
      const g = GRADS[paint.slice(5, -1)];
      return g && Math.max(...g.stops.map((st) => parseInt(st.color.slice(1, 3), 16) / 255)) * alpha >= INK_ALPHA;
    }
    return alpha >= INK_ALPHA;
  };
  const counts = solid(fill, +(style["fill-opacity"] ?? 1) * opacity) || solid(stroke, +(style["stroke-opacity"] ?? 1) * opacity);
  const ground = fill.startsWith("url(") && GRADS[fill.slice(5, -1)]?.stops.at(-1)?.color.startsWith("#00");
  let reach = 0;
  const k = Math.sqrt(Math.abs(m[0] * m[3] - m[1] * m[2]));
  if (counts && !ground) {
    for (const p of pts) {
      const [x, y] = apply(m, p);
      reach = Math.max(reach, Math.hypot(x - 54, y - 54) + (sw * k) / 2);
    }
  }
  if (fill === "none" && !stroke) return 0;

  // A radial fade on an ellipse is drawn on a circle squashed by a group: a vector drawable's
  // radial gradient is round, and the mock's is fitted to the ellipse's own box.
  const radialOnEllipse = fill.startsWith("url(") && GRADS[fill.slice(5, -1)]?.type === "radial" && ellipse && ellipse[2] !== ellipse[3];
  let pad = "    ".repeat(depth);
  let pathData = writePath(segs);
  let gradBox = box;
  if (radialOnEllipse) {
    const [cx, cy, rx, ry] = ellipse;
    lines.push(`${pad}<group android:pivotX="${fmt(cx)}" android:pivotY="${fmt(cy)}" android:scaleY="${fmt(ry / rx)}">`);
    pad += "    ";
    pathData = writePath(parsePath(ellipsePath(cx, cy, rx, rx)));
    gradBox = { l: cx - rx, t: cy - rx, w: 2 * rx, h: 2 * rx };
  }
  const attrs = [`android:pathData="${pathData}"`];
  const children = [];
  const paint = (value, which, alpha) => {
    if (value.startsWith("url(")) {
      const g = GRADS[value.slice(5, -1)];
      if (!g) throw new Error(`no gradient ${value}`);
      children.push(gradientXml(g, gradBox, which, pad + "    "));
    } else {
      attrs.push(`android:${which}="${colour(value)}"`);
    }
    if (alpha !== 1) attrs.push(`android:${which === "fillColor" ? "fillAlpha" : "strokeAlpha"}="${fmt(alpha)}"`);
  };
  if (fill !== "none") paint(fill, "fillColor", +(style["fill-opacity"] ?? 1) * opacity);
  if (style["fill-rule"] === "evenodd") attrs.push(`android:fillType="evenOdd"`);
  if (stroke) {
    paint(stroke, "strokeColor", +(style["stroke-opacity"] ?? 1) * opacity);
    attrs.push(`android:strokeWidth="${fmt(sw)}"`);
    if (style["stroke-linecap"]) attrs.push(`android:strokeLineCap="${style["stroke-linecap"]}"`);
    if (style["stroke-linejoin"]) attrs.push(`android:strokeLineJoin="${style["stroke-linejoin"]}"`);
  }
  if (children.length) {
    lines.push(`${pad}<path ${attrs.join(" ")}>`);
    lines.push(...children);
    lines.push(`${pad}</path>`);
  } else {
    lines.push(`${pad}<path ${attrs.join(" ")} />`);
  }
  if (radialOnEllipse) lines.push(`${"    ".repeat(depth)}</group>`);
  return reach;
}

function gradientXml(g, box, which, pad) {
  const items = g.stops.map((s) => `${pad}        <item android:offset="${fmt(s.offset)}" android:color="${s.color}" />`).join("\n");
  const geo = g.type === "linear"
    ? `android:type="linear" android:startX="${fmt(box.l + g.x1 * box.w)}" android:startY="${fmt(box.t + g.y1 * box.h)}" ` +
      `android:endX="${fmt(box.l + g.x2 * box.w)}" android:endY="${fmt(box.t + g.y2 * box.h)}"`
    : (() => {
      if (Math.abs(box.w - box.h) > 0.01) throw new Error("a radial gradient on a shape that is not round or an ellipse");
      return `android:type="radial" android:centerX="${fmt(box.l + g.cx * box.w)}" android:centerY="${fmt(box.t + g.cy * box.h)}" ` +
        `android:gradientRadius="${fmt(g.r * box.w)}"`;
    })();
  return `${pad}<aapt:attr name="android:${which}">\n${pad}    <gradient ${geo}>\n${items}\n${pad}    </gradient>\n${pad}</aapt:attr>`;
}

const vector = (body, note) => `<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by tools/make-tab-icons.mjs from the tab icon mock. ${note} Do not edit: change the mock and run it again. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
${body}
</vector>
`;

// The mono glyph sits where the mock puts it: its 24-unit box scaled to 44 units about the centre,
// a round line 1.8 wide. A themed home screen reads only its shape and paints it in its own colour.
const monoMarkup = (icon) =>
  `<g transform="translate(32 32) scale(1.8333)" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">` +
  icon.mono.map((d) => `<path d="${d}"/>`).join("") + `</g>`;

// ── write ──
const files = new Map();
const put = (rel, text) => files.set(rel, text);

put("drawable/ic_tab_bg.xml", vector(convert(`<rect x="0" y="0" width="108" height="108" fill="url(#tileSoft)"/>`).xml, "The tile every tab icon sits on."));

const problems = [];
const rows = [];
for (const icon of ICONS) {
  if (!/^[a-z]+$/.test(icon.id)) throw new Error(`icon id ${icon.id} is not a resource name`);
  const art = convert(resolve(icon));
  if (art.reach > SAFE + 0.05) problems.push(`${icon.id}: ink reaches ${art.reach.toFixed(1)} from the centre, past the ${SAFE} safe circle`);
  put(`drawable/ic_tab_${icon.id}_fg.xml`, vector(art.xml, `${icon.label}: the art.`));
  put(`drawable/ic_tab_${icon.id}_mono.xml`, vector(convert(monoMarkup(icon)).xml, `${icon.label}: the themed glyph.`));
  put(`mipmap-anydpi/ic_tab_${icon.id}.xml`, `<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by tools/make-tab-icons.mjs from the tab icon mock. ${icon.label}. Do not edit. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_tab_bg" />
    <foreground android:drawable="@drawable/ic_tab_${icon.id}_fg" />
    <monochrome android:drawable="@drawable/ic_tab_${icon.id}_mono" />
</adaptive-icon>
`);
  rows.push(icon);
}

const kt = [
  "// GENERATED by tools/make-tab-icons.mjs from the tab icon mock. Do not edit: change the mock and run it again.",
  "package dev.niccc2007.filet.shortcuts",
  "",
  "import dev.niccc2007.filet.R",
  "",
  "/** Each tab's and app action's icon, by its id in the tab icon mock. */",
  "internal object TabIconRes {",
  "    /** The adaptive launcher icon: Filet's tile, the art, and the glyph a themed home screen draws. */",
  "    val LAUNCHER: Map<String, Int> = mapOf(",
  ...rows.map((i) => `        "${i.id}" to R.mipmap.ic_tab_${i.id},`),
  "    )",
  "",
  "    /** The art alone, on the 108-unit canvas, for drawing inside the app. */",
  "    val ART: Map<String, Int> = mapOf(",
  ...rows.map((i) => `        "${i.id}" to R.drawable.ic_tab_${i.id}_fg,`),
  "    )",
  "}",
  "",
].join("\n");
files.set("KT", kt);

const target = (rel) => (rel === "KT" ? KT : join(RES, rel));
let stale = [];
for (const [rel, text] of files) {
  const p = target(rel);
  const now = existsSync(p) ? readFileSync(p, "utf8") : null;
  if (now === text) continue;
  if (check) { stale.push(rel === "KT" ? "shortcuts/TabIconRes.kt" : rel); continue; }
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, text);
}
// A tab icon the mock no longer has must not linger as a resource nobody draws.
const known = new Set([...files.keys()].filter((k) => k !== "KT").map((k) => k.split("/").pop()));
for (const dir of ["drawable", "mipmap-anydpi"]) {
  for (const f of readdirSync(join(RES, dir))) {
    if (f.startsWith("ic_tab_") && !known.has(f)) stale.push(`${dir}/${f} (no longer in the mock)`);
  }
}

const summary = `${rows.length} icons, ${files.size - 1} files, ink within the ${SAFE} circle`;
if (problems.length) {
  console.error("TABICONS PROBLEMS:\n  " + problems.join("\n  "));
  process.exit(1);
}
if (check) {
  if (stale.length) {
    console.error(`TABICONS STALE: ${stale.length} file(s) differ from the mock. Run: node tools/make-tab-icons.mjs\n  ` + stale.slice(0, 12).join("\n  "));
    process.exit(1);
  }
  console.log(`TABICONS OK  (${summary})`);
} else {
  console.log(`wrote ${summary}`);
}
