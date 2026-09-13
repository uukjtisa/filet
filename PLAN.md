# Filet — system design

> An open-source, feature-dense Android file manager for people who want to do
> real work on their phone without a PC. A file browser is the shell; the thesis is
> **your phone is a computer, stop renting it as an appliance.**

Opened 2026-09-10. Companion app to **Trawl**, by Niccc2007.
Licence **GPL-3.0** · package `dev.niccc2007.filet` (FROZEN — see §7).

---

## 0. Governing rules

These outrank every feature in this document.

### R1 — No Dead Switches

**If it does not work, it does not exist in the UI.** No greyed-out buttons, no
"coming soon", no settings toggle whose handler is a TODO, no menu entry that
opens an empty screen. A feature is either implemented and tested, or it is not
rendered at all.

Enforcement: every feature ships behind a flag that defaults **OFF**, and the flag
only flips when the feature has a test and has been driven by hand on a device.
`FEATURES.md` is the ledger; only `SHIPPED` rows are visible to a user.

Why: a half-working file manager loses trust on the first tap. The competitors
that died all died this way — impressive feature lists, three features that work.

### R2 — Working beats beautiful, and beautiful is not optional later

The aesthetic layer (Signet, see `TEMPLATE.md`) is a *dependency*, not a phase.
It gets built once, up front, and then never blocks a feature again. What R1
forbids is shipping the polish **around** a hollow feature.

### R3 — Everything above L0 talks only to the VFS

No `java.io.File` outside the local provider. Ever. This is the single rule that
separates this app from every other open-source Android file manager. MP-Manager
has `java.io.File` in 53 of its 102 own source files and therefore can never grow
SAF, root, SMB, or archive browsing without a rewrite.

### R4 — Every claim is checkable

Same discipline as the portfolio. No feature bullet in the README that isn't in
the app. No "supports X" where X is aspirational.

### R5 — The roadmap will derail. The layer contracts will not.

Milestones are expected to reorder, split, and slip. The layer boundaries in §3
are the part that has to hold, because they're what make reordering cheap.

---

## 1. Decisions locked

| Question | Decision | Consequence |
|---|---|---|
| Root support | **v2** (M8) | Build the provider seam in M0; no root implementation until an emulated rooted image exists for testing. No rooted hardware on hand. |
| Storage model | **Maximum versatility — all of them** | `LocalProvider` + `SafProvider` + `ManageStorageProvider` + (v2) `RootProvider`, all behind the VFS. Distribution flavour decides which are enabled. |
| Distribution | **GitHub Releases + F-Droid primary**, Play optional later | F-Droid/GitHub tolerate `MANAGE_EXTERNAL_STORAGE`. A Play flavour would degrade to SAF-only. |
| Baseline | **Greenfield.** Not a fork. | MP-Manager is mined for integration recipes only — see §6. |
| Licence | **GPL-3.0 + reserved name** | Forks must stay open and must not use the name "Filet" or the mark. |
| Language / UI | **Kotlin + Jetpack Compose**, minSdk 26 | Audience is power users, not KitKat. Dropping to 26 deletes an enormous amount of compat sludge and gives `ShortcutManager` unconditionally. |
| Script runtime v1 | **Lua (LuaJ)** | Pure Java, ~300 KB, no NDK, no ABI splits. Python is an optional downloadable module later (10–30 MB *per ABI* — it would quadruple the app). |
| App shell | **Signet** — a reusable template module | See `TEMPLATE.md`. Generalised from Trawl's updater/onboarding, reused by every future app. |

---

## 2. The name

**Filet.** Contains `FILE`. Means *to open something and cleanly separate its
parts* — which is what the app does to an APK, an archive, a device. And it is
the second stage of **Trawl**: you trawl for it, then you filet it. Two apps, one
naming system.

Known cost, accepted: `filet` / `fillet`, fi-LAY / FILL-it. Single-L spelling is
canonical because it is the one that carries the FILE pun.

---

## 3. The layers

