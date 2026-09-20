# Redesign — the translation spec

> **Generated.** `node tools/make-redesign-spec.mjs`. Every line number is read out of the
> source when this runs, because a hand-typed citation rots on the next edit and a rotted
> citation is worse than none — it sends somebody to the wrong place and looks authoritative
> doing it. Re-run it whenever the mock or the code moves.

The mock is `filet-redesign-mock.html`. This is the map between it and the app: what to open,
what draws it now, what draws it in the mock, which gate it answers, and the one thing about
that surface which is easiest to lose on the way across.

## Every surface

| Surface | In the app today | In the mock | Gate | The thing not to lose |
|---|---|---|---|---|
| **Tab strip** | `app/src/main/java/dev/niccc2007/filet/browser/BrowserScreen.kt:212` | `[data-r=3] .tab` | N54 | A raised capsule on a recessed track, one accent hairline that moves. Not a border per tab. |
| **Home tab** | `app/src/main/java/dev/niccc2007/filet/home/HomeOverview.kt:65` | `TABS3.home` | N59 | Storage tiles first, then arrivals, then recents, then places. The long heading stays here. |
| **New files tab** | `app/src/main/java/dev/niccc2007/filet/home/FileHistoryScreen.kt:80` | `TABS3.tracked` | N53 | Tab label and path read New files; the heading on Home reads the long name. |
| **Nearby tab** | `app/src/main/java/dev/niccc2007/filet/nearby/NearbyScreen.kt:77` | `TABS3.nearby` | N59 | The QR, the URL and the code are one card. Running state is a dot, not a word. |
| **Scripts tab** | `app/src/main/java/dev/niccc2007/filet/script/ScriptsScreen.kt:56` | `TABS3.scripts` | N59 | Each script shows a snippet. A name alone says nothing about what a script does. |
| **Bookmarks tab** | `app/src/main/java/dev/niccc2007/filet/browser/Places.kt:45` | `TABS3.bookmarks` | N59 | A bookmark can point at a file, so the icon is the file kind and not always a star. |
| **Recent tab** | `app/src/main/java/dev/niccc2007/filet/browser/Places.kt:73` | `TABS3.recent` | N59 | Grouped by day. Long press removes one entry - it used to do nothing. |
| **Shortcuts tab** | `app/src/main/java/dev/niccc2007/filet/shortcuts/ShortcutsScreen.kt:48` | `TABS3.shortcuts` | N59 | Shows shortcuts the system has dropped, which is the only place they can be cleaned up. |
| **Remotes tab** | `app/src/main/java/dev/niccc2007/filet/remotes/RemotesScreen.kt:57` | `TABS3.remotes` | N59 | An unreachable remote is shown as unreachable and never waited for. |
| **Activity tab** | `app/src/main/java/dev/niccc2007/filet/jobs/ActivitySheet.kt:44` | `TABS3.activity` | N59 | Running above finished. The index job names the folder it is reading. |
| **About tab** | `app/src/main/java/dev/niccc2007/filet/about/AboutPage.kt:77` | `aboutHTML` | N58 | Four words from the repo, in one line. Watermark is the app mark, not the author seal. |
| **Settings tab** | `app/src/main/java/dev/niccc2007/filet/settings/SettingsPage.kt:98` | `setHTML` | N56 | Sub-tabs. The List pane carries a live view rendered by the same code as the real list. |
| **File row** | `app/src/main/java/dev/niccc2007/filet/browser/RowViews.kt:64` | `rowsHTML3` | N55 | No date column. Files show bytes, folders show item count. Tabular figures, not a code font. |
| **Search result row** | `app/src/main/java/dev/niccc2007/filet/browser/RowViews.kt:227` | `SearchResultRow` | N79 | Carries Available or Confirming while a pass runs, and nothing between passes. |
| **Scope chips** | `app/src/main/java/dev/niccc2007/filet/browser/PaneView.kt:321` | `.scopes` | N71 | Native search is dimmed and unpressable where the index never answers the scope anyway. |
| **Bottom bar** | `app/src/main/java/dev/niccc2007/filet/browser/BrowserScreen.kt:974` | `.foot` | N46 | One bar plus a More button. The setting that chose between bar and menu is deleted. |
| **Context menu** | `app/src/main/java/dev/niccc2007/filet/browser/ContextMenu.kt:55` | `DIALOGS.context` | N67 | Keeps the icon button row across the top. The app already splits these - see splitForContextMenu - and the first redesign dropped the row, then put back the wrong five. Delete lives in the row, so it is NOT repeated as a list entry. |
| **Context menu quick row** | `app/src/main/java/dev/niccc2007/filet/browser/ContextMenu.kt:204` | `.ctxbar` | N67 | Which actions get an icon button and which go in the list. Already a pure function - QUICK_IDS is copy, move, rename, send, delete. RELABEL ONLY: move is shown as Cut with scissors. The id stays move; do not add a second action. |
| **Actions popup** | `app/src/main/java/dev/niccc2007/filet/browser/PaneView.kt:770` | `actionsHTML` | N54 | Same icon row, then grouped entries, destructive last and separated. |
| **Open with** | `app/src/main/java/dev/niccc2007/filet/handlers/HandlerHost.kt:131` | `openWithHTML` | N81 | One dialogue. Filet viewers above, apps below, remember control in the footer beside Open. |
| **New folder** | `app/src/main/java/dev/niccc2007/filet/browser/Dialogs.kt:159` | `DIALOGS.newfolder` | N59 | Warns that a dot-prefixed name is never indexed by Android. |
| **Rename** | `app/src/main/java/dev/niccc2007/filet/browser/Dialogs.kt:159` | `DIALOGS.rename` | N59 | Selection stops at the dot, so typing replaces the name and keeps the extension. |
| **Delete** | `app/src/main/java/dev/niccc2007/filet/browser/Dialogs.kt:204` | `DIALOGS.delete` | N59 | Says there is no bin, and offers the holding folder instead. |
| **Properties** | `app/src/main/java/dev/niccc2007/filet/browser/Dialogs.kt:229` | `DIALOGS.properties` | N59 | Says creation time is not recorded rather than showing the modification time twice. |
| **Create archive** | `app/src/main/java/dev/niccc2007/filet/browser/Dialogs.kt:313` | `DIALOGS.archive` | N59 | Says zip leaves names readable and 7z does not. |
| **Extract** | `app/src/main/java/dev/niccc2007/filet/browser/ExtractSheet.kt:53` | `DIALOGS.extract` | N59 | Shows the plan before writing anything, so a clash is a question not a surprise. |
| **Archive save** | `app/src/main/java/dev/niccc2007/filet/browser/Dialogs.kt:204` | `DIALOGS.archsave` | N59 | Names the backup and the re-sign as steps, because both happen and neither is obvious. |
| **Folder picker** | `app/src/main/java/dev/niccc2007/filet/browser/FolderPicker.kt:44` | `DIALOGS.folder` | N59 | Can create a folder from inside itself. |
| **APK inspector** | `app/src/main/java/dev/niccc2007/filet/apk/ApkInspectorScreen.kt:57` | `DIALOGS.apk` | N64 | Needs a full-screen form as well as the sheet: it is a tool, not a question. |
| **XAPK inspector** | _new, nothing to replace_ | `DIALOGS.xapk` | N64 | New. Says plainly it is not an archive, which is why it could not be installed before. |
| **Metadata writer** | _new, nothing to replace_ | `metaHTML` | N46 | Fields first, support matrix folded away. Needs a full-screen form as well. |
| **Update sheet** | `app/src/main/java/dev/niccc2007/filet/update/UpdateSheet.kt:61` | `DIALOGS.update` | N68 | Keeps every element it has; the notes get the room and the chrome gets out of the way. |
| **Onboarding** | `app/src/main/java/dev/niccc2007/filet/signet/OnboardingScreen.kt:159` | `DIALOGS.onboard` | N69 | Stays a slideshow. The permission slide is the one that cannot become advertising. |
| **Crash** | `app/src/main/java/dev/niccc2007/filet/crash/CrashActivity.kt:96` | `DIALOGS.crash` | N65 | Taller, names the file it wrote, and offers to open it. |
| **Storage tool** | _new, nothing to replace_ | `storHTML` | N57 | New screen. Tree, largest, by type, clean up, and the tray. See STORAGE-TOOL.md. |
| **Appoint tray** | _new, nothing to replace_ | `.tray` | N80 | Tap to appoint as well as drag. Side panel in landscape, collapsed when empty. |
| **Theme picker** | `app/src/main/java/dev/niccc2007/filet/settings/SettingsPage.kt:98` | `appearanceHTML` | N84 | Twelve palettes plus a custom setter. Names describe the palette, never its source. |
| **App icon picker** | _new, nothing to replace_ | `APP_ICONS` | N84 | Launcher aliases. Switching one drops home-screen shortcuts to the old alias. |
| **App intro** | _new, nothing to replace_ | `IntroPolicy` | N42 | Off by default. A hard failsafe finishes it whatever the animation is doing. |

## Not found in the app

Nothing — every surface with an anchor was located.

## How to use this when the redesign is built

1. Open the mock and the cited file side by side. The mock is the specification; read values
   off it rather than matching them by eye.
2. Motion comes from the mock's CSS. `.18s var(--e-out)` is an 180 ms easing curve in Compose,
   not "a quick fade".
3. When a framework makes an approved behaviour awkward, build the behaviour. If it genuinely
   cannot be expressed, say so and propose the nearest faithful thing — never substitute
   silently and never report it as done.
4. When the mock changes, re-run this generator so the citations stay true.
