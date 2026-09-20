#!/usr/bin/env node
/**
 * Build the translation spec: every surface in the mock, beside the file and line that draws
 * it in the app today.
 *
 * A mock reliably loses fidelity on the way into real code, because the implementer re-derives
 * values by eye instead of reading them off, and because a surface that was never located is a
 * surface that quietly does not get done. Both failures are the same failure: nobody wrote
 * down where the thing lives.
 *
 * So the citations are generated, never typed. A hand-written line number rots on the next
 * edit, and a rotted citation is worse than none - it sends somebody to the wrong place and
 * looks authoritative doing it. This greps the real source every time it runs.
 *
 * It is deliberately noisy about what it cannot find. A surface with no anchor in the app is
 * either new work or a rename, and both need saying out loud rather than leaving a blank cell.
 *
 *   node tools/make-redesign-spec.mjs            write docs/early_mockups_preDev/REDESIGN-SPEC.md
 *   node tools/make-redesign-spec.mjs --check    fail if the file on disk is out of date
 *
 * Prints "REDESIGN SPEC OK" when the written file matches what the source says.
 */
import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const OUT = "docs/early_mockups_preDev/REDESIGN-SPEC.md";
const ROOTS = ["app/src/main/java", "core-index/src/main/java", "core-vfs/src/main/java"];

/**
 * Every surface the redesign covers.
 *
 * `anchor` is what to look for in the Kotlin. `mock` is the function or selector that draws it
 * in filet-redesign-mock.html, so the two can be put side by side. `note` is the one thing
 * about this surface that is easiest to lose in translation - it is here because that is the
 * sentence somebody will wish had been written down.
 */
const SURFACES = [
  // ── tabs ──
  ["Tab strip", "fun TabStrip", "[data-r=3] .tab", "N54",
   "A raised capsule on a recessed track, one accent hairline that moves. Not a border per tab."],
  ["Home tab", "fun HomeOverview", "TABS3.home", "N59",
   "Storage tiles first, then arrivals, then recents, then places. The long heading stays here."],
  ["New files tab", "fun FileHistoryScreen", "TABS3.tracked", "N53",
   "Tab label and path read New files; the heading on Home reads the long name."],
  ["Nearby tab", "fun NearbyScreen", "TABS3.nearby", "N59",
   "The QR, the URL and the code are one card. Running state is a dot, not a word."],
  ["Scripts tab", "fun ScriptsScreen", "TABS3.scripts", "N59",
   "Each script shows a snippet. A name alone says nothing about what a script does."],
  ["Bookmarks tab", "fun BookmarksBody", "TABS3.bookmarks", "N59",
   "A bookmark can point at a file, so the icon is the file kind and not always a star."],
  ["Recent tab", "fun RecentBody", "TABS3.recent", "N59",
   "Grouped by day. Long press removes one entry - it used to do nothing."],
  ["Shortcuts tab", "fun ShortcutsScreen", "TABS3.shortcuts", "N59",
   "Shows shortcuts the system has dropped, which is the only place they can be cleaned up."],
  ["Remotes tab", "fun RemotesScreen", "TABS3.remotes", "N59",
   "An unreachable remote is shown as unreachable and never waited for."],
  ["Activity tab", "fun ActivitySheet", "TABS3.activity", "N59",
   "Running above finished. The index job names the folder it is reading."],
  ["About tab", "fun AboutPage", "aboutHTML", "N58",
   "Four words from the repo, in one line. Watermark is the app mark, not the author seal."],
  ["Settings tab", "fun SettingsPage", "setHTML", "N56",
   "Sub-tabs. The List pane carries a live view rendered by the same code as the real list."],

  // ── rows and chrome ──
  ["File row", "fun FileRow", "rowsHTML3", "N55",
   "No date column. Files show bytes, folders show item count. Tabular figures, not a code font."],
  ["Search result row", "fun SearchResultRow", "SearchResultRow", "N79",
   "Carries Available or Confirming while a pass runs, and nothing between passes."],
  ["Scope chips", "fun ScopeChips", ".scopes", "N71",
   "Native search is dimmed and unpressable where the index never answers the scope anyway."],
  ["Bottom bar", "private fun SelectionBar", ".foot", "N46",
   "One bar plus a More button. The setting that chose between bar and menu is deleted."],

  // ── menus and dialogues ──
  ["Context menu", "fun ContextMenu", "DIALOGS.context", "N67",
   "Keeps the icon button row across the top: cut, copy, paste, rename, star, delete. The app already splits these - see splitForContextMenu - and the first redesign dropped the row."],
  ["Context menu quick row", "fun splitForContextMenu", ".ctxbar", "N67",
   "Which actions get an icon button and which go in the list. Already a pure function."],
  ["Actions popup", "private fun PaneContextMenu", "actionsHTML", "N54",
   "Same icon row, then grouped entries, destructive last and separated."],
  ["Open with", "private fun OpenWithSheet", "openWithHTML", "N81",
   "One dialogue. Filet viewers above, apps below, remember control in the footer beside Open."],
  ["New folder", "fun NameDialog", "DIALOGS.newfolder", "N59",
   "Warns that a dot-prefixed name is never indexed by Android."],
  ["Rename", "fun NameDialog", "DIALOGS.rename", "N59",
   "Selection stops at the dot, so typing replaces the name and keeps the extension."],
  ["Delete", "fun ConfirmDialog", "DIALOGS.delete", "N59",
   "Says there is no bin, and offers the holding folder instead."],
  ["Properties", "private fun PropertiesDialog", "DIALOGS.properties", "N59",
   "Says creation time is not recorded rather than showing the modification time twice."],
  ["Create archive", "private fun CompressDialog", "DIALOGS.archive", "N59",
   "Says zip leaves names readable and 7z does not."],
  ["Extract", "fun ExtractSheet", "DIALOGS.extract", "N59",
   "Shows the plan before writing anything, so a clash is a question not a surprise."],
  ["Archive save", "fun ConfirmDialog", "DIALOGS.archsave", "N59",
   "Names the backup and the re-sign as steps, because both happen and neither is obvious."],
  ["Folder picker", "fun FolderPicker", "DIALOGS.folder", "N59",
   "Can create a folder from inside itself."],
  ["APK inspector", "fun ApkInspector", "DIALOGS.apk", "N64",
   "Needs a full-screen form as well as the sheet: it is a tool, not a question."],
  ["XAPK inspector", null, "DIALOGS.xapk", "N64",
   "New. Says plainly it is not an archive, which is why it could not be installed before."],
  ["Metadata writer", null, "metaHTML", "N46",
   "Fields first, support matrix folded away. Needs a full-screen form as well."],
  ["Update sheet", "fun UpdateSheet", "DIALOGS.update", "N68",
   "Keeps every element it has; the notes get the room and the chrome gets out of the way."],
  ["Onboarding", "fun Onboarding", "DIALOGS.onboard", "N69",
   "Stays a slideshow. The permission slide is the one that cannot become advertising."],
  ["Crash", "fun CrashScreen", "DIALOGS.crash", "N65",
   "Taller, names the file it wrote, and offers to open it."],

  // ── the new ones ──
  ["Storage tool", null, "storHTML", "N57",
   "New screen. Tree, largest, by type, clean up, and the tray. See STORAGE-TOOL.md."],
  ["Appoint tray", null, ".tray", "N80",
   "Tap to appoint as well as drag. Side panel in landscape, collapsed when empty."],
  ["Theme picker", "fun SettingsPage", "appearanceHTML", "N84",
   "Twelve palettes plus a custom setter. Names describe the palette, never its source."],
  ["App icon picker", null, "APP_ICONS", "N84",
   "Launcher aliases. Switching one drops home-screen shortcuts to the old alias."],
  ["App intro", null, "IntroPolicy", "N42",
   "Off by default. A hard failsafe finishes it whatever the animation is doing."],
];

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith(".kt")) out.push(p);
  }
  return out;
}