```
L5  SURFACES     pinned shortcuts · widgets · share sheet · quick settings
L4  RUNTIME      Lua · (Python, later) · recipes · triggers
L3  HANDLERS     text/code · hex · image · media · APK · dex/smali · archive
L2  ROUTING      type registry — what opens what, internally and externally
L1  INDEX        FTS5 · provenance · stable file IDs · crawler · watchers
L0  VFS          local · SAF · manage-storage · archive · APK · root · network
```

### L0 — VFS (the provider core)

Modelled on Java NIO2's `FileSystemProvider` (the design Material Files uses;
read it, don't fork it — it is GPLv3 and so are we, but the value is the shape).

Each backend is a provider implementing one interface. The rest of the app never
knows which one it is talking to.

| Provider | Milestone | Notes |
|---|---|---|
| `LocalProvider` | M0 | Direct NIO on paths the app can touch |
| `SafProvider` | M1 | `DocumentsContract` — SD cards, `Android/data`, Play-safe path |
| `ManageStorageProvider` | M1 | When `MANAGE_EXTERNAL_STORAGE` is granted |
| `ArchiveProvider` | M1 read / M2 write | zip/apk/tar/7z mounted **as directories** |
| `ApkProvider` | M6 | An APK browsed as a tree; `classes.dex` opens as a tree of smali |
| `RootProvider` | M8 | libsu (topjohnwu), never `Runtime.exec("su")` |
| `NetProvider` | M8 | SMB (smbj), SFTP (sshj), FTP, WebDAV |

**The APK-as-a-filesystem provider is the whole product feel.** You browse *into*
an APK like a folder, tap a dex, and it opens as smali. That is a provider, not a
feature — which is why the VFS has to exist before any of it.

### L1 — Index

> **Full design: `SEARCH.md`.** What follows is the summary; that document is authoritative.

One SQLite database, three consumers. This consolidation is what makes the design
hold together.

- **Search** — FTS5 over names and paths, incremental crawler keyed on directory
  mtime. `MediaStore` is not sufficient and never will be.
- **Stable file IDs** — every known file gets an ID. IDs survive move and rename;
  paths do not. Consumed by L5 (see the shortcut rule).
- **Provenance** — where a file came from. See §5.

Watcher reality: `FileObserver`/inotify costs one watch per directory and there is
a per-process ceiling (commonly ~8192). **You cannot watch a whole filesystem.**
Strategy: watch shallow and hot (recents, Downloads, current panes), re-crawl deep
on a schedule, cache aggressively.

### L2 — Routing

Two tables that are constantly confused. They are not the same thing.

| | Direction | Mechanism |
|---|---|---|
| **Inbound** | other apps to Filet | AndroidManifest intent filters |
| **Internal** | inside Filet, tap to open | Filet's own **handler registry** |

The registry maps `mime/extension` to a handler, where a handler is either an
**internal viewer** (L3) or an **external Android intent**.

- **single tap** — the registered handler
- **double tap** — system "Open with" chooser
- **long press** — full action sheet

User-editable per type. `.mp3` to an external player. `.smali` to the internal
editor. `.apk` to the internal inspector. This registry is also what a pinned
shortcut's `handlerOverride` resolves against — the per-type default is a
*routing* concern, and shortcuts merely inherit it.

Inbound must cast wide: `*/*` plus specific mimes, `VIEW` / `SEND` /
`SEND_MULTIPLE` / `OPEN_DOCUMENT`, and `http`/`https` so links can be handed over
to Trawl. Being *in* the Open-With list is manifest work. Being *chosen* is the
user's call — you cannot force default status and must not try.

### L3 — Handlers

Text/code (sora-editor), hex, image (+EXIF), media player, archive, APK inspector,
dex/smali. Each registers the types it can open. Adding a viewer must never
require touching the browser.

### L4 — Runtime

Lua via **LuaJ** in v1. Python as an optional downloadable module later, behind
the same script API.

**Scripts bind to the VFS, not to `java.io.File`.** Do that and every script works
over SMB, inside archives, and through root for free. Bind them to raw File and
we have rebuilt MP-Manager.

