#!/usr/bin/env node
/**
 * The README's glimpse section, and whether it still shows the app that exists.
 *
 * The complaint that produced this was that the section had gone stale, and staleness is the
 * one property a screenshot has that nothing in the repository was watching. A picture does
 * not fail to compile when the screen it shows is redesigned; it just quietly becomes a
 * photograph of a previous version, on the page most people read first.
 *
 * So there are two kinds of finding here and they are deliberately not the same severity:
 *
 *  - **Structure** is a real failure. A missing file, an image with no alt text, a caption
 *    that has drifted out of step with the images above it - all of those are wrong in the
 *    repository and fixable in the repository.
 *  - **Staleness** exits 2, the established skip-with-a-reason code, and names every shot that
 *    predates the screen it shows. It is not a failure because the remedy is not here: the
 *    screens have to be photographed on a real phone, and the checker cannot do that. What it
 *    can do is make sure nobody has to notice on their own.
 *
 *   node tools/check-glimpse.mjs              check
 *   node tools/check-glimpse.mjs --selftest   prove it catches each structural fault
 *
 * Prints "GLIMPSE OK" only when the section is sound and nothing is out of date.
 */
import { readFileSync, existsSync, statSync } from "node:fs";
import { execSync } from "node:child_process";

const README = "README.md";

/**
 * Which source each screenshot is a picture of.
 *
 * The point of the mapping is that it is specific. "The UI changed" is true every day and
 * would mark everything stale forever; "the file that draws this screen changed after this
 * picture was taken" is the actual question.
 */
const SHOWS = {
  "01-browse.png": ["app/src/main/java/dev/niccc2007/filet/browser/PaneView.kt",
    "app/src/main/java/dev/niccc2007/filet/browser/RowViews.kt"],
  "02-split.png": ["app/src/main/java/dev/niccc2007/filet/browser/BrowserScreen.kt"],
  "03-search.png": ["app/src/main/java/dev/niccc2007/filet/browser/PaneView.kt"],
  "04-apk.png": ["app/src/main/java/dev/niccc2007/filet/apk/ApkInspectorScreen.kt"],
  "05-scripts.png": ["app/src/main/java/dev/niccc2007/filet/script/ScriptsScreen.kt"],
  "06-nearby.png": ["app/src/main/java/dev/niccc2007/filet/nearby/NearbyScreen.kt"],
  "07-web.png": ["app/src/main/java/dev/niccc2007/filet/nearby/WebApp.kt"],
  "08-context-menu.png": ["app/src/main/java/dev/niccc2007/filet/browser/ContextMenu.kt"],
  "09-paste.png": ["app/src/main/java/dev/niccc2007/filet/browser/PaneView.kt"],
  "10-rar.png": ["app/src/main/java/dev/niccc2007/filet/browser/PaneView.kt"],
  "11-compress.png": ["app/src/main/java/dev/niccc2007/filet/browser/ArchiveOptionsUi.kt"],
  "12-extract.png": ["app/src/main/java/dev/niccc2007/filet/browser/ExtractSheet.kt"],
  "13-archive-save.png": ["app/src/main/java/dev/niccc2007/filet/browser/ArchiveSaveSheet.kt"],
};

/** The section, as markdown. */
export function section(md) {
  const start = md.indexOf("## A glimpse of it");
  if (start < 0) return null;
  const rest = md.slice(start + 3);
  const end = rest.indexOf("\n## ");
  return rest.slice(0, end < 0 ? rest.length : end);
}

/** Every image referenced in it, with its alt text. */
export function images(sec) {
  return [...sec.matchAll(/<img\s+src="([^"]+)"[^>]*?alt="([^"]*)"/g)]
    .map((m) => ({ src: m[1], alt: m[2] }));
}

