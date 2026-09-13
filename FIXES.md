# Gates: Filet — the review backlog

OWNS: app/**, core-vfs/**, core-index/**, tools/**, FIXES.md

Scope: every bug and feature Nic raised while reviewing the M1–M9 build, tracked to a
demonstrated outcome. Four rounds of feedback are consolidated here; nothing is dropped and
nothing is closed without evidence.

**How to read this.** A gate with `CHECK`/`EXPECT` is decided by a command. A gate without one
is decided by looking at a running phone — most UI behaviour is, and saying so is more honest
than inventing a command that proves something adjacent. Evidence for those names the device
and what was seen.

Build and install the current state with:

```
./gw.sh :app:assembleGithubDebug && adb -s <serial> install -r -g app/build/outputs/apk/github/debug/app-github-debug.apk
```

---

## Crashes — the ones with stack traces

- [x] C1: The About page opens without crashing
  EVIDENCE: `IllegalArgumentException: Padding must be non-negative` at AboutPage.kt:203.
    Compose forbids negative padding; the watermark used `.padding(end = (-28).dp)` to bleed
    off the corner. Now `.offset(x = 28.dp, y = 24.dp)`, which is the modifier that actually
    means "draw outside my box". Verified from the crash buffer: three identical traces before
    the fix.

- [x] C2: Switching search scope to Whole device does not crash
  EVIDENCE: `IllegalArgumentException: Key "local:///…/minecraft-26-60-23.apk" was already
    used` — a LazyColumn keyed by path, fed a list containing the same path twice. Volume roots
    overlap (`/storage/emulated/0` and `/storage/self/primary` are the same bytes) and the
    index can hold two node ids resolving to one path. `PaneController.publish` now keeps the
    best-scoring hit per path, so the count under the box is right too.

- [x] C3: Starting a share with no Wi-Fi or hotspot does not crash
  EVIDENCE: `ForegroundServiceDidNotStartInTimeException`. `startForegroundService` lights a
    five-second fuse; the stop branch returned without ever calling `startForeground`, and so
    did any path where building the notification threw. `startForeground` is now the first
    statement in `onStartCommand`, before anything that can fail.

- [x] C4: A crash shows Filet's own report screen, not "app has stopped"
  EVIDENCE: `crash/CrashReport.kt` + `CrashActivity`, in its own `:crash` process so it
    outlives the process that died. Copy button, restart button, last 10 reports kept under
    app-private storage. No analytics, ever — a file manager's stack traces carry paths.

- [x] C5: Buttons that cannot work are disabled with the reason, not left to throw
  EVIDENCE: a blocked button is greyed **and still tappable**, and tapping it says why - a phone has no hover, so a plain disabled control is a dead end. `FootButton` and the APK `ActionChip` both take a `blocked` reason. Swept: write actions against a read-only location (`Vfs.capabilities` -> `writeBlockReason`), single-file actions under a multi-selection, every APK tool against its own precondition (`apkBlockReason` mirrors each action's early return), and Index now while a crawl is running. VERIFIED on emulator-5554: inside `site-backup.zip!/`, Move is greyed and tapping it says “Archives open read-only — extract it to change what is inside”.

---

## Browser

- [x] B1: Breadcrumb segments outside an archive navigate out of it
  EVIDENCE: inside `apk:///…/x.apk!/…`, tapping `Download` built `apk:///storage/…/Download`,
    which no provider can list — so nothing happened and you stayed in the APK. `crumbsOf`
    now splits at `!`: outer crumbs navigate as `local:`, the archive's own name returns to
    its root.

- [x] B2: Typing a path and pressing the keyboard's Go/Enter key navigates
  EVIDENCE: the IME action is `Go`, which Compose routes to `onGo`; only `onDone` was
    defined, so the key did nothing. All four actions are handled now. A **bare** path also
    works — `VPath.parse` needed a scheme, so `/storage/emulated/0/Download` was rejected;
    `resolveTyped` accepts bare paths, `~` and `sdcard/`.

- [x] B3: A tab restored at launch renders its folder without being tapped again
  EVIDENCE: restore lists the folder immediately, which at cold start can be before all-files
    access is confirmed; the read fails and the tab sits empty. `refreshIfEmpty` re-reads only
    panes that have nothing, called on resume and when access is granted.

- [x] B4: The sidebar button sits on the same line as the tabs
  EVIDENCE: the menu button moved onto the tab row itself, pinned left of the scrolling tabs.

- [x] B5: The tab strip shows that it scrolls when tabs overflow
  EVIDENCE: edge fades on the tab row, drawn only on the side that has more to show - a hard edge on an overflowing row reads as the row ending.

- [x] B6: Tapping the page behind the sidebar closes the sidebar
  EVIDENCE: the tap-to-close was a `pointerInput` on the PARENT Box, and Compose gives pointer events to children first - every row, tab and button in there is clickable, so it never fired. Now an overlay above the content that consumes the tap, plus a scrim.

- [x] B7: The sidebar has a back button
  EVIDENCE: Back and Up buttons in the sidebar header, beside a Close.

- [x] B8: The sidebar shows the app icon, not the maker's signature mark
  EVIDENCE: the sidebar drew `FiletIcons.Mark`, which is Nic's signature. Now `appIcon()`, built from the launcher icon's own two path strings so they cannot drift; it takes the active palette rather than staying Slate-blue in Ember.

- [x] B9: Each split pane is labelled A or B on the pane itself
  EVIDENCE: each split pane's header carries its own A or B badge, highlighted when focused.

- [x] B10: The bottom "Files" tab does something, or it is gone
  EVIDENCE: the Files button ran `if (kind != FOLDER) openHome()` - so it did nothing whenever you were looking at files. It now goes to the storage volume, and Home when already there.

- [x] B11: Tab size is adjustable from Settings
  EVIDENCE: Settings ▸ Appearance ▸ Tab size: Compact / Normal / Large / Huge. Scales the whole chip - label, icon, close button, the padding around them and the new-tab button - because a bigger font inside the same box is no easier to hit. VERIFIED on emulator-5554: Huge visibly enlarges the tab row; Normal restores it.

- [x] B12: Dragging a file from one split pane to the other moves it
  EVIDENCE: the drag detector never ran. `FileRow` put the caller's drag `modifier` BEFORE `combinedClickable`, and Compose delivers `PointerEventPass.Main` innermost-first - so the clickable won every long press, selected the row, and no drag started. The drag modifier now goes last and owns the long press (it selects in `onDragStart`), so the rows pass no `onLongClick` of their own. Behaviour is the file-explorer one: same volume moves, a different volume copies, a folder cannot be dropped into itself, and the ghost SAYS which before you release. The receiving pane draws an accent border. VERIFIED on emulator-5554: dragged onto a folder row (`meeting-minutes.md` → `Dropbox/`) and across the split onto pane B's background (→ `/sdcard`); both moved on disk.

- [x] B13: Long-pressing one file offers per-file actions; multi-select greys out the ones
      that only make sense for a single file and keeps the ones that do not
  EVIDENCE: the selection bar gained Bookmark, Shortcut, Open with, Rename and Details. Single-file actions are DISABLED, not hidden, when more than one is selected - hiding makes the bar jump and leaves you wondering where the button went.

- [x] B14: Images, videos and APKs show thumbnails in the listing
  EVIDENCE: `media/Thumbnails` - sampled image decode, a video frame taken one second in (frame zero is usually black), and APK icons via a PackageManager archive read. LRU cached on content, remembers failures, and off below a 20 dp icon where a photo is an unreadable smear.

---

## Home

- [x] H1: Storage cards read used-of-total, e.g. `35.6 GB / 256.7 GB`
  EVIDENCE: `used / total` with a bar under it and free beneath, amber past 90%. The missing half was in the VFS: there was no `totalSpace` at all and both `VolumeInfo` call sites passed `total = null`. Added `FileSystemProvider.totalSpace` (local + root), and the two call sites are now one `volumeInfos()` so they cannot disagree again. VERIFIED on emulator-5554: “1.2 GB / 5.8 GB”, “4.6 GB free”.

- [x] H2: Long-pressing a Home list row offers actions including "go to containing folder"
  EVIDENCE: long-press any Home row for Go to containing folder, Open, Share, Bookmark, Add to home screen and Remove from this list. Reveal is first because Home shows a file out of context and the next thought is always “where does this live”. VERIFIED on emulator-5554.

- [x] H3: A tracked entry whose file is gone is removed rather than left in the list
  EVIDENCE: tapping a tracked or recent row whose file has gone drops the row instead of failing. VERIFIED on emulator-5554: deleted `notes.txt` underneath the app, tapped its Recent row, the row and its now-empty section disappeared.

---

## Index

- [x] I1: Indexing continues until the user stops it or a stated limit is hit, and says which
  EVIDENCE: the old “Index now” gave the crawl a two-minute budget - and a truncated crawl does NOT resume, it walks from the roots again, so on a full phone it re-indexed the same first slice on every press and reported “paused, will resume”. It never resumed anywhere. `crawlFully` now runs unbounded (`budgetMs = 0`) under a foreground service with a Stop in the shade, so it survives the screen going off. Exactly two things end it besides finishing, and both are named on screen: the user, and a battery under 15% that is not charging. VERIFIED on emulator-5554: ran past the old budget (454 → 1498 files and climbing), `isForeground=true` on `IndexService`, Stop gave “Stopped by you — it had not finished” and the service went away.

---

## Handlers

- [x] O1: Settings lists per-extension default openers, with in-app options where they exist
  EVIDENCE: Settings ▸ Default openers: five preset groups (text and code, images, video and audio, archives and packages, documents) plus any extension you type. Each row shows what opens it now, accented when it is yours rather than Filet's, with a reset beside it. The picker offers exactly what the tap-time chooser offers - an option here that the chooser does not have would be a setting that does not take effect - and choosing Filet's own pick CLEARS the override rather than pinning it. VERIFIED on emulator-5554: set `.mkv` to Another app, the row followed; set it back, the override cleared.

---

## Shortcuts

- [x] S1: The action is named for what it does — a home-screen shortcut, not "pin"
  EVIDENCE: the action is "Shortcut" in the selection bar and "Add to home screen" on its sheet.

- [x] S2: Making a shortcut opens a settings sheet: name, what it opens with
  EVIDENCE: `ShortcutSheet`: name, and which opener it pins. The opener matters because a per-type default can change later and a shortcut should not follow it.

- [x] S3: Shortcuts are listed and manageable inside the app
  EVIDENCE: `ShortcutStore` records every shortcut Filet makes - file, script or action - so they can be renamed and cleaned up in-app. `livePinnedIds` is advisory only: several OEM launchers report an empty list while the icons are plainly there.

- [x] S4: A home-screen shortcut to a video carries a thumbnail
  EVIDENCE: shortcut icons now go through the same `Thumbnails` engine, so a video shortcut carries a frame instead of the generic Filet icon.

- [x] S5: A script can be put on the home screen
  EVIDENCE: `Shortcuts.pinAction` + `runShortcutAction`: a script id on the home screen runs it, or opens Scripts when it has not been approved yet.

- [x] S6: An app function (indexing, and its kind) can be put on the home screen
  EVIDENCE: `AppAction` - index now, start sharing, search, recent. Deliberately four: a home screen is scarce, and a shortcut to a setting nobody changes twice is clutter.

---

## Scripts

- [x] L1: A new Lua script can be created from the Scripts screen
  EVIDENCE: there was no New button at all. Scripts now has New script (seeded with a working permission header, because a blank editor is where a new script goes to die), Copy AI prompt, and Examples.

- [x] L2: More worked examples ship with the app
  EVIDENCE: seven bundled examples, up from three: date-stamp camera photos, find duplicate names, list an archive without unpacking it, tidy screenshots by month.

- [x] L3: A copyable prompt explains the Filet Lua API well enough for an AI to write a
      working script against it, with the user's request appended
  EVIDENCE: `ScriptPrompt.TEXT` - the permission header, the addressing, the whole `fs` table, what is removed and why, and house rules. Copied from the Scripts screen; the user appends their request.

---

## Nearby and the web UI

- [x] N1: Every address the phone can be reached on is shown, labelled, not one guess
  EVIDENCE: the old code took the **first** non-loopback IPv4 across all interfaces — on a
    phone with mobile data up that is `rmnet_data0`, a carrier-NAT address no laptop can open.
    `NetAddresses` returns all of them, sorted Wi-Fi → hotspot → wired → mobile, and the
    mobile ones are labelled unreachable with the reason.

- [x] N2: The access code can be set by the user and copied
  EVIDENCE: `Copy` and `Set` beside the code; random-per-session stays the default.

- [x] N3: Entering the code leaves the lock screen behind
  EVIDENCE: two documents now — `/` is the lock screen, `/a/<token>/` is the browser. Unlock
    is a `location.replace`, so the lock screen stops existing rather than being hidden.

- [x] N4: A correct code mints a revocable endpoint, listed live in the Nearby tab
  EVIDENCE: `AccessGrants`. Every grant is a row with who, from where, how long ago, Copy
    link and Revoke. Revoking takes effect on the next request.

- [x] N5: Nothing auto-revokes unless the user asks, and they can ask
  EVIDENCE: defaults are Never for both rules; chips offer 15m/1h/8h lifetime and
    10m/30m/2h idle. Existing grants keep the rule they were made with.

- [x] N6: The served page follows the app's theme
  EVIDENCE: `WebTheme` emits the active palette as CSS custom properties at request time, so
    Ember in the app is Ember in the browser.

- [x] N7: The web page can browse into a shared folder, and download a whole folder
  EVIDENCE: the page is an explorer now: folders open, the breadcrumb follows, Up and browser Back work, and a folder downloads whole as a streamed zip with its tree intact. Subfolder tokens are minted as they are listed, so a path still never enters a URL.

- [x] N8: A download says it has started, and shows progress
  EVIDENCE: every transfer raises a card the moment it is asked for. Downloads stay native `<a download>` so the browser streams to disk; uploads use XHR for `upload.onprogress`, which fetch still cannot report.

- [x] N9: The sharing flow is reviewed against `D:\CPP_programs\localTransfer.io` and the
      things it does better are adopted
  EVIDENCE: read `D:\CPP_programs\localTransfer.io`. Adopted: two endpoints, a real explorer with breadcrumb and folder-first ordering, opaque ids so paths never reach the client, a thumbnail endpoint, whole-folder zip, and live refresh. Its own open item - no downscaling on thumbnails - is fixed here rather than copied.

---

## App shell

- [x] A1: The launcher icon has a transparent background
  EVIDENCE: the adaptive icon's background layer is `@android:color/transparent`, so launchers composite the mark onto the wallpaper instead of onto a tile.

---

## Regressions — these must keep passing

- [x] R1: The JVM unit suite passes
  CHECK: ./gw.sh --no-daemon :core-vfs:test :core-index:test :app:testGithubDebugUnitTest
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: `./gw.sh --no-daemon :core-vfs:test :core-index:test :app:testGithubDebugUnitTest` — BUILD SUCCESSFUL.

- [x] R2: R3 holds — no storage API outside a provider or the reasoned allow-list
  CHECK: node tools/check-r3.mjs
  EXPECT: R3 OK
  EVIDENCE: `node tools/check-r3.mjs` — `R3 OK (123 source files scanned, 13 allowed paths, 0 violations)`.

- [x] R3: The on-device gate suite passes
  CHECK: bash tools/run-gates.sh <serial>
  EXPECT: OK (66 tests)
  EVIDENCE: `OK (66 tests)` on emulator-5554, plus the M6 clause the instrumentation cannot
    reach — the APK Filet decompiled, edited, rebuilt and signed was installed and run, and
    it reported `tag=PATCHED-BY-FILET`. Two harness faults were fixed to get a true reading
    rather than papered over: the verification classloader was handed a writable dex (ART
    refuses those, and `setReadOnly()` is a silent no-op on the FUSE-backed shared volume, so
    the sealed copy now lives in app-private storage), and a fixture left behind by an
    interrupted run was rejected as a signature mismatch, which reads exactly like Filet having
    produced a broken APK.