> **SECURITY — the most attackable surface in the app.**
> A script runner reachable by intent is remote code execution. An external intent
> may *offer* a script; only an explicit, unambiguous user confirmation may **run**
> one. Scripts declare a permission set (which paths, network yes/no) and it is
> enforced at the VFS boundary, not by convention. This is the first thing a
> security researcher will poke and the first thing that would get the app pulled.

### L5 — Surfaces

`ShortcutManager.requestPinShortcut()` (API 26+) gives Windows-style pinned
shortcuts: thumbnail icon, custom label, renameable via `updateShortcuts()`.

> **THE SHORTCUT RULE — the real problem hiding in "no stale shortcuts":**
> **A shortcut never stores a path. It stores a Filet file ID, and Filet resolves
> ID to the current path at launch.**

Paths break on every move and rename. L1 assigns the ID and maintains the mapping
as the crawler and watchers observe changes. Move the file, the shortcut follows.
Delete it, Filet calls `disableShortcuts()` with a real message instead of leaving
a dead tile on the home screen.

Shortcut payload: `{ fileId, label, iconRef, handlerOverride? }`.

Also here: widgets (script launcher, folder view), share-sheet targets, quick
settings tiles.

---

## 4. Distribution flavours

| Flavour | Storage | Updater | Notes |
|---|---|---|---|
| `github` | MANAGE_EXTERNAL_STORAGE + SAF | **on** (Signet updater) | Primary |
| `fdroid` | MANAGE_EXTERNAL_STORAGE + SAF | **off** | F-Droid handles updates and objects to self-updaters |
| `play` (later, optional) | SAF only | off | Only if Play review is ever worth the fight |

The updater is a build-flavour concern, not a runtime setting.
`REQUEST_INSTALL_PACKAGES` must not even appear in the F-Droid manifest.

---

## 5. The Trawl bridge

The wrong framing is "Trawl notifies Filet." The right one:

> **Trawl acquires. Filet operates. The contract between them is a file's biography.**

### 5.1 Provenance — and it is *searchable*

Android has no equivalent of Windows' mark-of-the-web or macOS'
`kMDItemWhereFroms`. A file on your phone has no memory of where it came from.
Nothing fills that gap.

Trawl already knows all of it — source URL, page title, uploader, chosen format,
the yt-dlp info JSON, timestamp — and currently discards it. Instead it writes a
**biography record** that Filet stores in L1 and indexes.

What that unlocks:

- Every file shows a **Source** chip: open the original page, copy the URL, check
  whether it is still live, or **re-download at a different quality** (hands
  straight back to Trawl).
- **Search by origin, not filename.** *"that video I pulled last month"*,
  *"everything from this channel"*, *"files from the link someone sent me in March."*
  Nothing on Android does this. It is the single most differentiating feature here.
- Duplicate detection that is actually smart — same source, different filename.

Generalises: publish the contract and **any** app can write provenance. Trawl is
simply the first writer.

### 5.2 Shared job ledger

Both apps run long background work — Trawl downloads; Filet decompiles, hashes,
zips, indexes, searches. Today that is two notification stacks and no history.

One ledger both publish to is the "monitoring sidebar": one surface answering
*what is this phone doing, and what did it do while I was not looking.*

### 5.3 Recipes — Trawl completion is just a trigger

The elevated version of "auto-track the Trawl folder". L4 listens for events;
"Trawl finished a download" is one of them.

> download a video, extract the audio, tag it, move it to Music, pin a shortcut

Written once as a Lua recipe. Because it is an event system rather than a
hardcoded link, Trawl is only *the first source* — a camera capture, a share-sheet
drop, or a watched folder later trigger the same recipes.

### 5.4 Mechanism

Each app exposes a `ContentProvider` guarded by an
`android:protectionLevel="signature"` permission. Both apps are signed with the
same key, so only Nic's apps can read or write the bridge — no third app can spoof
provenance or inject a job. No server, no account, works offline.