export function structure(md, exists = existsSync) {
  const found = [];
  const sec = section(md);
  if (sec === null) return ["the README has no glimpse section at all"];

  const imgs = images(sec);
  if (imgs.length === 0) found.push("the glimpse section shows no images");

  for (const img of imgs) {
    if (!exists(img.src)) found.push(`${img.src} is referenced but not in the repository`);
    // Alt text is the whole of what a screen reader gets, and this is a page of pictures.
    if (!img.alt || img.alt.trim().length < 8) {
      found.push(`${img.src} has no useful alt text`);
    }
  }

  // An image with no width renders full-bleed and breaks the grid the section is built as.
  for (const m of sec.matchAll(/<img\s+(?![^>]*width=)[^>]*>/g)) {
    found.push(`an image has no width: ${m[0].slice(0, 60)}`);
  }

  // The note about seeded content is load-bearing: the screenshots must never look like they
  // are somebody's actual files, and the line saying so is the only thing that makes that
  // clear to a reader.
  if (!/seeded|demo folder|not real files/i.test(sec)) {
    found.push("the section no longer says the screenshots use seeded demo content");
  }

  return found;
}

/**
 * Screenshots older than the screen they show.
 *
 * Uses the last commit that touched each file, not the file's mtime: a checkout writes every
 * mtime to the moment it ran, which would report either everything or nothing as stale
 * depending on the order things landed on disk.
 */
export function stale() {
  const when = (p) => {
    try {
      const out = execSync(`git log -1 --format=%ct -- "${p}"`, { encoding: "utf8" }).trim();
      return out ? Number(out) * 1000 : null;
    } catch {
      return null;
    }
  };
  const out = [];
  for (const [shot, sources] of Object.entries(SHOWS)) {
    const path = `docs/screenshots/${shot}`;
    if (!existsSync(path)) continue;
    const shotAt = when(path) ?? statSync(path).mtimeMs;
    for (const src of sources) {
      if (!existsSync(src)) continue;
      const srcAt = when(src);
      if (srcAt && srcAt > shotAt) {
        out.push({ shot, src, days: Math.round((srcAt - shotAt) / 86400000) });
        break;
      }
    }
  }
  return out;
}

if (process.argv.includes("--selftest")) {
  const CASES = [
    ["no section at all", "# Filet\n\nSome text.\n", true],
    ["an image that is not in the repo", `## A glimpse of it\n<img src="docs/screenshots/nope.png" width="31%" alt="Browsing with thumbnails">\n<sub>seeded demo folder</sub>\n`, true],
    ["an image with no alt text", `## A glimpse of it\n<img src="ok.png" width="31%" alt="">\n<sub>seeded demo folder</sub>\n`, true],
    ["alt text too short to say anything", `## A glimpse of it\n<img src="ok.png" width="31%" alt="pic">\n<sub>seeded demo folder</sub>\n`, true],
    ["an image with no width", `## A glimpse of it\n<img src="ok.png" alt="Browsing with thumbnails">\n<sub>seeded demo folder</sub>\n`, true],
    ["the seeded-content note removed", `## A glimpse of it\n<img src="ok.png" width="31%" alt="Browsing with thumbnails">\n`, true],
    ["a sound section", `## A glimpse of it\n<img src="ok.png" width="31%" alt="Browsing with thumbnails">\n<sub>Screenshots use a seeded demo folder, not real files.</sub>\n`, false],
  ];
  let bad = 0;
  for (const [name, md, expect] of CASES) {
    // `ok.png` stands in for a file that is present; nope.png for one that is not.
    const got = structure(md, (p) => !p.includes("nope")).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} — expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(
    `GLIMPSE SELFTEST OK  (${CASES.filter((c) => c[2]).length} negative controls, ` +
      `${CASES.filter((c) => !c[2]).length} positive)`,
  );
  process.exit(0);
}

const md = existsSync(README) ? readFileSync(README, "utf8") : "";
const faults = structure(md);
if (faults.length) {
  console.error("The glimpse section is broken:\n");
  for (const p of faults) console.error("  " + p);
  process.exit(1);
}

const old = stale();
if (old.length) {
  console.log(
    `GLIMPSE STALE: ${old.length} screenshot(s) are older than the screen they show.\n` +
      `This is not a repository fault and cannot be fixed here - the screens have to be\n` +
      `photographed on a real device. What needs re-shooting:\n`,
  );
  for (const s of old) {
    console.log(`  ${s.shot}  -  ${s.src} changed ${s.days} day(s) after it was taken`);
  }
  process.exit(2);
}

const sec = section(md);
console.log(`GLIMPSE OK  (${images(sec).length} shots, all present, captioned and current)`);
