#!/usr/bin/env node
/**
 * Write the Settings search index from the rows of the Settings page themselves.
 *
 *   node tools/make-settings-index.mjs           regenerate settings/SettingsIndex.kt
 *   node tools/make-settings-index.mjs --check   exit 1 if the file is not what this would write
 *
 * Every row that names its preference - `key = ...` on a ToggleRow, SliderRow, SegmentRow,
 * BarRow or HoldHint - becomes one entry: the key, the row's title, the tab and section it sits
 * in, and every word written into its declaration (title, hints, option labels). So the search
 * cannot be missing a row that is on the page: adding one changes what this writes, and
 * check-settingsindex fails until the file is regenerated.
 *
 * A row drawn by a helper (`FiletStorageCard`, `RowDetailCard`, ...) takes its tab and section
 * from the place on the page that calls the helper.
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";

export const PAGE = "app/src/main/java/dev/niccc2007/filet/settings/SettingsPage.kt";
export const HELPERS = ["app/src/main/java/dev/niccc2007/filet/settings/RowDetailCard.kt"];
export const OUT = "app/src/main/java/dev/niccc2007/filet/settings/SettingsIndex.kt";

const ROWS = ["ToggleRow", "SliderRow", "SegmentRow", "BarRow", "HoldHint"];

/**
 * Walk Kotlin source, calling [visit] with each index that is code - not inside a string, a char
 * literal or a comment - and [onString] with each string literal's raw body.
 */
function scan(src, visit, onString) {
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    const d = src[i + 1];
    if (c === "/" && d === "/") {
      while (i < n && src[i] !== "\n") i++;
      continue;
    }
    if (c === "/" && d === "*") {
      i = src.indexOf("*/", i + 2);
      i = i < 0 ? n : i + 2;
      continue;
    }
    if (c === '"') {
      const raw = src.startsWith('"""', i);
      const start = i + (raw ? 3 : 1);
      let j = start;
      let depth = 0;
      while (j < n) {
        if (!raw && src[j] === "\\") { j += 2; continue; }
        if (src[j] === "$" && src[j + 1] === "{") { depth++; j += 2; continue; }
        if (depth > 0 && src[j] === "}") { depth--; j++; continue; }
        if (depth === 0 && (raw ? src.startsWith('"""', j) : src[j] === '"')) break;
        j++;
      }
      onString?.(src.slice(start, j), i);
      i = j + (raw ? 3 : 1);
      continue;
    }
    if (c === "'") {
      let j = i + 1;
      while (j < n && src[j] !== "'") j += src[j] === "\\" ? 2 : 1;
      i = j + 1;
      continue;
    }
    visit?.(i);
    i++;
  }
}

/** The index of the bracket closing the one at [open], skipping strings and comments. */
export function closing(src, open) {
  const pairs = { "(": ")", "{": "}", "[": "]" };
  const want = pairs[src[open]];
  let depth = 0;
  let found = -1;
  scan(src.slice(open), (k) => {
    if (found >= 0) return;
    const ch = src[open + k];
    if (ch === src[open]) depth++;
    else if (ch === want && --depth === 0) found = open + k;
  });
  return found;
}

/** Positions of code (not strings or comments) where [re] matches. */
function codeMatches(src, re) {
  const code = new Set();
  scan(src, (k) => code.add(k));
  return [...src.matchAll(re)].filter((m) => code.has(m.index));
}

