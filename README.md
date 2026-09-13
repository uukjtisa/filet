# Filet

**An Android file manager for doing real work on your phone, without reaching for a PC.**

Open source, no ads, no accounts, no telemetry. GPL-3.0.

Filet browses local storage, SD cards, SAF grants, network shares and root — and it browses
*into* archives and APKs the same way it browses a folder. It searches a whole device in
milliseconds, runs sandboxed Lua over your files, and hands a file to a laptop over Wi-Fi
with nothing installed on the laptop.

> Companion to [Trawl](https://github.com/uukjtisa/trawl), by **Niccc2007**
> ([@uukjtisa](https://github.com/uukjtisa)). Early development — `0.1.0`.

---

## Screenshots

| | | |
|:--:|:--:|:--:|
| ![Browsing](docs/screenshots/01-browse.png) | ![Split view](docs/screenshots/02-split.png) | ![Search](docs/screenshots/03-search.png) |
| **Browsing** — six view densities, thumbnails for images, video and APKs | **Two panes** — labelled A and B, drag a file from one to the other | **Search** — scope chips, a query language, whole-device results |
| ![Inside an APK](docs/screenshots/04-apk.png) | ![Lua scripts](docs/screenshots/05-scripts.png) | ![Nearby](docs/screenshots/06-nearby.png) |
| **An APK, inspected** — signature, SDK levels, every dex openable as smali | **Lua** — each script states what it may touch before it runs | **Nearby** — a QR, a PIN you can set, and every address it can be reached on |

![The web UI](docs/screenshots/07-web.png)

**And a browser on the same network sees this** — no app, no account, no cable. Folders open,
whole folders download as a streamed zip, thumbnails are generated on the phone, and the page
wears whatever theme the app is wearing.

*Every screenshot uses a seeded demo folder, not real files.*

---

## What it does

**Browse anything as a folder.** Local storage, SD cards, SAF grants, WebDAV — and zip, tar
and APK archives, addressed as `zip:///path/a.zip!/inner`. Inside an APK, `classes.dex` opens
as a browsable tree of smali. Everything above the storage layer speaks one interface, so a
new backend is a new file, not a new special case.

**Search that answers before you finish typing.** SQL narrows 200k rows to 500, then an
fzf-class scorer reranks them in memory: subsequence matching, word-boundary and camelCase
bonuses, Damerau-Levenshtein typo tolerance, and frecency. `dwnld` finds Downloads; `AM`
finds AndroidManifest.xml. There is a real query language — `type: size: modified: in: from:
pkg: class: perm: inzip: dup:` with AND/OR/NOT and grouping — and invalid syntax degrades to
a substring search instead of erroring at you.

**Search *inside* APKs.** `pkg:`, `label:` and `perm:` come from the resource table;
`class:` from deduplicated package prefixes in the dex. Roughly 2 KB of facts per APK, not
2 MB. As far as I know nothing else on Android does this.

**An APK toolchain that runs on the phone.** Inspect signatures and protection status, edit
the binary manifest, disassemble dex to smali, edit it, rebuild and re-sign. The rebuild
reads minSdk from the APK being rebuilt, so the dex format it emits is one the target device
can actually load.

**Sandboxed Lua.** Scripts get a `fs` table scoped to the VFS and a permission header they
must declare up front; there is no `io`, no `os.execute`, no network. Seven worked examples
ship with the app, and there is a copyable prompt that explains the whole API to an AI agent
so you can append what you want in plain words.

**Nearby sharing that treats a peer as a volume, not a target.** Start a share and any
browser on the network can open it — list, preview, download a file or a whole folder as a
streamed zip, and upload back. Paths never enter a URL: a URL is `/f/<token>`, so traversal
is unrepresentable rather than merely blocked. PIN on by default, every granted session is
listed live and revocable, and nothing expires unless you ask it to.

**Home-screen shortcuts that survive a move.** A shortcut stores a file's index id, not its
path — move the file and the shortcut follows it. Delete it and the shortcut says so instead
of doing nothing.

Full ledger: [`FEATURES.md`](FEATURES.md) — 69 shipped, with per-feature notes.

---

## The rules it is built to

Four rules outrank every feature, and they are the reason the app is shaped the way it is.

**R1 — No dead switches.** If it does not work, it does not exist in the UI. No "coming
soon", no toggle whose handler is a TODO, no menu entry that opens an empty screen. Where a
control genuinely cannot work right now it is greyed **and says why when you tap it** —
a phone has no hover, so a plain disabled button is a dead end.

**R2 — Working beats beautiful, and beautiful is not optional later.** The visual layer is
built once, up front, and then never blocks a feature.

**R3 — Everything above the storage layer talks only to the VFS.** No `java.io.File` outside
the local provider, enforced by `tools/check-r3.mjs` on every push. The allow-list is 13 files
long and every entry has a written reason. This is the single rule that separates Filet from most
open-source Android file managers: it is what makes archives, network shares and root all
work through one code path instead of three.

**R4 — Every claim is checkable.** Each milestone has gates with a command and an expected
output ([`GATES.md`](GATES.md)), and each bug found in review is tracked to a demonstrated
outcome ([`FIXES.md`](FIXES.md)) — 46 of 46 met, including 66 on-device tests.

### Layers

```
L5  Surfaces     Compose UI, panes, tabs, split
L4  Runtime      Lua scripts, jobs, the activity ledger
L3  Handlers     viewers, editors, the APK toolchain
L2  Routing      what opens what, inbound and internal
L1  Index        SQLite FTS5 + trigram, retrieve-then-rerank
L0  VFS          FileSystemProvider per backend; VPath, VNode
```

---

## Building it

Needs the Android SDK and a JDK 21. Android Studio's bundled JBR is the one that is known to
work; `gw.sh` points `JAVA_HOME` at it.

```bash
git clone https://github.com/uukjtisa/filet
cd filet
./gw.sh :app:assembleGithubDebug
adb install -r -g app/build/outputs/apk/github/debug/app-github-debug.apk
```

Two flavours: `github` (with the in-app updater) and `fdroid` (without — F-Droid forbids
self-updaters). Debug builds get a `.debug` application id so they sit beside a release one.

`minSdk 26`, `targetSdk 37`, Kotlin 2.2, Compose Material3.

### Running the checks

```bash
./gw.sh :core-vfs:test :core-index:test :app:testGithubDebugUnitTest   # JVM suites
node tools/check-r3.mjs                                                # R3: no storage API above L0
./tools/run-gates.sh <adb-serial>                                      # 66 on-device gates
```

`run-gates.sh` exists because `connectedAndroidTest` reinstalls the app between runs, which
resets the all-files appop and deletes the artifacts the gates just produced — both of which
look like feature failures and are neither. It installs once, grants, runs the
instrumentation directly, and collects what the tests wrote.

`tools/testservers/webdav.py` is a minimal WebDAV server for exercising the network provider
from a device over `adb reverse`.

CI runs everything that does not need a phone: R3, the house-rule checks, the JVM suites and
both flavours. The on-device suite is deliberately left out of it — it needs real storage, a
real launcher and a real package installer, and an emulator on a runner would turn a
meaningful pass into a decorative one. `GATES.md` records where that suite was measured and on
what hardware.

---

## Permissions, and why

| Permission | Why |
|---|---|
| All-files access | To browse the shared volume. Filet works without it — only folders you pick by hand are visible, which is narrower, not broken. |
| Foreground service (data sync) | Two uses, both user-initiated and both visible with a Stop: a full index you asked for, and an active Nearby share. |
| Internet / local network | Nearby sharing and network storage. No analytics, no crash reporting, no outbound connection Filet makes on its own. |
| Install packages | Only when you tap Install on an APK. |

Crash reports are written to app-private storage and shown to you. They are never uploaded
anywhere — a file manager's stack traces carry your paths.

---

## Licence

GPL-3.0. See [`LICENSE`](LICENSE).
