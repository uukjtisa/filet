# Legacy icons: translation spec

Generated 2026-11-14 10:25 from `../mockups/filet-legacy-icons-mock.html` and the app's source, by the spec script.
Every citation is found by the script each run; don't edit this file, run the script again.
"mock L" is a line of the built mock. A Kotlin cite is `file:line` under `app/src/main/java/dev/niccc2007/filet/`, or **NEW** where the app has nothing yet. "App after" names a proposed site; it reads **NEW** until it's built, and a regenerated spec with a **NEW** left in that column is unfinished work.
Nothing here is built yet.

## 1. Decisions, in order

1. An optional look brings back the line icons from before the icon rework. It is **off by default**, has its own choice in Appearance for each place it changes, and never touches Home or the launcher icons.
2. Groundwork, `b8477c3`: one style that all icon drawing reads, every place on the drawn icons, Home never reading it. That's `IconStyle` today (`icons/IconStyle.kt:12`).
3. Gallery covers the category galleries and the grid view. **Each place gets a choice, not an on/off switch.** Emblems work on the legacy looks, and a folder's emblem survives switching. The Bottom bar list follows Tabs, while the bar itself and its preview don't change. The plain folder is the one with pictures. The setting is called **Legacy icons**, with Tabs · Files and folders · Gallery · Shortcuts tab under it.
4. Legacy icons also changes **Filet's own app icon**, on the home screen and in the app drawer.
5. Gallery covers **all six** category galleries. Change icon's preview draws in the lists' look. The cover photo stands in the middle of the fan; Legacy outline hides it and keeps it saved.
6. The folder looks are **Drawn · Legacy · Legacy outline**.
7. Files get more than one look, and the gallery gets Legacy for its buttons as well as its icons, but only buttons the icon rework redrew get a Legacy look.
8. Files get **two looks, Drawn · Legacy**. Home's six category buttons stay drawn. **Show emblem** sits in Change icon, on by default. Legacy outline hides every emblem and greys the tick with a line saying why. **The chosen emblem is always kept.** A Legacy folder tab chip is the plain outline. The gallery's hold sheet and selection bar stay as they are.
9. Round 3 is final, with the names as drawn (Tabs and Shortcuts tab: Drawn · Legacy; Gallery rows Folders and Files; App icon) and the app icon picker.
10. While an emblem can't show, the emblem grid and colours **still work** and the pick is kept (mock L454).
11. The switchable app icon set is **Legacy plus seven designs** (Tile, Ribbon, Picture, Lit, Letter, Fan, Page), in the palette colours as section 8 draws them. The launcher entry moves off the main screen once (option A).
12. **A fresh install starts on Legacy.** The designs are named **Tile · Ribbon · Picture · Lit · Letter · Fan · Page**. **Export carries the app icon**, and Import asks "Also use this app icon?" before it switches (mock L430).
13. Folder colour, round 2: file lists and the gallery each keep their own Folders choice, with four choices. Shortcuts and tab chips follow the lists' Folders choice unless the folder has its own.

## 2. Surfaces

