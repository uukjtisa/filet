#!/usr/bin/env node
/**
 * The redesign mock actually contains the surfaces it was asked for.
 *
 * A mock is the spec for the round that follows it, and the failure it exists to catch is a
 * quiet one: a surface gets discussed, agreed and then never drawn, and nobody notices until
 * the implementation round asks what it was supposed to look like. Every item below is
 * a surface this round is answerable for.
 *
 * This is deliberately a blunt instrument and the limit is worth stating: it can see whether
 * a surface is present and wired, and it cannot see whether the design is any good. Whether the design is right is a
 * judgement made by looking at it, not by a checker. A green run means nothing was dropped.
 *
 *   node tools/check-mock.mjs              check the working mock
 *   node tools/check-mock.mjs --selftest   prove it catches a missing surface
 *
 * The working copy lives on the desktop for iteration, so on any machine that does not have
 * it this exits 2 - a SKIP with the reason printed, the same as the checkers that need 7-Zip.
 * CI has no desktop and must not go red for it.
 *
 * Prints "MOCK OK" only after every assertion passes.
 */
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const MOCK = process.env.FILET_MOCK || join(homedir(), "Desktop", "filet-redesign-mock.html");
const SPEC = "docs/early_mockups_preDev/STORAGE-TOOL.md";
const TEMPLATE = "docs/early_mockups_preDev/filet-mock.html";

/**
 * Each entry is one surface this round committed to, and the needle is the thing that would be
 * missing if it had not been built. Needles are structural (a class, a function, a handler) rather than
 * cosmetic, so restyling cannot fail this and deleting a feature must.
 */
