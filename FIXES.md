# Gates: Filet — the review backlog

OWNS: app/**, core-vfs/**, core-index/**, tools/**, FIXES.md

Scope: every bug and feature Nic raised while reviewing the M1–M9 build, tracked to a
demonstrated outcome. Eight rounds of feedback are consolidated here; nothing is dropped and
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

---

## Found while closing the ledger

Not on Nic's list — these surfaced while verifying the gates above, and are recorded here
because "found on the way" is how most of them were found.

- [x] X1: An empty search result says why it is empty
  EVIDENCE: whole-device search on a cold index returned nothing and said "Try Subfolders or
    Whole device" — advice to do the thing you are already doing, on a search the app knew the
    answer to. `lastRunAt` is only stamped after a crawl that ran to completion, so it is
    exactly the "has the index ever seen the whole device" flag; the note now distinguishes a
    genuinely empty folder, an index that is off, one still building, and one that has never
    finished a pass. Verified on emulator-5554: the same query returned all three invoices
    once the crawl had reached them.

- [x] X2: The launcher icon fits the mask that is applied to it
  EVIDENCE: the drawable's own comment claimed "every structural edge is inside [the safe
    zone], so no launcher mask can crop the mark". Measured: the body reached x=16 and the
    flap x=92, both outside 18..90, and the splash screen was visibly clipping them. Both
    layers now carry an identical 0.945 group scale about the canvas centre, and
    `tools/check-icon.mjs` flattens the paths and decides it rather than a comment asserting
    it. Exercised against a positive control — set the scale back to 1.0 and it fails, naming
    both edges.

- [x] X3: The shell scripts are executable on a fresh clone
  CHECK: git ls-files -s gradlew gw.sh tools/run-gates.sh
  EXPECT: 100755
  EVIDENCE: CI failed with exit 126 — found, but not executable. Git on Windows does not track
    the executable bit unless it is already in the index, so `gradlew` and every `tools/`
    script went in as 100644 and nothing on a Linux or macOS clone could run them. Fixed with
    `git update-index --chmod=+x`, not with a `chmod` step in the workflow, which would have
    hidden the same problem from anyone cloning by hand.

- [x] X4: Line endings survive a Windows checkout
  CHECK: git check-attr text -- gradlew
  EXPECT: text: unset
  EVIDENCE: `core.autocrlf=true` on this machine would have committed `gradlew` and `gw.sh`
    with CRLF, which makes them unrunnable on Unix with an error ("bad interpreter: /bin/sh^M")
    that does not name its cause. `.gitattributes` pins `* -text`, so the working copy, the
    blob and what a cloner gets are the same bytes.

---

## Round 5 - while he was testing

- [x] X5: "Hand to another app" remembers which app
  EVIDENCE: it could not. `Intent.createChooser` is Android's chooser and it never tells the
    calling app what was picked, so EXTERNAL could be saved as a default with nothing to save
    beside it and the chooser came back every time. Filet resolves the candidates itself now
    and launches the component directly, storing package + activity per extension. First use
    remembers with the box already ticked; Settings can pre-set one before a file is ever
    opened; a shortcut pinned to "another app" opens the remembered one instead of prompting.
    VERIFIED on the Huawei: `.mkv` set to Video Player, written to `handlers.external.v1` as
    `com.lenovo.anyshare.gps/com.lenovo.anyshare.VideoPlayer`, row renamed to match, reset
    cleared both keys.

- [x] X6: Settings ▸ Default openers is findable, and its app list appears
  EVIDENCE: two faults. It sat second-to-last, under a Tracked-folders list that runs to a
    dozen rows, so "where is that?" was the honest reaction; it is now third from the top,
    directly under Browsing. And tapping "Another app" did nothing at all: the picker was a
    `Box(fillMaxSize)` emitted from `SettingsPage` right after its `LazyColumn(fillMaxSize)`,
    two siblings, so it laid out at zero height. It is a real `Dialog` window now, which is
    the only reason the `AlertDialog` beside it had ever worked.

- [x] X7: An app that never declared the type can still be chosen
  EVIDENCE: the declared list is correct and incomplete - an app may declare only `file://`,
    or a vendor mime, or nothing, and then looks uninstalled. Two tiers: the resolved
    handlers, then "All apps on this phone" with each row labelled by what it does declare.
    The second tier can fail, which is stated on the divider, and a default that refuses the
    file is cleared automatically.

- [x] X8: Music files show their cover art, in the listing and on a shortcut
  EVIDENCE: `MediaMetadataRetriever.embeddedPicture`, two-pass decoded like an image file
    because cover art is routinely 1200x1200 and a 40dp row does not need that. Read from the
    file's own tags rather than MediaStore's album-art table, which only knows what it has
    scanned and answers per album, so one mistagged track would give a whole folder the wrong
    cover. Shortcuts share the engine, so they inherit it.

- [x] X9: Back, forward, up and refresh, the way a desktop file manager has them
  EVIDENCE: the pane already kept the history; there was no way to reach it but the system
    back gesture. Four buttons left of the path bar, disabled rather than hidden so the set
    keeps its width and the breadcrumb does not shuffle sideways under your thumb after every
    navigation. 32dp wide, 28dp narrow. The first cut dropped Forward on a phone; measured on
    a 1080px screen the row had room to spare, so all four stay at every width.

- [x] X10: The in-app updater actually updates
  EVIDENCE: F46 was scaffolding - a flavour flag, a button, and a toast. `update/Updater.kt`
    reads GitHub Releases over `HttpURLConnection` and `org.json`, adding no dependency;
    `UpdateSheet` shows the notes, the download progress and Install. It never installs
    anything itself: the system package installer opens and asks. The downloaded file handle
    stops inside the updater rather than travelling up into the view model, because R3 caught
    it doing exactly that. VERIFIED on the Huawei against the live repo: reached GitHub and
    answered "No releases published yet" rather than blaming the network.

- [x] X11: Crash reports are readable without a cable
  EVIDENCE: app-private storage is the safe place for a stack trace and also a place nobody
    can reach without adb. Reports are mirrored to `/storage/emulated/0/.filet_logs/crash/`,
    dotted to stay out of the gallery scanner, capped at 30, best-effort so a failed mirror
    never replaces a crash report with a second crash.

---

## Round 6 — two urgent bugs, the choosers, the three viewers, and search during a crawl

Round 6 went in with its own working ledger (`.unlazy/viewers/GATES.md`, 27 gates, all met).
The rule it added: **a decision that can be wrong gets extracted into a pure function with a
test.** Both urgent bugs below existed because the decision lived inside an `onClick`, where
nothing could reach it to prove it wrong.

- [x] Y1: A bookmarked file opens as a file
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*PlaceOpenTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: `Places.kt` called `pane.navigateTo` on every bookmark row and `Bookmark` carried
    no kind, so a bookmarked `.pptx` was handed to the lister as a directory. The record now
    stores `isDir`, recorded at the moment of bookmarking because that is when it is free.
    Records saved before the field existed store nothing rather than guessing: `placeAction`
    returns `Resolve`, the caller stats once and writes the answer back. Guessing "folder" is
    the bug; guessing "file" would break every bookmark anyone already has. Verified on the
    Huawei with his own bookmark - PowerPoint took focus, and the stored record gained
    `"dir":false`.

- [x] Y2: An extension already routed to an app can be pointed at a different one
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*OpenerChoiceTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: on a type whose built-in already IS "Another app" - a `.pptx`, a `.docx` - the
    picker treated tapping that row as "you chose the default" and cleared the override
    instead of offering the app list. The only way out was to set the type to something else
    and back. `openerChoice` checks the external branch FIRST, and `OpenerChoiceTest` pins
    that ordering with the `.docx` case that made it wrong.

- [x] Y3: A chooser does not remember unless it is told to
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*OpenerChoiceTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: this reverses round 5, and the reversal is his call: "do not auto remember unless
    said so". Round 5 shipped the box pre-ticked on the reasoning that a setting you have to
    go and find is one you never find; what that actually did was write a permanent routing
    rule every time somebody opened one file in one app once. The tick box starts off and
    says what it will do. The handler sheet's Just once now carries the primary colour,
    because Always is the button with the lasting consequence.

- [x] Y4: The filtered app list is filtered
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*MimeTableTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: found while verifying Y2. `.pptx` had no entry in the type table, fell through to
    the wildcard, and a package-manager query with a wildcard matches every app that declares
    one - so "apps that handle this type" for a PowerPoint deck was Certificate Installer,
    HTML Viewer and Manage SIM contacts. The table now names the Office, document, archive
    and media types explicitly, falls back to Android's `MimeTypeMap`, and only then shrugs.
    A shrug now returns an empty filtered tier rather than dressing junk up as an answer, and
    `MimeTableTest` fails if a Settings preset ever offers an extension with no type. On the
    Huawei the same sheet now lists Docs, WPS Office and Huawei Print.

- [x] Y5: The image viewer edits: crop, draw, rotate, flip, invert, greyscale, resize
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*ImageOpsTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: every pixel operation is a pure function over an ARGB buffer in `ImageOps.kt`, so
    a transposed rotation or a crop that is one pixel out fails a build instead of quietly
    ruining a photo. The buffers in the test are deliberately not square and not symmetric,
    which are the two shapes that hide an axis swap. Invert leaves alpha alone, because
    complementing all 32 bits turns a transparent PNG inside out and reads as a broken decode.
    The crop frame is held in image pixels and converted through one tested `FitBox`, so the
    rectangle you draw is the rectangle you get. Verified on the Huawei end to end: cropped to
    the middle 70% of a 1200x800 PNG and inverted it, and the file pulled back off the phone
    is 840x560 and matches `crop(180,120,1020,680)` then invert at every sampled pixel.

- [x] Y6: An edit never writes over the original
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*ImageOpsTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: the save path does not get to choose a name. `editedName` returns one that is
    neither the original's nor any name already in the folder, it does not grow a suffix per
    save, and it renames the extension when the editor had to re-encode - a `.heic` comes back
    as JPEG, and a JPEG called `.heic` is a file nothing on the phone will open.

- [x] Y7: The music player is a player
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*QueueTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: what was there was a progress line, a filename, and three buttons - one of which
    was the Close icon standing in for Pause. Now: cover art and tags read from the file
    itself, a scrub bar with elapsed and remaining, a real transport, shuffle and repeat, and
    the folder you opened it from as a queue you can see and jump around in. The queue runs in
    the sort order that is on screen, because a queue that disagrees with the listing behind
    it looks like a shuffle nobody asked for. `QueueTest` covers the edges that a composable
    cannot: a folder of one, a track that arrived from search and is not in the listing, a
    track deleted underneath, and the wrap at both ends.

- [x] Y8: The video player scrubs under your finger, and its chrome is Filet's
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*SeekGestureTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: "odd looking and old" was `android.widget.MediaController`, the 2010 stock chrome,
    which draws in the platform's colours and whose bar only responds if you catch a thumb the
    width of a pencil. Replaced. Drag anywhere on the picture to scrub, with the time under
    the finger; the bar itself maps absolutely from the first touch, so putting a thumb halfway
    along means halfway along. Double tap left or right to jump ten seconds, and keep tapping
    to keep adding; the middle third is play/pause instead, because a thumb rests there and
    jumping the video because of that is the behaviour the band exists to prevent.

    **A real bug this found.** The chrome and the gesture layer started as two full-screen
    siblings, so a drag along the bar was delivered to both - the bar mapped it absolutely,
    the picture mapped it relatively, and whichever wrote last won. On the Huawei, dragging
    the bar from three quarters along to a fifth moved the video by the distance rather than
    to the place under the finger. They are laid out now rather than stacked: the gestures own
    the band between the bars, so one touch has exactly one owner. That is a layout fact
    rather than a race that happens to come out right. Re-measured: the same drag lands on
    0:09, which is where the finger left the bar.

- [x] Y9: Every viewer can hand the file to another app
  EVIDENCE: he found this looking at a PNG - the in-app viewer had no way out. Image, audio
    and video all carry it now, and it forces the picker rather than reusing a remembered app,
    which is what "open this somewhere else" means.

- [x] Y10: Searching during a crawl steers the crawl, and says so
  CHECK: ./gw.sh --no-daemon :core-index:testDebugUnitTest --tests "*CrawlPriorityTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: his report, and his fix. Search during the first crawl found nothing, because the
    crawler walks in its own order and had not reached the folder you meant. A query now bends
    the pending queue toward it for 45 seconds: folders whose name carries a query word first,
    shallow before deep, and the machine-written trees - `Android/data`, `Android/obb`,
    `.thumbnails`, `node_modules` - to the back for the duration.

    Two rules keep it safe, and both are what `CrawlPriorityTest` pins. **Nothing is dropped**:
    a detour is a reordering, never a filter, because a crawl that silently skips folders would
    then have the generation sweep delete everything in them. **The original order comes back
    exactly**: every pending entry carries the sequence number it was discovered with, so
    ending the detour is a sort rather than a hope. On the Huawei, searching "invoice" during a
    live crawl shows: *Indexing rerouted to "invoice" — 11591 files so far. Results keep
    arriving as the scan reaches them.*

---

## Round 7 — archives, the first release, and a ledger that was not kept

Round 7's own working ledger (`.unlazy/round7/GATES.md`, 52 gates) was **authored and then not
maintained**: the work shipped, the boxes never got ticked, and every evidence line sat at
`pending` until 2026-09-14, when each gate carrying a command was re-run and written up from
what it actually printed. That is recorded here rather than quietly cleaned up, because a
ledger nobody keeps is worse than no ledger — it looks like evidence and is not.

Where it landed: **32 met, 18 unmet device checks, 2 abandoned.**

- [x] G1: Filet reads and creates the archive formats a phone actually meets
  CHECK: ./gw.sh --no-daemon :core-vfs:testDebugUnitTest --tests "*ArchiveProviderTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: tar, tar.gz, tar.bz2, tar.xz, 7z and a single compressed file (gz, bz2, xz) all
    list through the VFS and read back byte for byte; zip, tar, the three tar variants and 7z
    can be created. One `Archives` table decides what can be listed, created, and what needs a
    real path — nothing else in the app answers those questions, so a format cannot be
    half-supported by two places disagreeing.

- [x] G2: There is a release, and the app can find it
  CHECK: node tools/check-release.mjs
  EXPECT: RELEASE OK
  EVIDENCE: his ask, twice. v0.1.0 was tagged and published with a signed APK attached. The
    checker asserts the three things that break quietly — a tag with no release, a release with
    no APK, and a release whose asset name the updater's own selector would skip — each of
    which looks fine on the releases page while "Check for updates" does nothing.

- [x] G3: Where a shortcut routes is one tested decision
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*ShortcutRouteTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: 11 tests. **And it was not enough** — see J9. The routing function was correct the
    whole time; the caller had nothing to route into. A green test on the right function is
    not evidence that the feature works, and this is the clearest example of it in the project.

- [x] G4: Dragging a list into a new order is a tested decision
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*ReorderTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: 13 tests over the move arithmetic, which is where a drag-to-reorder goes wrong:
    moving an item down is off by one against moving it up, and nothing about that is visible
    until somebody drags the last item.

- [x] G5: How this was built is written down, honestly
  CHECK: node tools/check-aiuse.mjs
  EXPECT: AIUSE OK
  EVIDENCE: `docs/AI_USE.md` divides the work rather than waving at it. It is deliberately
    **not** linked from the README — it is there for anyone who looks, not a badge.

- [x] G6: A shared folder of thousands of files does not lock up the browser looking at it
  CHECK: node tools/check-webui.mjs
  EXPECT: WEBUI OK
  EVIDENCE: the listing is windowed, rows are built off-document and thumbnails load lazily.
    A phone serving a folder to a laptop was the case that made this necessary: the phone is
    fine, the browser was not.

- [x] G7: Indexing carries on after the search that steered it is closed
  CHECK: ./gw.sh --no-daemon :core-index:testDebugUnitTest --tests "*CrawlPriorityTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: 14 tests. A detour is a **reordering, never a filter** — a crawl that silently
    skipped folders would let the generation sweep delete everything in them — and the original
    order comes back exactly, because every pending entry carries the sequence number it was
    discovered with.

- [x] G8: Refresh means something specific on every pane, with no kind left out
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*RefreshTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: 9 tests, and the assertion that matters is that no pane kind falls through to a
    default. A refresh button that does nothing on one screen is worse than no button, because
    it teaches people the data is current when it is not.

- [x] G9: A tracked folder can include everything beneath it, in one choice
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*TrackedFoldersTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: 6 tests. Tracking `Download` and meaning "and everything in it" is one toggle on
    the folder, not a second entry per subfolder.

- [x] G10: Which tab is active survives every way the tab list can change
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*TabsTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: 14 tests — closing the active tab, closing one before it, reordering, restoring.
    The index-based bug this pins is the one where closing tab 2 silently moves you to a
    different tab than the one you were looking at.

- [x] G11: The feed reads its folders at once, and a burst of changes is one refresh
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*FeedTimingTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: 7 tests. Folders are read several at a time but not all at once, and a copy that
    writes forty files produces one refresh rather than forty. The quiet window is long enough
    for a copy and short enough to feel instant — and J16 is where that claim was finally
    made true rather than merely timed.

- [x] G12: The formats table, the licence table and the README agree about RAR
  CHECK: node tools/check-licences.mjs
  EXPECT: LICENCES OK
  EVIDENCE: at the time, RAR was refused and every surface said so for the same reason. Round 8
    reversed the conclusion — see J12 — and the same checker is what kept the reversal honest
    across all of them.

**Still open from round 7, and not claimed:** compress/extract driven from a phone selection
(A7), cancelling a long compress (A8), `inzip:` inside a `.tar.gz` on the device (A10), the
unpinnable-shortcut message (K4), the four video-player gates (V1–V4), tab drag surviving a
restart (U4), both Nearby receive gates (N1, N2), subfolder navigation in the web UI (W2),
three search gates (Q1, Q3, Q4) and two refresh gates (F2, F4), and many-tabs behaviour (T2).
Eighteen device checks nobody has run. **Abandoned:** A9, whose command names a test that was
never written; H1, which Nic reversed in round 8 — the feed tracks files now, not folders.

---

## Round 8 — the release, the rendered changelog, RAR, and making copy and paste findable

- [x] J1: The updater works the way Trawl's does, end to end
  CHECK: node tools/check-release.mjs
  EXPECT: RELEASE OK
  EVIDENCE: his "[very important]". v0.1.0 published, then v0.1.2. A phone on 0.1.0 is offered
    the real release, names the version and the download size, and hands off to the system
    installer rather than installing silently. The APK is signed v2+v3 — v3 is the only scheme
    that carries a proof-of-rotation record, so signing without it means the key can never be
    changed without every installed copy refusing the update.

- [x] J2: The release APK installs on a phone that already has a debug build
  CHECK: node tools/check-appid.mjs
  EXPECT: APPID CONSISTENT
  EVIDENCE: found by the release test, not by a user. The two builds declared the **same**
    private permission name, because it was written out literally instead of built from the
    application id, and Android refuses to install a second app defining a permission another
    one owns. Both names are templated now, pinned by a test rather than only by the manifest,
    and the checker fails on a literal id anywhere it matters. Proven on the device: both
    builds installed side by side, then upgraded in place to 0.1.2.

- [x] J3: The update sheet draws the release notes instead of printing markup
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*ReleaseNotesTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: his ask was explicit about not rebuilding what already exists — *"use trawl
    renderer its beautiful"* — so the parser and the renderer are Trawl's, ported, with two
    swaps: its image loader for one that does not add a dependency, and its custom font for
    the default family. Headings, paragraphs, bold, italics, inline code, bullets, links,
    block quotes, tables and centred image strips all draw, checked against the live v0.1.2
    body on the phone rather than against a fixture.

- [x] J4: A bullet that wraps onto a second line stays one bullet
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*ReleaseNotesTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: a real bug in the ported parser, found by feeding it a real release body: a list
    item whose text wrapped in the source became two list items on screen. The parser now
    buffers the open item until something ends it. **This belongs back in Trawl**, which has
    the same bug.

- [x] J5: The changelog pane opens big and can be resized
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*SheetSizeTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: his report: the pane was small against how much content a release body holds.
    It opens at 58% of the screen and drags between 22% and 78%, and the size is
    remembered. The drag arithmetic is a pure function with a test because it contains a sign
    flip — dragging the handle UP must make the sheet BIGGER — and that is exactly the kind of
    thing that is wrong in one direction only.

- [x] J6: There is a notification when an update exists, and it can be answered
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*UpdatePromptTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: his ask, in full — remind me in N days, remind me next launch, and *"a dont remind
    me ever again option too"*. All three, plus skip-this-version, decided by pure functions
    over (what is available, what is installed, what was chosen, what time it is) rather than
    by state scattered through the UI. Two rules are worth naming: **"never remind me" stops
    the network check as well as the notification**, because a switch that keeps polling is a
    lie; and **postponing one release does not silence the next one**, because the thing being
    postponed is a version, not the feature.

- [x] J7: "Never remind me" is reversible from Settings, and the switch is not dead
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*UpdatePromptTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: rule R1, no dead switches. An off switch with no way back on is a trap, and it is
    the single easiest thing to ship without noticing, because the person who wrote it never
    turns it off.

- [x] J8: How a release body must be written is recorded in the repository
  CHECK: node tools/check-releasedoc.mjs
  EXPECT: RELEASEDOC OK
  EVIDENCE: `docs/RELEASES.md`, ported from Trawl's rule at his request and kept in the repo
    rather than in a prompt, so it outlives any one session and applies to the next app. Open
    with pictures pinned at the tag; say what changed and what it means; never talk about the
    code; say what it cannot do. The checker is blunt on purpose and says so — it can see
    whether there are images and sections and whether the prose has slipped into naming source
    files, and it cannot see whether the writing is any good.

- [x] J9: Anything that scrolls sideways looks like it does, before it is touched
  CHECK: node tools/check-scrollcue.mjs
  EXPECT: SCROLLCUE OK
  EVIDENCE: his report — *"i can barely tell it was scrollable until i dragged it"*. One shared
    component: a fade and a chevron at whichever edge has more behind it. Applied to the
    reminder row, the tab strip and the selection bar; a checker now fails a horizontal
    scroller that has no cue, with an exemption list where each entry carries a reason. **Not**
    applied to the breadcrumb — he looked at it and said no, and that refusal is recorded next
    to the rule so it does not get "fixed" later.

- [x] J10: Acting on a selection opens a context menu, and it fits
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*ContextMenuTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: his ask, with the reference — *"a familiarity of windows 11 context right click
    menu in the exporer"*. It opens at the finger, not anchored to a row: a row of five icons
    for copy, move, rename, share and delete, then labelled rows underneath. Blocked actions
    stay put and answer with the reason instead of disappearing, so the same verb is always
    under the same finger. The old scrolling bar is still there, in Settings, and it is not a
    dead switch. Both surfaces are built from **one** action list, which is what the tests
    pin — `QUICK_IDS` matches by string, so a rename leaves it pointing at nothing while still
    compiling and still drawing.

- [x] J11: Settings is grouped instead of being one long list
  EVIDENCE: his report: it was packed, with no visual distinction between the interface
    options, the general ones and the rest. General, appearance, browsing, safety, about.

- [x] J12: Shortcuts for folders, actions and scripts do what they say — third report
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*ShortcutRouteTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: **the most important entry in this round**, because it had been reported three
    times and "fixed" twice. Both earlier fixes corrected the routing function, which was never
    wrong. The actual cause: a shortcut launches the app cold, and the code that acts on the
    shortcut ran before the saved tabs had finished restoring, so there was no pane to route
    into and the app simply opened at home. It now waits for the restore to signal that it has
    **finished** — not for the tab list to look non-empty, which was the first fix and which
    acted on whichever tab happened to exist first. Diagnosed by instrumenting rather than
    theorising: a diagnostic message proved the shortcut's target arrived intact, and a
    screenshot proved the tabs had not.

- [x] J13: Home cards hide one at a time, and dead cards are explained or gone
  EVIDENCE: his correction — *"i meant certain card not the whole storage shortcuts"*. Each
    storage card has its own hide control, with a count of what is hidden and a way to bring
    them back. A card that could not report a size now says **"Size unknown — may not be
    readable"** rather than showing a zero, because a volume Android will not stat is a fact
    about the volume, not a bug to hide.

- [x] J14: Termux is detected, and its files are reachable — with the limit stated
  EVIDENCE: he asked whether it was even feasible. Partly. Termux exposes its **home**
    directory through the system document provider and nothing else, so Filet can offer a
    shortcut that opens the picker at that folder and then treats the grant as an ordinary
    volume. Files move in and out of Termux's home. `usr/bin` is **not reachable**, and that is
    Termux's decision rather than Filet's. The card appears only when Termux is installed, and
    it can be hidden, which moves it into Settings instead of deleting it.

- [x] J15: RAR opens
  CHECK: node tools/check-licences.mjs
  EXPECT: LICENCES OK
  EVIDENCE: his question — *"why are rar refused? is there any other you can do?"* — and the
    answer changed once the licence was read properly rather than assumed. Every RAR decoder
    published for the JVM descends from RARLAB's UnRAR source, whose licence forbids using it
    to build a RAR-compatible archiver; that is a field-of-use restriction and GPL-3 does not
    permit one to be added, so none of them can ship. **libarchive's RAR readers are
    independent work under a BSD licence** and carry no such clause. They are C, so Filet gains
    one native module — about 130 KB per architecture — built with only the two RAR readers
    enabled, no write support and no crypto backend. RAR and RAR5 including solid archives now
    open as folders and extract on the device. Creating a RAR is still refused, and that
    refusal is now a licence boundary that is stated rather than a gap that is implied.

- [x] J16: Paste is offered where you are standing
  EVIDENCE: his report, and the whole point of it: after copying something he expected a
    one-tap paste in the pane he was standing in, and the only one was a row inside the
    three-dot menu at the top right. After copy or move, a pill appears **in the pane**, saying how many items and
    where they will land. A floating pill rather than a full-width bar, which was his call.

- [x] J17: A long press extends a selection across the gap, like shift-clicking
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*RangeSelectTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: his ask, referencing Explorer. A phone has no shift key, so the gesture comes from
    the long press: nothing selected means open this item's menu; a selection running and you
    press outside it means extend to here; press something already selected and the menu acts
    on the whole selection — which is the case he chose when asked. Ranges are inclusive, work
    in both directions, and are measured against the **sorted, filtered rows on screen**, never
    the underlying order — extending across hidden rows would select files nobody pointed at.
    A range is added to the selection rather than replacing it, because on a phone the only way
    to build a selection is one gesture at a time.

- [x] J18: A new file shows up in the feed immediately
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*FeedTimingTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: reported twice — *"nothing appeared"*, then *"it showed up.. 2mins ago though"* —
    and his target was explicit: *"it needs to be blazing instant"*. Two of my own bugs.
    The feed published from a `finally`, which runs on cancellation too, so a superseded pass
    overwrote the good result with a partial one and the list went blank. And a 2.5s budget
    killed the listing of a 1700-file folder **invisibly**, because catching everything
    swallows a cancellation as well — a timed-out listing is indistinguishable from a
    successful read of an empty directory.

    What made it instant was not a faster scan. The watcher already knows the file **name**, so
    a change now costs one `stat` on one path spliced into the list, with the full re-read left
    behind it as the slow path. Each folder keeps its last good listing, results publish as
    they arrive rather than all at the end, and the per-folder budget is 12 seconds.

- [x] J19: The feed tracks files, and is named for what it holds
  CHECK: ./gw.sh --no-daemon :app:testGithubDebugUnitTest --tests "*TrackedFoldersTest*"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: his call, and it reverses round 7's G-series intent deliberately. Tracking folders
    as well as files meant copying one folder in produced an entry for the folder and an entry
    for everything under it. Files only; new files in a tracked subfolder still appear; the
    section is called **Files**. Only local and document-provider roots are seeded, because
    seeding a remote volume means mounting it at startup just to watch it.

**Still open from round 8:** a file landing in Termux's `usr/bin` from Filet (his to try), and
`inzip:` search inside a RAR — not verified and not claimed, because `adb` cannot type a colon
on this phone and three attempts to drive the scope chips landed on the wrong one. That is a
harness problem, not evidence, and it is recorded as unverified rather than assumed from the
fact that the code path is shared with the browser's.