| # | Surface | Mock | App today | App after |
|---|---|---|---|---|
| 1 | **Legacy icons** section in Settings › Appearance, after Name lines and before Row details | mock L1280 | `settings/SettingsPage.kt:384` (the row it follows) | **NEW** |
| 2 | Live sample card: Tab, Folder, File, Gallery and Shortcut, each as the choices make it | mock L1284 | none; Row details' sample is the model (`settings/SettingsPage.kt:405`) | **NEW** |
| 3 | Tabs: Drawn · Legacy | mock L1292 | `icons/IconStyle.kt:27` | the setting's `tabs` |
| 4 | "Files and folders" group label, then Folders: Drawn · Legacy · Legacy outline | mock L1294 | `icons/IconStyle.kt:28` (one value for both) | **NEW** |
| 5 | Files and folders › Files: Drawn · Legacy | mock L1295 | `icons/IconStyle.kt:28` | the setting's `files.files` |
| 6 | "Gallery" group label, then Folders and Files with the same choices | mock L1297 | `icons/IconStyle.kt:29` | the setting's `gallery` |
| 7 | Shortcuts tab: Drawn · Legacy | mock L1299 | `icons/IconStyle.kt:30` | the setting's `shortcuts` |
| 8 | App icon row: current design and colour, chevron, opens the picker | mock L1824 | none | **NEW** |
| 9 | Lists: a folder drawn in the place's Folders look | mock L1312 | `browser/ThumbIcon.kt:133` | the same branch, three looks |
| 10 | Legacy folder: back, up to three pictures fanned, front | mock L1171 | `browser/ClassicIcons.kt:48` | unchanged but for row 11 |
| 11 | An emblem on the Legacy folder's front, in its colour, with its shadow | mock L1183 | none | **NEW** |
| 12 | Legacy outline: the bare folder outline, no emblem, no pictures, no cover | mock L1190 | `browser/Icons.kt:110` | drawn where Folders is Legacy outline |
| 13 | Legacy file: the kind's line icon, or the extension as text | mock L1209 | `browser/ClassicIcons.kt:79` | unchanged |
| 14 | Grid views (Small, Medium, Large) follow Gallery | mock L1328 | `browser/ThumbIcon.kt:133` | the grids provide Gallery's looks |
| 15 | The six category galleries follow Gallery › Files; Legacy is the line glyph at a third of the tile in fg3 | mock L1333 | `home/CategoryScreen.kt:51` | the galleries provide Gallery's looks |
| 16 | Home's six category buttons stay drawn, whatever Gallery says | mock L1367 | `home/HomeOverview.kt:1171` | unchanged |
| 17 | The gallery's hold sheet and selection bar: no change (none of their icons was redrawn) | mock L334 | unchanged | unchanged |
| 18 | Tab chips: Legacy is the line glyph; a folder's chip is the plain outline, never an emblem | mock L1386 | `browser/BrowserScreen.kt:749` | chipArt reads the setting's `tabs` |
| 19 | Settings › Bottom bar's list follows Tabs; the bar and its preview never change | mock L1395 | `browser/BarPicker.kt:430` | unchanged in shape, fed by the setting |
| 20 | Shortcuts tab: Legacy is the tab's line glyph on a sunken tile; home-screen icons never change | mock L1415 | `shortcuts/TabIcons.kt:87`, `shortcuts/ShortcutCatalogue.kt:163`, `shortcuts/HoldMenuSection.kt:169` | the Shortcuts tab provides its look |
| 21 | Change icon: **Show emblem** tick under the Emblem heading, above the groups | mock L1498 | `icons/FolderIconSheet.kt:169` | **NEW** |
| 22 | The tick greyed on Legacy outline, with its line | mock L1486 | `ui/dialogs/DlgKit.kt:512` (takes `enabled`, has no greyed look) | DlgTick draws `.ci-tick.grey` when not enabled |
| 23 | Change icon's preview in the lists' look | mock L1492 | `icons/FolderIconSheet.kt:168` | the preview provides Files and folders › Folders |
| 24 | The shortcut sheet's Same as the folder writes the same record, tick included, never greyed | mock L437 | `shortcuts/ShortcutSheet.kt:458` | the sheet gets the tick |
| 25 | The folder's record gains Show emblem | mock L1476 | `icons/FolderArt.kt:52` | **NEW** |
| 26 | The setting's own store, `look.legacy.v1` | mock L1475 | **NEW** | a `look(...)` line in ConfigKeys, beside `data/ConfigKeys.kt:120` |
| 27 | App icon picker: two previews, Design grid six across, four Colours, two captions, Cancel and Switch (off when nothing changes) | mock L1830 | none | **NEW** |
| 28 | One launcher alias per design and colour; the launcher entry moves off MainActivity once (option A) | mock L411 | `AndroidManifest.xml:103` (MainActivity is the entry) | aliases generated by the build |
| 29 | A switch republishes the hold menu straight away | mock L439 | `shortcuts/LauncherShortcuts.kt:54` | called after every switch |
| 30 | Filet Debug keeps its amber in every design; only the design switches there | mock L1846 | the debug build's own launcher colours (`818a136`) | aliases per design only, on debug |

## 3. Behaviour rules

