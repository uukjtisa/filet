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
 * The composite the section leads with, and the tool that builds it.
 *
 * The banner is generated from the shots below, so it has a failure mode the shots do not: a
 * re-shoot that is committed without re-running the tool leaves the README showing the old
 * screens inside a new-looking image, which is worse than a stale strip because it does not
 * look stale. Unlike staleness, that IS fixable here - it is one command - so it is a failure
 * rather than a skip.
 */
const BANNER = "docs/glimpse.png";
const BANNER_TOOL = "tools/make-glimpse.py";

/**
 * Which source each screenshot is a picture of.
 *
 * The point of the mapping is that it is specific. "The UI changed" is true every day and
 * would mark everything stale forever; "the file that draws this screen changed after this
 * picture was taken" is the actual question.
 */
const SHOWS = {
  "01-home.png": ["app/src/main/java/dev/niccc2007/filet/home/HomeOverview.kt",
    "app/src/main/java/dev/niccc2007/filet/ui/tabs/TabKit.kt"],
  "02-browse.png": ["app/src/main/java/dev/niccc2007/filet/browser/PaneView.kt",
    "app/src/main/java/dev/niccc2007/filet/browser/RowViews.kt"],
  "03-new-files.png": ["app/src/main/java/dev/niccc2007/filet/home/FileHistoryScreen.kt"],
  "04-view.png": ["app/src/main/java/dev/niccc2007/filet/browser/Menus.kt"],
  "05-context-menu.png": ["app/src/main/java/dev/niccc2007/filet/browser/ContextMenu.kt"],
  "06-extract.png": ["app/src/main/java/dev/niccc2007/filet/browser/ExtractSheet.kt"],
  "07-scripts.png": ["app/src/main/java/dev/niccc2007/filet/script/ScriptsScreen.kt"],
  "08-nearby.png": ["app/src/main/java/dev/niccc2007/filet/nearby/NearbyScreen.kt"],
  "09-remotes.png": ["app/src/main/java/dev/niccc2007/filet/remotes/RemotesScreen.kt",
    "app/src/main/java/dev/niccc2007/filet/remotes/HostingCard.kt"],
  "10-metadata.png": ["app/src/main/java/dev/niccc2007/filet/metadata/MetadataScreen.kt"],
  "11-image-editor.png": ["app/src/main/java/dev/niccc2007/filet/handlers/ImageScreen.kt"],
  "12-hex.png": ["app/src/main/java/dev/niccc2007/filet/handlers/Viewers.kt"],
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
  // The banner opens the README rather than living in the glimpse section - it is the first
  // impression, and burying it under a table of contents wastes it. So it is looked for in the
  // whole file, while the section below still has to carry the detail shots.
  if (!md.includes(BANNER)) {
    found.push(`${BANNER} is not in the README at all`);
  }
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
/** The last commit that touched a path, in milliseconds, or null. */
export function committedAt(p, run = execSync) {
  try {
    const out = run(`git log -1 --format=%ct -- "${p}"`, { encoding: "utf8" }).trim();
    return out ? Number(out) * 1000 : null;
  } catch {
    return null;
  }
}

/** Shots that have been re-taken since the banner was last built from them. */
export function bannerBehind() {
  if (!existsSync(BANNER)) return ["(the banner itself is missing)"];
  const bannerAt = committedAt(BANNER) ?? statSync(BANNER).mtimeMs;
  const behind = [];
  for (const shot of Object.keys(SHOWS)) {
    const path = `docs/screenshots/${shot}`;
    if (!existsSync(path)) continue;
    const shotAt = committedAt(path);
    if (shotAt && shotAt > bannerAt) behind.push(shot);
  }
  return behind;
}

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
    if (!existsSync(path)) {
      // Not skipped. A shot named here and absent from disk means the map and the README
      // have drifted, and silently skipping is exactly how renaming every file turned this
      // whole check into a no-op that still printed OK.
      out.push({ shot, src: "(missing from docs/screenshots)", days: 0 });
      continue;
    }
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
    ["the banner dropped from the section", `## A glimpse of it\n<img src="ok.png" width="31%" alt="Browsing with thumbnails">\n<sub>seeded demo folder</sub>\n`, true],
    ["a sound section", `## A glimpse of it\n<img src="docs/glimpse.png" width="100%" alt="Browsing with thumbnails">\n<sub>Screenshots use a seeded demo folder, not real files.</sub>\n`, false],
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
for (const shot of bannerBehind()) {
  faults.push(
    `${shot} was re-taken after the banner was built - run \`python ${BANNER_TOOL}\``,
  );
}
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
console.log(
  `GLIMPSE OK  (a banner at the top built from ${Object.keys(SHOWS).length} shots, ` +
    `${images(sec).length} detail shots below it, all present, captioned and current)`,
);