> **Debug builds are a different package.** `applicationIdSuffix ".debug"` means
> `dev.niccc2007.filet.debug` cannot see the release Trawl, and vice versa.
> Bridge discovery must enumerate both the release and `.debug` package names and
> verify the signing certificate, or every development session is bridge-blind.
> *(Assumption: this is what "support debug one too" meant. Confirm.)*

### 5.5 Work required in Trawl

Small but real, and it is a PR against his own fork:

1. Write a biography record on download completion.
2. Publish jobs to the shared ledger.
3. Accept a "re-download this at quality X" intent from Filet.

---

## 6. What to mine from MP-Manager (and not inherit)

`github.com/AbdurazaaqMohammed/MP-Manager` — GPL-3.0, so lifting a helper class
outright is legal. **Attribute in the file header and in the README.**

Measured 2026-09-10: 400,610 lines total, but 93% is vendored upstream pasted into
the source tree. The author's own code is 26,741 lines across 102 files, with
`java.io.File` in 53 of them, zero `DocumentFile`, zero ViewModel/LiveData/Compose/
coroutines, `MainActivity.java` at 3,174 lines, minSdk 19.

Mine the integration recipes — weeks of fiddly work, and the only reason to open
the repo:

| File | What it teaches |
|---|---|
| `SignWrapper.java` (431) + `SignatureKeyDialog.java` (403) | driving apksig with JKS *and* pk8/pem |
| `ApkToolsHandler.java` (1,178) | invoking ARSCLib/APKEditor on-device |
| `ApkManifestEditor.java` (543) | AXML round-trip |
| `UnifiedEditorFragment.java` (1,478) | wiring sora-editor to smali |
| `RootManager.java` (927) | their root approach (we use libsu instead) |

Take the knowledge. Take none of the architecture.

### Dependencies — declare them, do not paste them

| Library | Licence | For |
|---|---|---|
| ARSCLib (REAndroid) | Apache-2.0 | resources.arsc + AXML, **no aapt2 needed** |
| APKEditor (REAndroid) | Apache-2.0 | decompile/build — loads all dex at once and **OOMs on big apps**; stream instead |
| `com.android.tools.smali:smali-dexlib2` | Apache-2.0 | dex to smali |
| `com.android.tools.build:apksig` | Apache-2.0 | v1/v2/v3/v4 signing |
| sora-editor (Rosemoe) | **LGPL-2.1** | code editor, TextMate + tree-sitter |
| LuaJ | BSD-ish | Lua runtime |
| libsu (topjohnwu) | Apache-2.0 | root (M8) |
| smbj / sshj / commons-net / sardine | Apache-ish | network providers (M8) |

Confirm exact Maven coordinates when wiring. **Do not vendor source.**

---

## 7. Frozen decisions

- **`applicationId` = `dev.niccc2007.filet`.** Changing it after the first
  F-Droid or Play release forces every user to uninstall and lose their data.
  Same lesson as the Vercel subdomain. The *display name* can change; this cannot.
- **The icon.** Candidate **E4** — two-tone open folder — chosen 2026-09-13. Vector sources and
  placement in `icon/`. Like the display name it *can* change, but a launcher icon is how people
  find the app on a crowded home screen, so treat it as frozen after the first release.
- **The signing key.** The Trawl bridge depends on both apps sharing it. Losing it
  means no user can ever update. Back it up somewhere that is not this machine.

---

## 8. Roadmap

Expected to reorder and slip (R5). Milestones have **exit criteria**, not dates.