- **Two stores, never one.** The setting holds Tabs, Files and folders (folders, files), Gallery (folders, files) and Shortcuts tab. The folder record (FolderStyles, by file id) holds the emblem, colour, cover and Show emblem. Turning a look on or off writes only the setting, and Change icon writes only the folder record. A test switches every look on and back off and checks that every folder record is byte-for-byte unchanged. (mock L425)
- **Drawn when drawn.** No icon is stored. Every icon is drawn from the place's look plus the folder's record, so switching back to Drawn brings back exactly what was there, emblem included, with no restart. (mock L426)
- **Each place on its own.** Changing one place's choice changes only that place. **Home never reads the setting**, and neither do the launcher icons, home-screen shortcuts, or the bar and its preview. (mock L427)
- **Pictures stay pictures.** Thumbnails, video frames, album art and app icons look the same in every look. A choice changes only what has no picture of its own. (mock L428)
- **Legacy** keeps its old rules: up to three pictures from inside, fanned, none below 22dp, and none for a folder with no pictures. A cover chosen with "Use a photo from inside" stands in the middle of the fan. **Legacy outline** shows no pictures and no cover. (mock L429)
- **Export** carries the setting with the other Appearance settings, and the folder records travel as they already do. It carries the app icon too, and Import asks "Also use this app icon?" before switching.decided, 2026-11-14 (mock L430)
- **Change icon follows the lists.** Its preview draws the folder in the look the lists use, so what you pick is what you get. (mock L431)
- **Gallery is all six** category galleries plus the grid views. (mock L432)
- **Home's category buttons stay drawn** whatever Gallery says, so `IconStyle`'s note that Home never provides a style stays true. (mock L433)
- **An emblem shows only when** the folder has one, its Show emblem is on, and the place's Folders choice isn't Legacy outline. Nothing that hides an emblem clears it. (mock L434)
- **Each place hides by its own Folders choice.** Lists follow Files and folders › Folders, the grids follow Gallery › Folders. Change icon follows the lists, as its preview does, so its tick greys when Files and folders › Folders is Legacy outline. (mock L435)
- **Show emblem** is per folder, on by default, and kept in the folder's record. Greyed, it shows its saved value and the line *Legacy outline shows no emblems. Yours is kept.* Apply on several folders sets it on all of them. (mock L436)
- **The shortcut sheet's "Same as the folder"** writes the same record, so it gets the same tick. It's never greyed there: home-screen icons don't follow Legacy icons. (mock L437)
- **Tabs:** a Legacy folder chip is the plain outline, never an emblem. A Drawn chip follows its folder, Show emblem included. (mock L438)
- **App icon switches republish the hold menu** straight away. Every design and colour is an alias the build generates, and Filet Debug keeps its amber. (mock L439)
- **Default**: every place on Drawn, which is today's app. (mock L440)
- **Picking while hidden** (question 10): while Show emblem is off, or Folders is on Legacy outline, the emblem grid and the colours keep working, and the pick is kept for when it shows. Only the tick greys, and only on Legacy outline. (mock L454)
- **The greyed tick's line** is exactly `Legacy outline shows no emblems. Yours is kept.`; ticked, `Untick to hide it on this folder. It stays saved.`; unticked, `Hidden on this folder. Your emblem is kept.` (mock L1486)
- **A Drawn folder chip** wears its folder's icon with a page in it (one `Fi('txt')`), at 19, emblem included unless its Show emblem is off (mock L1386).
- **The hold hints**, word for word, are the `title` of each row's ⓘ in mock L1280 and mock L1824. Each one goes into its SegmentRow's `hint`.
- **No restart.** Changing a choice redraws every place at once (round trip, mock L1454).

## 4. The two stores

**The setting**, `look.legacy.v1`, kept with Appearance and carried by Export (mock L1475). The names are the mock's own, from the round trip (mock L1468):

```
{
  tabs:      DRAWN | LEGACY,
  files:     { folders: DRAWN | WITH_PICTURES | OUTLINE, files: DRAWN | LEGACY },
  gallery:   { folders: DRAWN | WITH_PICTURES | OUTLINE, files: DRAWN | LEGACY },
  shortcuts: DRAWN | LEGACY
}
```

Every value DRAWN by default, which is the app today. An unknown or missing value reads as DRAWN. Today's `IconStyles` (`icons/IconStyle.kt:26`) holds one DRAWN/PLAIN per place. It becomes per place, with Folders and Files apart and three values for folders; `IconStyle` belongs to the orchestrator, so the shape is agreed with it first.

**The folder's record**, `folder.styles.v1` by file id (`icons/FolderStyles.kt:137`), gains `showEmblem`: true by default, and a record without it reads true, so every folder saved before this keeps its emblem showing (mock L1476).

**Neither store writes the other.** A look change writes only the setting; Change icon writes only the record. No icon is stored anywhere.

**Folder colour, round 2** (`filet-folder-colour-mock.html`): if it is taken, Folders gets a fourth value, `DRAWN_WHOLE`, and the record gets `paint`. Keep both open to one more value; build nothing for them yet.

## 5. The app icon

**The set**: Legacy, today's icon, plus the seven on the mock's `SHIPPED` line (mock L1790), which its picker offers (mock L1792):