const WANTED = [
  ["N53", "the tab carries the short name", /title:"New files"/],
  ["N53", "the home heading keeps the long name", /TRACKED_LONG\s*=\s*"New in your tracked folders"/],
  ["N53", "the path prints the short name", /path:"filet:\/\/new-files"/],
  ["N54", "the tabs are redesigned rather than restyled in place", /\[data-r="3"\] \.tab\.on\{/],
  ["N54", "R2 is still there to compare against", /id="round"/],
  ["N54", "the Actions popup exists", /function actionsHTML\(\)/],
  ["N54", "the Actions popup is grouped", /class="agrp"/],
  // Separated, wherever it lives. It used to be the last row of the list; it is now the last
  // button of the icon row, still the only red thing in the menu.
  ["N54", "the destructive action is marked out from the rest", /class="ai bad"|"bad2"/],
  ["N55", "the date column is off in the list", /\[data-r="3"\] \.row \.dt\{display:none\}/],
  ["N55", "a folder shows what is inside it", /function metaFor/],
  ["N55", "the list has its own typeface", /--ff-list/],
  ["N55", "numbers are tabular", /tabular-nums/],
  ["N56", "the date format is a setting", /function fmtDate/],
  ["N56", "every order is offered", /"dmy"[\s\S]{0,400}"mdy"[\s\S]{0,400}"ymd"/],
  ["N56", "named and numeric months are offered", /MONTHS_LONG|Named month/],
  ["N56", "the fields are individually switchable", /js-t" data-k=|data-k="size"/],
  ["N56", "there is a live view", /function liveHTML/],
  ["N56", "the live view uses the same renderer as the list", /liveHTML[\s\S]{0,900}rowsHTML3/],
  ["N57", "the storage tool exists", /function storHTML/],
  ["N57", "folders carry a bar for their share", /class="bw"/],
  ["N57", "sizes are shown against folders and files", /STORE_TREE/],
  ["N57", "the tray keeps its name", /Appoint for removal/],
  ["N57", "rows can be dragged into the tray", /tray\.ondrop/],
  ["N57", "the TreeSize half is there", /Folder tree/],
  ["N57", "the Disk Drill half is there", /Clean up/],
  ["N58", "the About card follows the repo rather than the tagline it replaced",
   /four things it will not trade[\s\S]{0,12}against each other/],
  ["N58", "all four are named, in one line rather than four",
   /Productivity, power, aesthetics and convenience/],
  ["N58", "the watermark is the app mark and not the author seal",
   /class="wm"><svg><use href="#i-folder"\//],
  ["N58", "the glare is sized to the name and not to the card", /\.sig \.nm\{[\s\S]{0,900}width:fit-content/],
  ["N81", "one open-with dialogue, not two", /function openWithHTML/],
  ["N81", "both entry points reach the same one", /openwith: \(state\) => openWithHTML[\s\S]{0,120}uncertain: \(state\) => openWithHTML/],
  ["N81", "Filet's own viewers come first", /class="owh">In Filet/],
  ["N81", "the external half is separated", /class="owsep"/],
  ["N81", "each half can be expanded to everything", /Every other viewer[\s\S]{0,2000}Every other app/],
  ["N81", "the remember control sits with the button it modifies", /class="owfoot"[\s\S]{0,400}js-rem/],
  ["N86", "the menu keeps its icon row", /function ctxbarHTML/],
  ["N86", "the row is the five the app already decided on",
   /"copy"[\s\S]{0,700}"move"[\s\S]{0,700}"rename"[\s\S]{0,700}"send"[\s\S]{0,700}"delete"/],
  ["N86", "move is relabelled Cut with scissors", /"Cut"[\s\S]{0,20}"i-cut"/],
  ["N84", "more than three themes", /const THEMES = \[[\s\S]{0,2000}"solar"/],
  ["N84", "a custom palette setter", /"custom"[\s\S]{0,600}Your own three colours/],
  ["N84", "the app icon can be changed", /const APP_ICONS/],
  ["N84", "the separator is a shared thing, not a bare rule", /\.rule2\{/],
  ["N59", "the tabs are drawn, not just their labels", /const TABS3 = \{/],
  // One rule per tab, not one ordered span across all of them. The span version measured
  // distance as much as presence: it went red when the nearby screen grew, and it had gone
  // red for the right reason moments earlier - an over-wide slice had deleted four tabs - with
  // no way to tell those two apart from the message. A checker that cannot say WHICH screen is
  // missing sends you looking in the wrong place.
  ...["home", "tracked", "nearby", "scripts", "bookmarks", "recent", "shortcuts", "remotes", "activity"]
    .map((t) => ["N59", `the ${t} tab has a screen`, new RegExp(`\\n {2}${t}: \\(`)]),
  ["N59", "the tracked tab is one of them", /\n {2}tracked: \(/],
  // FileHistoryScreen.kt declares three controls in two shapes, and the redesign has already
  // lost them once by redrawing them as four loose buttons. A redesign may restyle a control;
  // it may not delete one. These three say the controls survived.
  ["N88", "the day / flat grouping toggle is still there and still says which it is",
   /js-hgroup[\s\S]{0,140}grouped \? "By day" : "Flat"/],
  ["N88", "first seen against last changed is still a choice",
   /js-hsort[\s\S]{0,300}data-v="changed"/],
  ["N88", "flat still means flat - no day headings when it is off", /if \(grouped\) \{[\s\S]{0,900}\} else \{/],
  ["N88", "the day headings fold", /data-day=[\s\S]{0,400}\.day\.shut \+ \.dayrows|\.day\.shut \+ \.dayrows/],
  ["N80", "a row can be appointed without dragging it", /data-tick=/],
  ["N80", "a whole set can go in at once", /js-selall/],
  ["N80", "the tray folds away when it is empty", /\.tray\.idle/],
  ["N80", "landscape lays the tray beside the list rather than under it",
   /grid-template-areas:"head tray"/],
  ["N57", "the unreadable directory is disclosed", /Android\/data and Android\/obb are not fully readable|not fully readable/],
  // ScriptsScreen.kt draws a name, the permission lines, and three chips. The redesign lost
  // all three at once by drawing a code excerpt instead, so all three are asserted.
  ["N88", "a script row says what the script may touch", /const script2 = [\s\S]{0,700}perms\.map/],
  ["N88", "a script row keeps all three verbs", /Running\\u2026" : "Run"[\s\S]{0,120}>Edit<[\s\S]{0,60}>Delete</],
  ["N88", "the run output carries how long it took", /class="runout"[\s\S]{0,400}class="ms"/],
  ["N88", "the approval dialogue is one of the dialogues", /scriptrun: \(\) =>[\s\S]{0,900}Always allow/],
  // Nearby. The row icon shipped unsized once and rendered at its intrinsic 131x93 inside a
  // 212px phone, so the rule is on the CSS that fixes it, not on the markup that used it.
  ["N89", "a row's leading icon is sized by the row that owns it",
   /\.nrow > svg, \.nrow \.ni svg\{width:17px/],
  ["N89", "the sharing light has a transfer state, not just on and off", /\.share\.busy \.dot2\{/],
  ["N89", "one pip per connected device, each with its own state",
   /const pips = states =>[\s\S]{0,200}\.map/],
  ["N89", "a pip can say which way the transfer is going", /\.pips i\.dn\{[\s\S]{0,200}\.pips i\.up\{/],
  ["N89", "shared entries carry a thumbnail where the type has one", /\.th3\.img\{[\s\S]{0,400}\.th3\.vid\{/],
  ["N89", "a type with no preview still gets the same tile", /thumb: "doc"/],
  ["N89", "the stop button is its own width, not the card's", /\.share \.sfoot \.tbtn\{flex:none\}/],
  // A marker any block may use is styled where any block can see it. Scoped under .share it
  // rendered as a grey square in the hosting card, which is the third time in one round a
  // class was reused outside the scope that sized it.
  ["N89", "the status light is styled unscoped", /\n\.dot2\{width:9px/],
  // N90 - hosting. Filet can only dial out today: every NetProtocol is a client provider, and
  // the HTTP server serves Filet's own web UI, which no desktop can mount. This is the other
  // direction, and it is WebDAV because SMB needs privileged port 445.
  ["N90", "the remotes tab offers to host, not only to connect", /Mount this phone on your PC/],
  ["N90", "the address you paste into Explorer is shown", /DavWWWRoot/],
  ["N90", "writing is off until it is turned on", /Let the PC change files", false\)/],
  ["N90", "what is exposed is a choice, not the whole phone by default",
   /chips2\("Let the PC see", \["Filet\/Shared", "Whole phone"\], "Filet\/Shared"\)/],
  ["N90", "Windows' 50 MB WebDAV cap is disclosed before it looks like a broken file",
   /50 MB[\s\S]{0,400}FileSizeLimitInBytes/],
  ["N90", "the service Windows needs is named", /WebClient/],
  ["N90", "it says plainly that nothing is hosted for you", /protocol,\s*not a service/],
  // The screen's own protocol list, which the redesign had wrong in both directions.
  ["N90", "every protocol the app has gets an add button",
   /\+ SMB[\s\S]{0,200}\+ SFTP[\s\S]{0,200}\+ FTP[\s\S]{0,200}\+ WEBDAV/],
  // Anchored to the sentence the user reads, not to the protocol list on its own - the same
  // words appear in the comment above the screen, so the loose needle was satisfied by a
  // comment while the blurb itself said something else. A rule a comment can satisfy is not
  // measuring the surface.
  ["N90", "SFTP is in the blurb it was missing from",
   /SMB, SFTP, FTP and WebDAV, mounted as ordinary folders/],
  // N91 - the page Nearby serves, and the clipboard on it. The share page is a surface like
  // any other and had never been drawn, so it could be redesigned without anybody noticing
  // what it already did. Its own features are asserted here alongside the new ones.
  ["N91", "the served page is a surface in the mock", /function webHTML\(state\)/],
  ["N91", "it is drawn in a browser, not a phone frame", /function bframe\([\s\S]{0,600}class="bwin"/],
  ["N91", "the grant link is what the browser shows", /class="burl">http:\/\/[\d.]+:8321\/a\//],
  // Kept from app/src/main/assets/web/app.html.
  ["N91", "the filter survived", /placeholder="Filter this folder"/],
  ["N91", "select all survived", />Select all</],
  ["N91", "zip-the-selection survived", />Download as \.zip</],
  ["N91", "the count still admits when it shows part of a folder", /showing 11 of 11/],
  ["N91", "the upload target survived and still says where files land", /Received folder/],
  ["N91", "the job toasts survived", /class="wjob/],
  // The clipboard.
  ["N91", "a clipboard entry previews what is in it", /const clipEntry = [\s\S]{0,300}class="pv/],
  ["N91", "every entry can be copied", /class="cb"><button class="pri">Copy</],
  ["N91", "deleting is gated on the phone allowing it", /canDelete \? `<button class="bad">Delete/],
  ["N91", "entries are plain files in visible storage", /Filet\/Clipboard/],
  ["N91", "an entry is a named file, not a row in a database", /entry-04\.txt/],
  ["N91", "the three gates are switches that do something", /data-tgl="\$\{key\}"/],
  ["N91", "turning the clipboard off says so rather than showing an empty panel",
   /not sharing its clipboard/],
];

/** Things the mock must NOT do, each of which has gone wrong once already. */
const FORBIDDEN = [
  // Guards against a mock that is a different app, not against new palettes. The list moves
  // when a theme is added on purpose; it fails on a theme nobody declared.
  ["the mock uses a theme that is not declared in THEMES",
   /data-theme="(?!slate|ember|paper|graphite|ash|frost|clay|moss|plum|solar|noir|custom)[a-z]/],
  ["the About card still carries the tagline the README was rewritten away from",
   /people who want to do real work on their phone without a PC/i],
  // A theme may take a palette; it may not take the name that goes with it. A colour is not a
  // claim of association and a name is, so these are refused anywhere in the file including
  // in a comment.
  ["a theme is named after the product its palette came from", /(macos|mac os|anthropic|claude)/i],
  ["the open-with dialogue has a Just once button as well as a remember control, which is one binary twice",
   /openWithHTML[\s\S]{0,2600}Just once/],
  // The screen shows what a script may REACH, never what it says. Source belongs behind Edit.
  ["the scripts tab shows an excerpt of the source, which is not what that screen is",
   /scripts: \(\) =>[\s\S]{0,2200}class="code"/],
  // NetProtocol has four entries and HTTP is not one of them - TLS is a per-connection flag
  // that reads as FTPS or HTTPS on the form. Listing them as protocols invents two and, when
  // it happened, pushed the real fourth one (SFTP) out of the sentence entirely.
  ["the remotes blurb lists HTTP as if it were one of the protocols",
   /SMB, FTP, FTPS, HTTP/],
];

function problems(html, spec, template) {
  const found = [];
  for (const [gate, what, re] of WANTED) {
    if (!re.test(html)) found.push(`${gate}: ${what} - not in the mock`);
  }
  for (const [what, re] of FORBIDDEN) {
    if (re.test(html)) found.push(what);
  }
  if (spec != null) {
    // The spec is half the deliverable, and the two reference tools it is matching are
    // named in the brief, so the spec has to name them too.
    if (!/TreeSize/i.test(spec)) found.push("N57: the spec never mentions TreeSize, which is half the target design");
    if (!/Disk Drill/i.test(spec)) found.push("N57: the spec never mentions Disk Drill, which is the other half");
    if (!/## Proposals/.test(spec)) found.push("N57: the spec has no proposals section, which is part of what it is for");
  }
  if (template != null && html.length <= template.length) {
    // Round 3 is additive to round 2. A working copy smaller than the template means it was
    // started from scratch instead of extended, and a mock that does not resemble the app it
    // is redesigning is worth nothing.
    found.push("the working mock is not larger than the template it was copied from");
  }
  return found;
}

if (process.argv.includes("--selftest")) {
  const full = [
    'title:"New files"', 'TRACKED_LONG = "New in your tracked folders"', 'path:"filet://new-files"',
    '[data-r="3"] .tab.on{', 'id="round"', "function actionsHTML()", 'class="agrp"', '"bad")',
    '[data-r="3"] .row .dt{display:none}', "function metaFor", "--ff-list", "tabular-nums",
    '"bad2"',
    "function fmtDate", '"dmy" "mdy" "ymd"', "MONTHS_LONG", 'data-k="size"',
    "function liveHTML", "liveHTML rowsHTML3", "function storHTML", 'class="bw"', "STORE_TREE",
    "Appoint for removal", "tray.ondrop", "Folder tree", "Clean up", "not fully readable",
    "Productivity, power, aesthetics and convenience - four things it will not trade against each other",
    'class="wm"><svg><use href="#i-folder"/>',
    'data-tick=', "js-selall", ".tray.idle", 'grid-template-areas:"head tray"',
    "function openWithHTML",
    "openwith: (state) => openWithHTML uncertain: (state) => openWithHTML",
    'class="owh">In Filet', 'class="owsep"', 'class="owfoot"> js-rem',
    'const THEMES = [ "solar"', '"custom" Your own three colours', "const APP_ICONS",
    ".rule2{", "const TABS3 = {",
    // One line per tab, indented, because each tab now has its own rule.
    "  home: (", "  tracked: (", "  nearby: (", "  scripts: (", "  bookmarks: (",
    "  recent: (", "  shortcuts: (", "  remotes: (", "  activity: (",
    // The New files tab: the controls the app declares, and folding days.
    'js-hgroup grouped ? "By day" : "Flat"', 'js-hsort data-v="changed"',
    "if (grouped) { } else {", ".day.shut + .dayrows",
    // The scripts tab: permissions, three verbs, a duration, and the approval dialogue.
    "const script2 = perms.map", 'Running\\u2026" : "Run" >Edit< >Delete<',
    'class="runout" class="ms"', "scriptrun: () => Always allow",
    // Nearby: sized icons, a light that can say "transferring", pips, and thumbnails.
    ".nrow > svg, .nrow .ni svg{width:17px", ".share.busy .dot2{",
    "const pips = states => .map", ".pips i.dn{ .pips i.up{",
    ".th3.img{ .th3.vid{", 'thumb: "doc"', ".share .sfoot .tbtn{flex:none}",
    ".dot2{width:9px",
    // Remotes: hosting, and the protocol list the screen actually has.
    "Mount this phone on your PC", "DavWWWRoot", 'Let the PC change files", false)',
    'chips2("Let the PC see", ["Filet/Shared", "Whole phone"], "Filet/Shared")',
    "50 MB FileSizeLimitInBytes", "WebClient", "protocol,\n        not a service",
    "+ SMB + SFTP + FTP + WEBDAV", "SMB, SFTP, FTP and WebDAV, mounted as ordinary folders",
    // The page Nearby serves, and the clipboard on it.
    "function webHTML(state)", 'function bframe( class="bwin"',
    'class="burl">http://192.168.1.14:8321/a/4k9x',
    'placeholder="Filter this folder"', ">Select all<", ">Download as .zip<",
    "showing 11 of 11", "Received folder", 'class="wjob',
    'const clipEntry = class="pv', 'class="cb"><button class="pri">Copy<',
    'canDelete ? `<button class="bad">Delete', "Filet/Clipboard", "entry-04.txt",
    'data-tgl="${key}"', "not sharing its clipboard",
    "function ctxbarHTML", '"copy" "move" "rename" "send" "delete"', '"Cut" "i-cut"',
    "Every other viewer Every other app",
    '[data-r="3"] .sig .nm{ width:fit-content',
  ].join("\n");
  const spec = "# x\nTreeSize\nDisk Drill\n## Proposals\n";
  const CASES = [
    ["a complete mock", full, spec, null, false],
    ["the storage tool never drawn", full.replace("function storHTML", "function nope"), spec, null, true],
    ["the tray renamed", full.replace("Appoint for removal", "Delete"), spec, null, true],
    ["a tray you cannot drop onto", full.replace("tray.ondrop", "x"), spec, null, true],
    ["the live view dropped", full.replace("function liveHTML", "x"), spec, null, true],
    ["the date column left on", full.replace('[data-r="3"] .row .dt{display:none}', "x"), spec, null, true],
    ["the long name used on the tab", full.replace('title:"New files"', 'title:"New in your tracked folders"'), spec, null, true],
    ["the About card left on the old tagline",
     full.replace("four things it will not trade against each other", "x"), spec, null, true],
    ["the author seal still used as the app mark",
     full.replace('class="wm"><svg><use href="#i-folder"/>', 'class="wm"><svg><use href="#i-mark"/>'), spec, null, true],
    ["the glare still sized to the card", full.replace("width:fit-content", "width:100%"), spec, null, true],
    ["dragging left as the only way into the tray", full.replace("data-tick=", "x"), spec, null, true],
    ["two open-with dialogues again", full.replace("function openWithHTML", "x"), spec, null, true],
    ["the tabs never drawn", full.replace("const TABS3 = {", "x"), spec, null, true],
    ["the icon row dropped again", full.replace("function ctxbarHTML", "x"), spec, null, true],
    ["move missing from the row", full.replace('"copy" "move"', '"copy" "x"'), spec, null, true],
    ["no custom palette", full.replace('"custom" Your own three colours', "x"), spec, null, true],
    ["the app icon left unchangeable", full.replace("const APP_ICONS", "x"), spec, null, true],
    ["the external apps not separated", full.replace('class="owsep"', "x"), spec, null, true],
    ["landscape still stacking the tray under the list",
     full.replace('grid-template-areas:"head tray"', "x"), spec, null, true],
    // This round. Every one of these is a thing the redesign actually dropped once, by
    // redrawing a screen from a memory of it rather than from the screen.
    ["a tab deleted outright", full.replace("  shortcuts: (", "x"), spec, null, true],
    ["one stateful toggle drawn as two buttons",
     full.replace('js-hgroup grouped ? "By day" : "Flat"', "js-hgroup"), spec, null, true],
    ["the sort choice dropped", full.replace('data-v="changed"', "x"), spec, null, true],
    ["flat stops meaning flat", full.replace("if (grouped) { } else {", "x"), spec, null, true],
    ["the day headings stop folding", full.replace(".day.shut + .dayrows", "x"), spec, null, true],
    ["a script row stops saying what it may touch",
     full.replace("const script2 = perms.map", "x"), spec, null, true],
    ["Edit and Delete dropped from a script row",
     full.replace(">Edit< >Delete<", "x"), spec, null, true],
    ["the run duration dropped", full.replace('class="ms"', "x"), spec, null, true],
    ["the approval dialogue dropped", full.replace("scriptrun: () =>", "x"), spec, null, true],
    ["a row icon left unsized", full.replace(".nrow > svg, .nrow .ni svg{width:17px", "x"), spec, null, true],
    ["the sharing light loses its transfer state",
     full.replace(".share.busy .dot2{", "x"), spec, null, true],
    ["the per-device pips dropped", full.replace("const pips = states => .map", "x"), spec, null, true],
    ["a pip cannot say which way the transfer goes",
     full.replace(".pips i.dn{ .pips i.up{", "x"), spec, null, true],
    ["shared entries lose their thumbnails", full.replace(".th3.img{ .th3.vid{", "x"), spec, null, true],
    ["the no-preview type loses the tile", full.replace('thumb: "doc"', "x"), spec, null, true],
    ["the stop button spans the whole card",
     full.replace(".share .sfoot .tbtn{flex:none}", "x"), spec, null, true],
    ["the status light re-scoped to one block",
     full.replace(".dot2{width:9px", ".share .dot2{width:9px"), spec, null, true],
    ["hosting dropped from remotes",
     full.replace("Mount this phone on your PC", "x"), spec, null, true],
    ["the Explorer address dropped", full.replaceAll("DavWWWRoot", "x"), spec, null, true],
    ["the PC can write by default",
     full.replace('Let the PC change files", false)', 'Let the PC change files", true)'), spec, null, true],
    ["the whole phone exposed by default",
     full.replace('"Whole phone"], "Filet/Shared"', '"Whole phone"], "Whole phone"'), spec, null, true],
    ["Windows' 50 MB cap undisclosed",
     full.replace("50 MB FileSizeLimitInBytes", "x"), spec, null, true],
    ["SFTP dropped from the add row", full.replace("+ SFTP", "+ X"), spec, null, true],
    ["HTTP listed as a protocol again",
     full.replace("SMB, SFTP, FTP and WebDAV, mounted as ordinary folders",
                  "SMB, FTP, FTPS, HTTP, HTTPS and WebDAV"), spec, null, true],
    ["the share page never drawn", full.replace("function webHTML(state)", "x"), spec, null, true],
    ["the share page put back in a phone frame",
     full.replace('function bframe( class="bwin"', "x"), spec, null, true],
    ["the folder filter dropped from the share page",
     full.replace('placeholder="Filter this folder"', "x"), spec, null, true],
    ["zip-the-selection dropped from the share page",
     full.replace(">Download as .zip<", "x"), spec, null, true],
    ["a clipboard entry stops previewing its contents",
     full.replace("const clipEntry = class=\"pv", "x"), spec, null, true],
    ["deleting stops being gated on the phone allowing it",
     full.replace('canDelete ? `<button class="bad">Delete', '`<button class="bad">Delete'), spec, null, true],
    ["clipboard entries stop being files in visible storage",
     full.replace("Filet/Clipboard", "x"), spec, null, true],
    ["the clipboard gates become decoration",
     full.replace('data-tgl="${key}"', "x"), spec, null, true],
    ["the spec forgets its own references", full, "# x\n## Proposals\n", null, true],
    ["the spec has no proposals", full, "TreeSize\nDisk Drill\n", null, true],
    ["a mock started from scratch again", full, spec, "x".repeat(full.length + 1), true],
  ];
  let bad = 0;
  for (const [name, html, sp, tpl, expect] of CASES) {
    const got = problems(html, sp, tpl).length > 0;
    if (got !== expect) {
      console.error(`SELFTEST FAILED: ${name} - expected ${expect ? "caught" : "clean"}`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  const neg = CASES.filter((c) => c[4]).length;
  console.log(`MOCK SELFTEST OK  (${neg} negative controls, ${CASES.length - neg} positive)`);
  process.exit(0);
}

if (!existsSync(MOCK)) {
  console.log(`SKIP: the working mock is not on this machine (${MOCK}). Set FILET_MOCK to point at it.`);
  process.exit(2);
}
const html = readFileSync(MOCK, "utf8");
const spec = existsSync(SPEC) ? readFileSync(SPEC, "utf8") : null;
const template = existsSync(TEMPLATE) ? readFileSync(TEMPLATE, "utf8") : null;
if (spec === null) {
  console.error(`${SPEC} is missing, and it is the deliverable half of N57`);
  process.exit(1);
}

const found = problems(html, spec, template);
if (found.length) {
  console.error("The redesign mock is missing something that was asked for:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const gates = [...new Set(WANTED.map((w) => w[0]))].join(", ");
console.log(`MOCK OK  (${WANTED.length} surfaces across ${gates}, plus the storage spec)`);