| M | Scope | Exit criterion |
|---|---|---|
| **M0** | Signet shell + theme · VFS interface + `LocalProvider` · single pane · file ops | Browse, copy, move, delete, rename on internal storage without crashing |
| **M1** | `SafProvider` · `ManageStorageProvider` · dual pane · drag & drop · sort/filter · bookmarks · archive read · live search | **You use it daily instead of your current file manager** |
| **M2** | Handler registry · sora editor · image/media/hex viewers · inbound intents · share sheet | Single/double tap routing works; other apps can open files in Filet |
| **M3** | FTS5 index · crawler · watchers · **stable file IDs** · provenance schema | Search a 100k-file device in under a second |
| **M4** | Pinned shortcuts with ID resolution · rename · auto-repair on move · disable on delete · `handlerOverride` · widgets | Move a shortcut's target file; the shortcut still works |
| **M5** | LuaJ bound to the VFS · script permission model · script manager · script shortcuts | A script can process a file on an SMB share it has never seen |
| **M6** | APK inspector · ARSCLib manifest · apksig signing · dex to smali browse/edit/rebuild · `ApkProvider` | Decompile, edit one smali line, rebuild, re-sign, install — on device |
| **M7** | Trawl bridge — ContentProvider + signature perms · provenance write in Trawl · Source chip · search-by-origin · job ledger | Find a file by where it came from |
| **M8** | libsu root provider · SMB/SFTP/WebDAV · emulated rooted image for testing | Root browse works on an emulator image |
| **M9** | **Nearby** — shared set, HTTP+TLS server, mDNS, pairing, peer-as-a-pane (`NEARBY.md`) | Drag a file from another phone's pane into yours |
| **M10** | Wear OS companion — approve transfers, activity glance, recipe tile | A transfer is accepted from the watch |

M6 depends on nothing above it and can jump the queue if motivation says so.
M9 shares its
remote-provider seam with M8, so those two are built together.
M4 **cannot** precede M3 — shortcuts need stable IDs.

### Status — 2026-09-13

Measured on a HUAWEI NCO-LX1 (API 31). Evidence per milestone is in `GATES.md`; the numbers
below are counts from the test XML, not a summary of intent.

| M | State | The short version |
|---|---|---|
| M0 | ✅ met | Shell, VFS, one pane, six file ops |
| M1 | ✅ met, one clause is Nic's | Everything the criterion rests on is tested. "Used daily instead of your current file manager" is a verdict only he can give |
| M2 | ✅ met | Routing is registry-driven; the platform's own PackageManager confirms the inbound filters |
| M3 | ✅ met, **measured** | 100,000 real files, slowest warm query 497 ms against a 1,000 ms gate |
| M4 | ✅ met | Required building move-tracking: a pinned id used to die with the generation sweep |
| M5 | ✅ met | One unmodified script over a zip and over a real WebDAV share |
| M6 | ✅ met | Decompile → one smali line → rebuild → sign → install → the edited code runs |
| M7 | ✅ met from Filet's side | Cross-package provenance through the signature permission; Trawl's own write is its gate |
| M8 | ⛔ **root abandoned**, network met | No rooted image or hardware here, and an emulator's `su` refuses app uids by design |
| M9 | ✅ browser half met | An external client with no Filet downloads a shared file. Phone-to-phone drag needs a second phone |
| M10 | — | Out of scope for this run |

**Three features were found broken while closing these gates and are now fixed:** a shortcut's
stable id did not survive a move (M4), every rebuilt APK emitted DEX 041 and could not run
below API 35 (M6), and the WebDAV client had never worked because Android's
`HttpURLConnection` rejects `PROPFIND` (M8b). None of them would have been visible without a
gate that had to be demonstrated rather than asserted — which is what R4 is for.

---

## 9. Open questions

- [x] Confirm the `.debug` bridge interpretation in §5.4. — **Confirmed on device.** The
      instrumentation APK is a separate package signed with the same key, and it reaches the
      provider through the signature permission exactly as Trawl's debug build would.
- [ ] Rooted emulator image: AVD + Magisk, or a prebuilt rooted system image? Needed before M8.
      **Now known to be load-bearing, not optional:** a stock `google_apis` image cannot be
      used even with `adb root`, because its `su` refuses any caller that is not uid shell or
      root. Magisk-patched image or rooted hardware.
- [ ] Does Trawl's fork take the provenance PR cleanly, or does it need rebasing on upstream Seal first?
- [ ] Signet as a Gradle `includeBuild`, a Maven artifact, or a git submodule? (`TEMPLATE.md` §5)