| Design | Mock id | Drawn in |
|---|---|---|
| Tile | `r4tile` | tab-icons mock L493 |
| Ribbon | `r5tuck` | tab-icons mock L495 |
| Picture | `r4pics` | tab-icons mock L497 |
| Lit | `r4lit` | tab-icons mock L498 |
| Letter | `f` | tab-icons mock L500 |
| Fan | `fan` | tab-icons mock L501 |
| Page | `page` | tab-icons mock L502 |

Each design carries its own monochrome layer (`monoArt`, on the same line) for themed icons, and its alias icon takes it.

Not in the set: 2a · Ribbon over the lip, 2c · Ribbon clipped on, A · Index tabs. They stay in the tab icons mock.

**The colours** (mock L1793). A colour turns Filet's blues the way a build's colours do (makeTone, mock L1123); paper, gold and photos keep theirs.

| Colour | Dark | Light |
|---|---|---|
| Slate | `#4E7382` | `#7FA8B8` |
| Ember | `#AC5C1E` | `#EE9B5F` |
| Moss | `#4C7A4E` | `#8FBE8C` |
| Ink | `#6A6A67` | `#A0A098` |

Filet Debug keeps `#8A5A2B` / `#D99A5B` in every design (mock L1107).

**How it switches** (mock L383): one `activity-alias` per design and colour, 8 × 4 = 32 on Filet, and 8 on Filet Debug (design only). One is on at a time; a switch turns the new one on and the old one off with `setComponentEnabledSetting(…, DONT_KILL_APP)`. The build generates every alias and its launcher icon files from the same art; none is drawn by hand.

**The one-time move, A** (mock L411): MainActivity stops being a launcher entry (`AndroidManifest.xml:103`) and the aliases take its place. Its other intent filters ("Open with Filet") stay. On the update that brings this, the home-screen Filet icon is re-placed once.

**After every switch**, republish the hold menu straight away (`shortcuts/LauncherShortcuts.kt:54`): its entries are dynamic shortcuts and Android turns them off when their activity stops being a launcher entry (mock L404).

**Decided, 2026-11-14:**
- a fresh install starts on **Legacy**, the alias already on in the manifest (mock L450);
- the names under each design: **Tile**, **Ribbon**, **Picture**, **Lit**, **Letter**, **Fan**, **Page** (mock L1791);
- **Export carries the app icon.** Which alias is on is Android's to hold, so Export reads it from the package manager into the export, and Import asks "Also use this app icon?" before it switches; No keeps the icon as it is (mock L430).

**Still to do:**
- the three device checks, on a test build with a few aliases (mock L415): where the icon goes and whether Filet closes; whether pinned shortcuts survive; whether the hold menu comes back.

The risk the mock flags stays flagged: Ember sits close to Filet Debug's amber (mock L385).

## 6. Where the app differs from the mock, and why

- **The App icon row** scrolls the mock to section 8 (mock L1901); in the app it opens the picker as a sheet, with the dialogue kit's open and close.
- **The new-section tint and border** (`.newblock`, mock L113) mark what's new in the mock only. The app draws the section like every other.
- **The Today phone, the Build and Theme controls and Reset the demo** are for reviewing. The app uses this build's colours and the phone's theme.
- **The home-screen frames after a switch** are what Android's rules predict, not what a launcher was seen to do. They wait on the device checks.
- **The legacy drawings** in the mock are read off `ClassicIcons.kt` and `Icons.kt` (mock L1091). The app keeps its own code; only the emblem on the Legacy folder (surface 11) and the greyed tick (surface 22) are new drawing.

## 7. Tokens

Theme, dark (mock L7): `--bg:#161614; --surface:#1C1C1A; --raised:#232320; --sunken:#121210; --high:#2A2A26; --line:#2E2E2A; --lineSoft:#232320; --fg:#E8E6E1; --fg2:#9A968D; --fg3:#6B6860; --accent:#7FA8B8; --onAccent:#0E1417; --sel:#243036;`
Theme, light (mock L12): `--bg:#F5F4F0; --surface:#FFFFFF; --raised:#FAF9F6; --sunken:#EBE9E3; --high:#E4E1DA; --line:#DDDAD2; --lineSoft:#E9E6DF; --fg:#22211E; --fg2:#615D55; --fg3:#8B867C; --accent:#3E6E82; --onAccent:#FFFFFF; --sel:#E3EDF1;`

**Settings rows**