function locate(anchor, files) {
  if (!anchor) return null;
  for (const f of files) {
    const lines = readFileSync(f, "utf8").split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].includes(anchor)) {
        return { file: relative(".", f).replace(/\\/g, "/"), line: i + 1 };
      }
    }
  }
  return null;
}

function build() {
  const files = ROOTS.flatMap((r) => walk(r));
  const rows = [];
  const missing = [];
  for (const [name, anchor, mock, gate, note] of SURFACES) {
    const at = locate(anchor, files);
    if (anchor && !at) missing.push(`${name} (looked for "${anchor}")`);
    rows.push({ name, mock, gate, note, at, anchor });
  }
  return { rows, missing };
}

function render({ rows, missing }) {
  const line = (r) =>
    `| **${r.name}** | ${r.at ? `\`${r.at.file}:${r.at.line}\`` : "_new, nothing to replace_"} | \`${r.mock}\` | ${r.gate} | ${r.note} |`;
  return `# Redesign — the translation spec

> **Generated.** \`node tools/make-redesign-spec.mjs\`. Every line number is read out of the
> source when this runs, because a hand-typed citation rots on the next edit and a rotted
> citation is worse than none — it sends somebody to the wrong place and looks authoritative
> doing it. Re-run it whenever the mock or the code moves.

The mock is \`filet-redesign-mock.html\`. This is the map between it and the app: what to open,
what draws it now, what draws it in the mock, which gate it answers, and the one thing about
that surface which is easiest to lose on the way across.

## Every surface

| Surface | In the app today | In the mock | Gate | The thing not to lose |
|---|---|---|---|---|
${rows.map(line).join("\n")}

${missing.length
  ? `## Not found in the app\n\nEach of these is either new work or a rename, and both need saying out loud\nrather than leaving a blank cell:\n\n${missing.map((m) => `- ${m}`).join("\n")}\n`
  : "## Not found in the app\n\nNothing — every surface with an anchor was located.\n"}
## How to use this when the redesign is built

1. Open the mock and the cited file side by side. The mock is the specification; read values
   off it rather than matching them by eye.
2. Motion comes from the mock's CSS. \`.18s var(--e-out)\` is an 180 ms easing curve in Compose,
   not "a quick fade".
3. When a framework makes an approved behaviour awkward, build the behaviour. If it genuinely
   cannot be expressed, say so and propose the nearest faithful thing — never substitute
   silently and never report it as done.
4. When the mock changes, re-run this generator so the citations stay true.
`;
}

const built = build();
const text = render(built);

if (process.argv.includes("--check")) {
  const have = existsSync(OUT) ? readFileSync(OUT, "utf8") : "";
  if (have !== text) {
    console.error(`${OUT} is out of date. Run: node tools/make-redesign-spec.mjs`);
    process.exit(1);
  }
  console.log(`REDESIGN SPEC OK  (${built.rows.length} surfaces, ${built.missing.length} not yet in the app)`);
  process.exit(0);
}

writeFileSync(OUT, text);
console.log(`REDESIGN SPEC OK  (${built.rows.length} surfaces, ${built.missing.length} not yet in the app)`);
for (const m of built.missing) console.log("  not found: " + m);