/** The string literals between [from] and [to], de-templated, in order. */
function literals(src, from, to) {
  const out = [];
  scan(src.slice(from, to), null, (body) => {
    const text = body
      .replace(/\$\{[^}]*\}/g, " ")
      .replace(/\$[A-Za-z_]\w*/g, " ")
      .replace(/\\n/g, " ")
      .replace(/\\(["\\$'])/g, "$1")
      .replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
      .replace(/\s+/g, " ")
      .trim();
    if (/[A-Za-z]{2}/.test(text)) out.push(text);
  });
  return out;
}

/** Each row call that names a key: where it is, its key expression, title and words. */
export function rowsIn(src) {
  const found = [];
  for (const m of codeMatches(src, new RegExp(`\\b(${ROWS.join("|")})\\(`, "g"))) {
    const lineStart = src.lastIndexOf("\n", m.index) + 1;
    if (/\bfun\s/.test(src.slice(lineStart, m.index))) continue;
    const open = m.index + m[0].length - 1;
    const close = closing(src, open);
    if (close < 0) continue;
    const args = src.slice(open + 1, close);
    const key = /(?:^|[\s,(])key\s*=\s*([A-Za-z_][\w.]*)/.exec(args);
    if (!key) continue;
    const words = literals(src, open + 1, close);
    // The title: the first argument when it is written out. A HoldHint's own first argument is
    // its hint, so its name is the first word in the lambda it wraps.
    let title = null;
    if (m[1] === "HoldHint") {
      const brace = src.indexOf("{", close);
      const end = brace >= 0 ? closing(src, brace) : -1;
      title = end > 0 ? literals(src, brace, end)[0] ?? null : null;
      if (end > 0) words.push(...literals(src, brace, end));
    } else if (/^\s*"/.test(args)) {
      title = words[0] ?? null;
    }
    found.push({ at: m.index, kind: m[1], key: key[1], title, words });
  }
  return found;
}

/** Top-level `fun Name(...) { body }` spans, so a row inside one can be traced to its caller. */
function functionsIn(src) {
  const out = [];
  for (const m of codeMatches(src, /\bfun\s+([A-Z]\w*)\s*\(/g)) {
    const paren = m.index + m[0].length - 1;
    const close = closing(src, paren);
    let body = src.indexOf("{", close);
    const eq = src.indexOf("=", close);
    if (eq >= 0 && (body < 0 || eq < body) && !src.slice(close, eq).includes("\n")) body = src.indexOf("{", eq);
    const end = body >= 0 ? closing(src, body) : -1;
    if (end > 0) out.push({ name: m[1], from: m.index, to: end });
  }
  return out;
}

/** The page's list: where it starts and ends, its tab blocks and its section headings. */
export function pageLayout(src) {
  const lazy = codeMatches(src, /LazyColumn\(/g)[0];
  if (!lazy) throw new Error("no LazyColumn in the Settings page");
  const open = src.indexOf("{", closing(src, lazy.index + "LazyColumn".length));
  const close = closing(src, open);
  const tabs = codeMatches(src, /if \(group == SettingsGroup\.(\w+)\)\s*\{/g)
    .filter((m) => m.index > open && m.index < close)
    .map((m) => {
      const brace = m.index + m[0].length - 1;
      return { tab: m[1], from: brace, to: closing(src, brace) };
    });
  const sections = codeMatches(src, /SettingsSection\(/g)
    .filter((m) => m.index > open && m.index < close)
    .map((m) => {
      const p = m.index + "SettingsSection".length;
      return { at: m.index, title: literals(src, p, closing(src, p))[0] ?? "" };
    });
  return { open, close, tabs, sections };
}

/** Each entry, merged by key: the first title wins, the words are pooled. */
export function entries(page, helpers = []) {
  const layout = pageLayout(page);
  const placed = [];
  const fnsByName = new Map();
  for (const src of [page, ...helpers]) {
    for (const f of functionsIn(src)) if (!fnsByName.has(f.name)) fnsByName.set(f.name, { ...f, src });
  }
  // Where on the page a position is: rows on the list directly, or the call to their helper.
  const where = (src, at, seen = new Set()) => {
    if (src === page && at > layout.open && at < layout.close) return at;
    const fn = [...fnsByName.values()].find((f) => f.src === src && at > f.from && at < f.to);
    if (!fn || seen.has(fn.name)) return null;
    seen.add(fn.name);
    for (const call of codeMatches(page, new RegExp(`\\b${fn.name}\\(`, "g"))) {
      if (call.index > layout.open && call.index < layout.close) return call.index;
    }
    for (const call of codeMatches(page, new RegExp(`\\b${fn.name}\\(`, "g"))) {
      const up = where(page, call.index, seen);
      if (up != null) return up;
    }
    return null;
  };
  for (const src of [page, ...helpers]) {
    for (const r of rowsIn(src)) {
      const at = where(src, r.at);
      if (at == null) continue;
      const tab = layout.tabs.filter((t) => at > t.from && at < t.to).pop()?.tab;
      if (!tab) continue;
      const section = layout.sections.filter((s) => s.at < at).pop()?.title ?? "";
      placed.push({ ...r, pageAt: at, tab, section });
    }
  }
  const byKey = new Map();
  for (const p of placed.sort((a, b) => a.pageAt - b.pageAt)) {
    const e = byKey.get(p.key);
    if (!e) {
      byKey.set(p.key, { key: p.key, title: p.title ?? p.section, tab: p.tab, section: p.section, words: [...p.words], at: p.pageAt });
    } else {
      e.words.push(...p.words);
    }
  }
  return [...byKey.values()].map((e) => ({ ...e, text: [...new Set(e.words)].join(" ") }));
}

/** [text] in pieces of at most [width] characters, broken after a space. */
function wrap(text, width) {
  const out = [];
  let rest = text;
  while (rest.length > width) {
    let cut = rest.lastIndexOf(" ", width);
    if (cut <= 0) cut = width;
    out.push(rest.slice(0, cut + 1));
    rest = rest.slice(cut + 1);
  }
  out.push(rest);
  return out;
}

const kt = (s) => '"' + s.replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\$/g, "\\$") + '"';

/** The Kotlin file for [list]. */
export function render(list) {
  const lines = [
    "// GENERATED by tools/make-settings-index.mjs from the rows in SettingsPage.kt. Do not edit:",
    "// run `node tools/make-settings-index.mjs`, and check-settingsindex keeps the two in step.",
    "package dev.niccc2007.filet.settings",
    "",
    "import dev.niccc2007.filet.data.PrefKeys",
    "import dev.niccc2007.filet.data.Prefs",
    "",
    "/** Every setting on the page that names its key, in page order. See [SettingsEntry]. */",
    "internal val SETTINGS_INDEX: List<SettingsEntry> = listOf(",
  ];
  for (const e of list) {
    lines.push("    SettingsEntry(");
    lines.push(`        key = ${e.key},`);
    lines.push(`        title = ${kt(e.title)},`);
    lines.push(`        tab = SettingsGroup.${e.tab},`);
    lines.push(`        section = ${kt(e.section)},`);
    // Wrapped at word boundaries into joined pieces, so no line runs past the prose limit.
    const pieces = wrap(e.text, 76);
    if (pieces.length === 1) {
      lines.push(`        text = ${kt(pieces[0])},`);
    } else {
      lines.push(`        text = ${kt(pieces[0])} +`);
      pieces.slice(1).forEach((p, i) => lines.push(`            ${kt(p)}${i === pieces.length - 2 ? "," : " +"}`));
    }
    lines.push("    ),");
  }
  lines.push(")", "");
  return lines.join("\n");
}

export function build() {
  const page = readFileSync(PAGE, "utf8");
  const helpers = HELPERS.filter(existsSync).map((f) => readFileSync(f, "utf8"));
  return render(entries(page, helpers));
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, "/").split("/").pop());
if (isMain) {
  const wanted = build();
  if (process.argv.includes("--check")) {
    const have = existsSync(OUT) ? readFileSync(OUT, "utf8") : "";
    process.exit(have === wanted ? 0 : 1);
  }
  writeFileSync(OUT, wanted);
  const n = (wanted.match(/SettingsEntry\(/g) || []).length;
  console.log(`SETTINGS INDEX written  (${n} settings)`);
}