These are Settings' own rows: `SegmentRow` (`settings/SettingsPage.kt:2084`), with `sub = true` for the indented rows (`SUB_INDENT`, `settings/SettingsPage.kt:1923`: 14 + 30 = the mock's 44), and `HoldHint` (`ui/tabs/TabKit.kt:1218`) for each ⓘ. The group labels and the sample card are new.

| Selector | Mock | Values |
|---|---|---|
| `.sec .t` | L105 | `font-size:13.5px; font-weight:600; color:var(--fg2); padding:0 14px 9px;` |
| `.segrow` | L107 | `padding:6px 14px;` |
| `.segrow.indent` | L108 | `padding-left:44px;` |
| `.segrow .l` | L109 | `font-size:12px; color:var(--fg2); display:flex; align-items:center; gap:4px;` |
| `.segrow .chips` | L110 | `margin-top:5px; display:flex; width:max-content; max-width:100%; flex-wrap:wrap; border:1px solid var(--lineSoft); border-radius:8px; overflow:hidden;` |
| `.segrow .chips span` | L111 | `font-size:11.5px; padding:6px 12px; color:var(--fg2); white-space:nowrap; cursor:pointer;` |
| `.segrow .chips span.on` | L112 | `background:var(--accent); color:var(--onAccent);` |
| `.grouplabel` | L115 | `font-size:12px; color:var(--fg2); padding:8px 14px 0; display:flex; align-items:center; gap:4px;` |
| `.info` | L114 | `width:13px; height:13px; border-radius:50%; border:1px solid var(--fg3); color:var(--fg3); font-size:9px; line-height:11px; text-align:center; font-style:normal; cursor:help;` |
| `.lpreview` | L133 | `margin:4px 12px 8px; border-radius:12px; background:var(--sunken); border:1px solid var(--lineSoft); padding:9px 8px 8px; display:flex; justify-content:space-around; align-items:flex-end;` |
| `.lpreview > div` | L134 | `display:flex; flex-direction:column; align-items:center; gap:4px; font-size:9.5px; color:var(--fg3);` |
| `.lpreview .ic` | L135 | `height:34px; display:grid; place-items:center;` |
| `.navrow` | L292 | `display:flex; align-items:center; gap:8px; padding:9px 14px; cursor:pointer;` |
| `.navrow .l` | L293 | `flex:1; font-size:12px; color:var(--fg2); display:flex; align-items:center; gap:4px;` |
| `.navrow .v` | L294 | `display:flex; align-items:center; gap:7px; font-size:12px; color:var(--fg);` |
| `.navrow .mi` | L295 | `width:24px; height:24px;` |
| `.navrow .chev` | L296 | `color:var(--fg3); font-size:17px; line-height:1;` |

**Lists, grids, galleries**

| Selector | Mock | Values |
|---|---|---|
| `.lrow` | L138 | `display:flex; align-items:center; padding:0 12px; height:46px; gap:10px;` |
| `.lrow .ic` | L139 | `width:24px; height:24px; flex:none; display:grid; place-items:center;` |
| `.tile .slot` | L150 | `width:64px; height:64px; display:grid; place-items:center;` |
| `.gal .t` | L158 | `aspect-ratio:1; border-radius:6px; background:var(--sunken); overflow:hidden; display:grid; place-items:center; position:relative;` |

**Tabs and the bar picker**

| Selector | Mock | Values |
|---|---|---|
| `.chip` | L164 | `display:flex; align-items:center; padding:5px 8px; margin:3px 2px; border-radius:7px; flex:none;` |
| `.chip .g` | L166 | `display:grid; place-items:center;` |
| `.chip .tl` | L167 | `font-size:11.5px; color:var(--fg2); margin-left:5px; max-width:110px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;` |
| `.arttile` | L193 | `width:30px; height:30px; flex:none;` |
| `.glyphbox` | L196 | `width:30px; height:30px; display:grid; place-items:center; flex:none;` |
| `.glyphtile` | L195 | `width:30px; height:30px; border-radius:8px; background:var(--sunken); border:1px solid var(--lineSoft); display:grid; place-items:center; flex:none;` |
| `.dragrow` | L173 | `display:flex; align-items:center; gap:10px; height:50px; padding:0 6px;` |

**Shortcuts tab**

| Selector | Mock | Values |
|---|---|---|
| `.srow` | L205 | `display:flex; align-items:center; gap:11px; margin:0 12px 7px; padding:9px 11px; border-radius:10px; border:1px solid var(--lineSoft); background:var(--raised);` |
| `.srow .l` | L207 | `font-size:13px; color:var(--fg);` |
| `.srow .d` | L208 | `font-size:10px; color:var(--fg3);` |

**Show emblem tick (DlgTick)**

`DlgTick` (`ui/dialogs/DlgKit.kt:512`) draws the ticked and unticked rows already; the four `.grey` rules are what it draws when `enabled` is false.

| Selector | Mock | Values |
|---|---|---|
| `.ci-tick` | L265 | `display:flex; gap:10px; align-items:flex-start; border-radius:11px; padding:10px 11px; background:var(--sunken); border:1px solid var(--line); margin-top:10px; text-align:left; width:100%; color:var(--fg);` |
| `.ci-tick b` | L267 | `flex:none; width:18px; height:18px; border-radius:5px; border:1px solid var(--line); margin-top:1px; display:grid; place-items:center;` |
| `.ci-tick.on` | L268 | `background:color-mix(in srgb, var(--accent) 9%, transparent); border-color:var(--accent);` |
| `.ci-tick.on b` | L269 | `background:var(--accent); border-color:var(--accent); color:var(--onAccent);` |
| `.ci-tick b svg` | L270 | `width:12px; height:12px;` |
| `.ci-tick .l` | L271 | `font-size:12.5px; color:var(--fg);` |
| `.ci-tick .s2` | L272 | `font-size:10.5px; color:var(--fg3); line-height:15px;` |
| `.ci-tick.grey, .ci-tick.grey.on` | L273 | `background:var(--sunken); border-color:var(--line); cursor:default;` |
| `.ci-tick.grey b, .ci-tick.grey.on b` | L274 | `background:var(--high); border-color:var(--line); color:var(--fg3);` |
| `.ci-tick.grey .l` | L275 | `color:var(--fg3);` |
| `.ci-tick.grey .s2` | L276 | `color:var(--fg2);` |

**App icon picker (the dialogue kit)**

The dialogue kit: `Dlg` (`ui/dialogs/DlgKit.kt:138`), `DlgHeader` (`ui/dialogs/DlgKit.kt:259`), `DlgSection` (`ui/dialogs/DlgKit.kt:773`) and its buttons. The two previews, the Design grid and the Colour row are new.

| Selector | Mock | Values |
|---|---|---|
| `.ci-sheet` | L232 | `width:340px; background:var(--raised); color:var(--fg); border-radius:20px 20px 0 0; box-shadow:0 -10px 40px rgba(0,0,0,.35); border:1px solid var(--line);` |
| `.ci-sheet .dh` | L234 | `display:flex; align-items:flex-start; gap:11px; padding:15px 16px 12px;` |
| `.ci-sheet .dh .di` | L235 | `width:32px; height:32px; border-radius:9px; flex:none; display:grid; place-items:center; color:var(--accent); background:color-mix(in srgb, var(--accent) 18%, transparent);` |
| `.ci-sheet .dh .t` | L238 | `font-size:14.5px; font-weight:500; line-height:1.3;` |
| `.ci-sheet .dh .s` | L239 | `font-size:11px; color:var(--fg3); line-height:15px; margin-top:2px;` |
| `.ci-sheet .db` | L242 | `padding:2px 16px 14px;` |
| `.ci-sheet .df` | L243 | `display:flex; gap:8px; padding:10px 16px 14px;` |
| `.ci-sheet .btn` | L244 | `flex:1; text-align:center; border-radius:10px; padding:9px 15px; font-size:12.5px; background:var(--raised); border:1px solid var(--line); color:var(--fg);` |
| `.ci-sheet .btn.p` | L245 | `background:var(--accent); border-color:transparent; color:var(--onAccent); font-weight:500;` |
| `.ci-sheet .btn[disabled]` | L246 | `opacity:.45; cursor:default;` |
| `.ci-sec` | L247 | `display:flex; align-items:center; gap:10px; padding:14px 0 8px;` |
| `.ci-sec span` | L248 | `font-size:9.5px; letter-spacing:1.2px; font-weight:600; color:var(--fg3); text-transform:uppercase; white-space:nowrap;` |
| `.ci-sec i` | L249 | `flex:1; height:1px; background:var(--lineSoft);` |
| `.ci-hps` | L277 | `display:flex; gap:8px;` |
| `.ci-hp` | L278 | `flex:1; border-radius:14px; padding:10px 0 8px; display:flex; flex-direction:column; align-items:center;` |
| `.ci-hp .ic` | L279 | `width:52px; height:52px;` |
| `.ci-hp .nm` | L280 | `font-size:11px; padding-top:5px; color:#fff; text-shadow:0 1px 2px rgba(0,0,0,.6);` |
| `.ci-hp.wl .nm` | L281 | `color:#1B1A17; text-shadow:none;` |
| `.ci-sheet .ci-designs` | L282 | `display:grid; grid-template-columns:repeat(6, 1fr); gap:5px;` |
| `.ci-sheet .ci-designs button` | L283 | `border-radius:10px; background:var(--sunken); border:1.5px solid transparent; padding:4px 2px 3px; display:flex; flex-direction:column; align-items:center; font-size:10.5px; color:var(--fg3);` |
| `.ci-sheet .ci-designs button .m` | L284 | `width:38px; height:38px;` |
| `.ci-sheet .ci-designs button.on` | L285 | `background:var(--sel); border-color:var(--accent); color:var(--fg);` |
| `.ci-sheet .ci-icolours` | L286 | `display:flex; gap:5px;` |
| `.ci-sheet .ci-icolours button` | L287 | `flex:1; display:flex; align-items:center; gap:6px; border-radius:10px; background:var(--sunken); border:1.5px solid transparent; padding:6px 7px; font-size:11.5px; color:var(--fg2);` |
| `.ci-sheet .ci-icolours button i` | L288 | `width:16px; height:16px; border-radius:50%; flex:none;` |
| `.ci-sheet .ci-icolours button.on` | L289 | `border-color:var(--accent); background:var(--sel); color:var(--fg);` |
| `.ci-cap` | L290 | `font-size:11px; color:var(--fg3); line-height:15px; padding-top:8px;` |
| `.ci-cap b` | L291 | `color:var(--fg); font-weight:500;` |

**Drawing values**, copied from the mock line by line

| What | Mock | Literal |
|---|---|---|
| Legacy folder, back (100 box) | L1168 | `` const CBACK = 'M5,33 Q5,20 12,20 L36,20 L50,33 L88,33 Q95,33 95,40 L95,81 Q95,88 88,88 L12,88 Q5,88 5,81 Z'; `` |
| Legacy folder, front (100 box) | L1169 | `` const CFRONT = 'M9,44 L91,44 Q98,44 94.5,51 L91,83 Q91,90 84,90 L16,90 Q9,90 9,83 L5.5,51 Q2,44 9,44 Z'; `` |
| Legacy folder, back gradient: tint lightened .10, down to the tint | L1164 | `` `<linearGradient id="cfb${id}" gradientUnits="userSpaceOnUse" x1="0" y1="20" x2="0" y2="88"><stop offset="0" stop-color="${lighten(tint, .10)}"/><stop offset="1" stop-color="${tint}"/></linearGradient>` + `` |
| Legacy folder, front gradient: lightened .28, down to .02 | L1165 | `` `<linearGradient id="cff${id}" gradientUnits="userSpaceOnUse" x1="0" y1="44" x2="0" y2="90"><stop offset="0" stop-color="${lighten(tint, .28)}"/><stop offset="1" stop-color="${lighten(tint, .02)}"/></linearGradient>`); `` |
| Pictures: up to three, the cover in the middle | L1175 | `` if (f.cover) pics = [pics[0], f.cover, pics[1]].filter(Boolean).slice(0, 3); `` |
| No pictures below 22dp (`CLASSIC_PREVIEW_FROM`) | L1176 | `` if (px < 22) pics = []; `` |
| Fan: 5° apart, 9 units apart per step | L1178 | `` const lean = (i - (pics.length - 1) / 2) * 5; `` |
| A picture: 54 × 42, radius 5, centred 40 high | L1180 | `` return `<g transform="rotate(${lean} ${cx} ${cy})"><clipPath id="${k}"><rect x="${cx - 27}" y="${cy - 21}" width="54" height="42" rx="5"/></clipPath>` + `` |
| **New:** the emblem on the front, centre 50, 66.5, scale 1.3, in its colour with its shadow | L1183 | `` const emblem = f.emblem ? emblemOn(EM[f.emblem].parts, f.colour \|\| 'steel', 50, 66.5, 1.3, true) : ''; `` |
| Front's top highlight | L1186 | `` `<path d="M9,45.4 H91" stroke="#fff" stroke-opacity=".26" stroke-width="1.6"/>${emblem}</svg>`; `` |
| Legacy outline: FiletIcons.Folder's path in accent, at its own stroke width | L1192 | `` return `<svg viewBox="0 0 24 24" width="${px}" height="${px}" style="display:block"><path d="${g.d[0]}" fill="none" stroke="${c}" stroke-width="${g.width}" stroke-linecap="round" stroke-linejoin="round"/></svg>`; `` |
| Legacy file: monogram sizes by letter count, scaled by glyph/30 within .8 to 2.2 | L1214 | `` const t = file.ext.toUpperCase(), sp = [13, 12, 9.5, 8, 6.5][Math.min(t.length, 5) - 1] * Math.max(.8, Math.min(2.2, glyphPx / 30)); `` |
| Gallery tile, Legacy: glyph at side × .28 in fg3 | L1334 | `` const side = 64, glyph = Math.round(side * .28); `` |
| Tab chip, Legacy: glyph 13, accent when on, fg3 otherwise | L1392 | `` else g = line(c.glyph, c.on ? T.accent : T.fg3, 13); `` |
| Bar picker, Legacy: glyph 18 in fg2 in a 30 box | L1398 | `` const icon = (b) => look === 'drawn' ? `<div class="arttile">${tabArt(b.id, 30)}</div>` : `<div class="glyphbox">${line(b.glyph, T.fg2, 18)}</div>`; `` |
| Shortcuts tab, Legacy: glyph 16 in fg2 on the sunken tile | L1417 | `` const tile = (art, g) => look === 'drawn' ? `<div class="arttile">${tabArt(art, 30)}</div>` : `<div class="glyphtile">${line(g, T.fg2, 16)}</div>`; `` |

Sizes: lists draw folders and files at 24 (mock L1313); grid slots at 64 with a 42 glyph (mock L1330); the sample card at 30 and 34 (mock L1286); Change icon's previews at 96 (mock L1497); the picker's designs at 38 and its previews at 52 (mock L1844).

## 8. Motion

None to build. Every choice redraws at once, with no fade and no restart. The mock's one motion is its own navigation: the App icon row scrolls to section 8 (mock L1901), which the app replaces with the picker sheet and the dialogue kit's open and close. The launcher may take a few seconds to show a switched icon; that's the launcher, not Filet (mock L388).

## 9. Acceptance

- [ ] Settings › Appearance has **Legacy icons** after Name lines and before Row details: the sample card, Tabs, Files and folders (Folders, Files), Gallery (Folders, Files), Shortcuts tab, App icon, in that order (mock L1280).
- [ ] Every row's hold hint is the mock's text, word for word.
- [ ] A fresh install, and an update with no setting saved, look exactly like the app today.
- [ ] Each place changes only itself. Home, its category buttons, the bottom bar and its preview, home-screen shortcuts and other apps' icons never change.
- [ ] Pictures, video frames, album art and app icons look the same in every look.
- [ ] Legacy folders: up to three pictures fanned 5° apart, the cover in the middle, none below 22dp; the emblem on the front in its colour.
- [ ] Legacy outline: the bare outline everywhere it's chosen; no emblem, no pictures, no cover.
- [ ] Legacy files: the kind's line icon, or the extension as text (`data.xyz` reads XYZ).
- [ ] Gallery reaches the grid views and all six category galleries.
- [ ] Tab chips on Legacy: line glyphs; a folder tab is the plain outline.
- [ ] Shortcuts tab on Legacy: line glyphs on sunken tiles, rows and catalogue alike.
- [ ] Change icon: Show emblem under Emblem, on for every folder to start; unticking hides only that folder's emblem; Apply on several folders sets it on all.
- [ ] On Files and folders › Folders: Legacy outline, the tick greys with its line; the emblem grid and colours still work and the pick is kept.
- [ ] Change icon's preview draws in the lists' look.
- [ ] The shortcut sheet's Same as the folder carries the same tick, never greyed.
- [ ] Round trip: switch every look on and back to Drawn, and every folder record is byte-for-byte unchanged (a test), with every emblem back and no restart.
- [ ] Export carries `look.legacy.v1` and the app icon; the folder records travel as they already do. Import asks "Also use this app icon?" before switching; No changes nothing.
- [ ] A fresh install starts on Legacy. The picker shows the names Legacy, Tile, Ribbon, Picture, Lit, Letter, Fan, Page.
- [ ] App icon: the picker offers Legacy and the seven chosen designs in the four colours; Switch is off when nothing changes; a switch moves the launcher entry and republishes the hold menu at once.
- [ ] Filet Debug keeps its amber in every design.
- [ ] The update that brings the aliases re-places the home-screen icon once (option A), and "Open with Filet" still works.
- [ ] The three device checks are answered on a real phone before the App icon row ships.
