<div align="center">

<img src="docs/glimpse.png" width="100%" alt="Filet, an Android file manager: convenience, power, versatility and sharing, each with the thing that makes it true. Beside them three phone screens fanned out - a folder listing, the home overview with internal storage and another phone mounted as a drive, and a Markdown page with a photo and a table">

[![Licence](https://img.shields.io/badge/Licence-GPL--3.0-4E7382?style=for-the-badge)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-4E7382?style=for-the-badge&logo=android&logoColor=white)](#building-it-yourself)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-4E7382?style=for-the-badge&logo=kotlin&logoColor=white)](#building-it-yourself)
[![Release](https://img.shields.io/github/v/release/uukjtisa/filet?style=for-the-badge&color=4E7382&label=release)](https://github.com/uukjtisa/filet/releases/latest)
[![Status](https://img.shields.io/badge/early%20development-C25E3A?style=for-the-badge)](docs/FIXES.md)
[![Support](https://img.shields.io/badge/Support-Ko--fi-C25E3A?style=for-the-badge&logo=kofi&logoColor=white)](https://ko-fi.com/thethirdy2007)

### PC-grade tools and native viewers, in a file manager that looks the part.

Built by **[Niccc2007](https://github.com/uukjtisa)**. Free and open source, always.

</div>

---

<div align="center">

## Support Filet

**Filet is independent.** It is built by one student, in the hours around studying, and every
line of it is shared.<br>
There is no company behind it and no paid tier coming - nothing is locked and nothing ever will be.

**Support goes straight to me**, and it buys the time and the test phones that keep Filet moving.

<a href="https://ko-fi.com/thethirdy2007"><img src="https://img.shields.io/badge/Support%20Filet%20on%20Ko--fi-C25E3A?style=for-the-badge&logo=kofi&logoColor=white" height="44" alt="Support Filet on Ko-fi"></a>

<sub>If it is not for you, the app is exactly the same. It never asks: the only mention of this
inside it is one row on the About screen.</sub>

</div>

---

> [!WARNING]
> **Early development.** I built this for my own phone and I'm still finding things wrong with
> it. There's no store listing - [Releases](https://github.com/uukjtisa/filet/releases) is the
> only place it comes from. Everything below is what the code does today, except the section
> that says it is a plan.

---

## Table of Contents

- [What Filet is](#what-filet-is)
- [A glimpse of it](#a-glimpse-of-it)
- [The tools](#the-tools)
- [The viewers](#the-viewers)
- [Finding things](#finding-things)
- [Your phone and your network](#your-phone-and-your-network)
- [Where it's going](#where-its-going)
- [Installing it](#installing-it)
- [Building it yourself](#building-it-yourself)
- [Permissions](#permissions)
- [Credits](#credits)
- [Licence](#licence)

---

## What Filet is

A general file manager with the tools you would normally go to a PC for built in, and its own
viewers for the files you open every day - so a document, an archive or an app package opens
*in* Filet instead of bouncing you to three other apps. Four things it won't trade against each
other:

- **Productivity** - search the whole phone in milliseconds, work in two panes, and keep your
  usual places a tap away.
- **Power** - edit a file inside an archive, take an app apart and rebuild it, change raw bytes,
  and script the lot in Lua.
- **Aesthetics** - a real palette and real motion, holding up on phone, tablet and landscape.
- **Convenience** - read Markdown, Word, slides, PDFs and spreadsheets where they lie, and share
  to any browser on your network with nothing installed at the other end.

Free and open source. No ads, no accounts, no telemetry, no paid tier.

---

## A glimpse of it

<p align="center">
  <img src="docs/screenshots/16-apk.png" width="23%" alt="The APK inspector on Filet's own package: SDK levels and ABIs, 17 permissions with 2 flagged dangerous, 26 dex files and 48,083 classes, the signer, and actions to decompile, sign and edit the manifest">
  <img src="docs/screenshots/12-hex.png" width="23%" alt="The hex viewer on a firmware file, offsets, bytes and the text they spell side by side">
  <img src="docs/screenshots/06-extract.png" width="23%" alt="An extraction preview: the folder it will create drawn into the listing as a ghost row marked new, one of two doubled parent folders lifted, and seven files, 353 KB, mostly media that will not shrink">
  <img src="docs/screenshots/11-image-editor.png" width="23%" alt="The image editor on a landscape photo, with crop, draw, text, resize, turn, mirror, flip and invert along the bottom">
</p>

<p align="center"><sub>
The <b>APK inspector</b> reads a package before anything is installed - permissions, dex, signer -
and decompiles, signs or edits its manifest &middot; <b>hex</b>, where a changed byte is staged
until you save &middot; an <b>extraction</b> previewed <i>before</i> it happens, the folder it
will make already standing in the list and a doubled parent lifted one level at a time &middot;
and an <b>image editor</b> that crops, draws, writes, resizes, turns and flips
</sub></p>

<p align="center">
  <img src="docs/screenshots/14-markdown.png" width="23%" alt="A Markdown file read as a page: headings, a photo, a table with right-aligned numbers, a checklist and a quote">
  <img src="docs/screenshots/15-sheet.png" width="23%" alt="A spreadsheet in the built-in reader, with sheet tabs, column letters and row numbers, and the header row and first column frozen">
  <img src="docs/screenshots/17-slides.png" width="23%" alt="A slide deck read as a column of cards, one per slide, with its title and bullets">
  <img src="docs/screenshots/18-pdf.png" width="23%" alt="A PDF rendered page by page, with a page counter">
</p>

<p align="center"><sub>
Readers built in rather than handed off: <b>Markdown</b> as a page, pictures and tables
included &middot; <b>spreadsheets</b> with their tabs and frozen headers &middot; <b>slides</b>
&middot; and <b>PDF</b>. Word documents open in the same reader
</sub></p>

<p align="center">
  <img src="docs/screenshots/callouts/01-home.png" width="23%" alt="The home screen, with internal storage and another phone mounted over the network side by side as tiles, the network one ringed and labelled, then categories and the newest files">
  <img src="docs/screenshots/19-search.png" width="23%" alt="A search inside one folder, with scope chips from this folder out to every drive and filters for type, extension, size, date, name, package, class and permission">
  <img src="docs/screenshots/04-view.png" width="23%" alt="The view menu over a folder of photos: a slider from compact list to large grid, sort by name, date, size or type, folders sorted separately, hidden files and tree view">
  <img src="docs/screenshots/09-remotes.png" width="23%" alt="Remotes: a phone found on the network, two saved WebDAV shares, and this phone hosted as a drive with its Windows path and address, the access codes blacked out">
</p>

<p align="center"><sub>
<b>Another phone on the network sits beside internal storage</b> as a tile like any other, the
bar under each coloured by how full it is &middot; <b>search</b> that reaches from the folder you
are in out to every drive, with filters for size, date, package, class and permission &middot;
one <b>view</b> menu for density, sort and the tree &middot; and <b>Remotes</b>, which both mounts
other machines and hosts this phone as a drive on yours
</sub></p>

<p align="center"><sub>
Screenshots use a seeded demo folder, not real files. The lists that are nothing <i>but</i>
personal data &mdash; recents, bookmarks, pinned shortcuts &mdash; are left out rather than
staged, and the share access codes are redacted. The banner at the top and the ringed copy above
are both built from these same files by <code>tools/make-glimpse.py</code>; the files themselves
are never annotated.
</sub></p>

---

## The tools

The things a phone file manager usually sends you to a PC for.

- **Archives** - open zip, 7z, RAR and tar like folders, see an extraction before it happens,
  edit a file inside one and save it back, and make zip (with AES-256) or 7z. RAR opens, but
  Filet cannot create one, and a 7z cannot be given a password here.
  Use zip if the archive needs a password. Both are licence limits, measured in
  [`core-native/`](core-native/README.md)
- **App packages** - inspect one before installing it, read and edit its smali and manifest,
  then rebuild, re-sign and install it on the phone
- **Hex and pictures** - a hex editor whose edits are staged until you save, and an image editor
  that crops, draws, rotates and adds text
- **Tags** - music tags and cover art, and PDF and Office properties, read and written without
  touching the original until the new copy checks out
- **Lua scripts** that can only touch what they declared, approved by you before they run

---

## The viewers

Filet opens a file rather than handing it away, and every viewer works the same inside an archive
or on a network share.

- **Documents** - Markdown, Word, PowerPoint, PDF and spreadsheets, with pinch zoom. A link to
  another file opens it, and Back returns you to the exact spot
- **Code and text**, highlighted, in a real editor
- **Pictures, video and music**, swiped through together, with playlists from your own files
- **A QR scanner** that shows what a code really is - a payment code shows who it pays

---

## Finding things

- **Whole-phone search in milliseconds**, fuzzy and typo-tolerant, ranked by what you actually
  open. `dwnld` finds Downloads
- **A query language** - `type: size: modified: in: pkg: perm: inzip: dup:` with AND, OR and NOT.
  Bad syntax falls back to a plain search instead of an error
- **Every hit is checked against the disk before it is shown**, so a stale index never shows you
  a stale result
- **New files** - not when a file was made, but when it turned up on your phone
- **Photos, videos and music from the whole phone**, grouped by date, latest first
- **Shortcuts that survive a move.** A pinned shortcut follows its file when the file moves, and
  says so when it is deleted

---

## Your phone and your network

- **Share to any browser** on your network, with nothing installed on the other machine. A PIN
  by default, a QR code to open it, every session listed and revocable, and a 4 GB video
  streamed without a copy
- **Host this phone as a drive** on your PC
- **Mount other machines** over WebDAV, SMB, SFTP and FTP, as a storage tile beside internal
  storage. WebDAV is proven against a real server; SMB, SFTP and FTP are written and are the
  next thing to be proven against real ones - see below

---

## Where it's going

The big pieces still to come. The full plan, with the test each one has to pass, is
[`docs/PLAN.md`](docs/PLAN.md).

- **Network drives that just work** - find a PC by its name instead of its IP address, play a
  film off a share without downloading it, and have a big copy survive the Wi-Fi dropping. The
  phone itself showing up in Windows' Network folder
- **Cloud drives** - OneDrive or Dropbox first, then Google Drive, browsed, searched and copied
  exactly like a local folder
- **APK reverse engineering, taken much further** - a code editor with jump-to-definition and a
  symbol pane you drive without the keyboard, and split packages (XAPK, APKM, APKS) merged into
  one installable APK
- **An automation server** - an agent on your network driving Filet end to end: files, Lua, and
  a smali-level change to an app, rebuilt and installed without a hand on the phone
- **More viewers**, and better ones

---

## Installing it

Get the APK from **[Releases](https://github.com/uukjtisa/filet/releases/latest)**. Android 8.0
or newer, one file for every phone. Android asks you to allow installs from this source, and Play
Protect may warn about it - [here is why](#why-play-protect-warns-about-filet). After that,
**About > Check for updates** asks GitHub for new versions.

---

## Building it yourself

You'll need the Android SDK and a JDK 21. Android Studio's bundled JBR is the one that's known
to work, and `gw.sh` points `JAVA_HOME` at it.

```bash
git clone https://github.com/uukjtisa/filet
cd filet
./gw.sh :app:assembleGithubDebug
adb install -r -g app/build/outputs/apk/github/debug/app-github-debug.apk
```

Two flavours:

- **`github`** has the in-app updater
- **`fdroid`** compiles it out, because F-Droid forbids an app that updates itself

Debug builds get a `.debug` application ID so they sit beside a release one. `minSdk 26`,
`targetSdk 37`, Kotlin 2.2, Compose Material3.

### Running the checks

```bash
./gw.sh :core-vfs:test :core-index:test :app:testGithubDebugUnitTest   # JVM suites
node tools/check-all.mjs                                               # every source checker
./tools/run-gates.sh <adb-serial>                                      # on-device tests
```

---

## Permissions

Every permission the APK carries, including the ones Android's own libraries add, and what uses
it.

| Permission | What uses it |
|---|---|
| All-files access (and the older storage permissions on Android 12 and below) | Browsing. Filet works without it, but then only folders you pick by hand are visible |
| Notifications | Music controls, the progress of a job you started, and the update notice |
| Camera | The QR scanner, and nothing else. Asked for on its first open, and optional |
| Internet, network state and Wi-Fi state | Network drives, sharing, Remotes, the update check, and Report a problem when you send one |
| Wi-Fi multicast | Finding other phones and servers on your Wi-Fi |
| Install packages | Only when you tap Install on an APK, or install an update. The system installer still asks |
| See all installed apps | The Apps tab, saving an installed app's APK, and telling you whether an APK is already installed |
| Foreground service: data sync | A full index you started, and a share while it is hosted. Both show a notification with Stop |
| Foreground service: media playback | Music that keeps playing with the app closed, and the lock-screen and earphone buttons |
| Wake lock, run at start-up | Added by Android's WorkManager library for the index tidy-up below, which it re-schedules after a restart. Nothing else starts at boot |
| Filet bridge | Filet's own. Only apps signed with the same key can hold it |
| Dynamic receiver | Added by AndroidX. Keeps Filet's internal broadcasts private to Filet |

### What runs without you asking

- **Opening the app** - one request to GitHub for a newer version, and at most twice a day while
  it stays open. Settings > Maintenance > Update notifications turns it off, and the F-Droid
  build has no update check at all
- **Every twelve hours, only while charging** with the battery not low - a sweep that keeps
  search current. Network shares you opted into get the same, on Wi-Fi only
- **When photos or other media change** - Android wakes Filet briefly so New files and search
  pick them up

Nothing else leaves the phone: no analytics, no ads, no crash uploads. Crash reports stay in
app-private storage and are mirrored to `/.filet_logs/` so you can read them, because a file
manager's stack traces carry your paths.

### Why Play Protect warns about Filet

Filet is installed from outside the Play Store and signed with my own key, and it asks for what
a file manager needs: all-files access, installing packages, seeing installed apps. That
combination is what Play Protect flags, whatever an app does with it.

You don't have to take this page's word for it. Every line of Filet is in this repository - read
it, or [build the APK yourself](#building-it-yourself) and install that instead.

---

## Credits

<table>
<tr>
<td align="center"><sub>Creator &amp; maintainer</sub><br><a href="https://github.com/uukjtisa"><img src="https://github.com/uukjtisa.png?size=160" width="80" alt=""><br><b>Niccc2007</b></a></td>
</tr>
</table>

### Contributors

Filet is shaped by the people who use it, test it, and take the time to say what is wrong and
what could be better. Thank you.

<!-- Ten to a row, in the order people first contributed; the eleventh starts a new row. -->
<table>
<tr>
<td align="center"><a href="https://github.com/KamrulShahed"><img src="https://github.com/KamrulShahed.png?size=112" width="56" alt=""><br><sub>Kamrul Shahed</sub></a></td>
<td align="center"><a href="https://github.com/versiogh"><img src="https://github.com/versiogh.png?size=112" width="56" alt=""><br><sub>versiogh</sub></a></td>
<td align="center"><a href="https://github.com/firetrojan"><img src="https://github.com/firetrojan.png?size=112" width="56" alt=""><br><sub>firetrojan</sub></a></td>
</tr>
</table>

### Built on

Filet bundles ARSCLib (Apache-2.0), smali and dexlib2 (BSD), LuaJ (MIT), Apache
commons-compress (Apache-2.0), XZ for Java (public domain) and zip4j (Apache-2.0) for
the archive formats,
sora-editor (LGPL-2.1, linked unmodified), ZXing (Apache-2.0) for QR codes, CameraX
(Apache-2.0) for the scanner, Media3 ExoPlayer (Apache-2.0) as a second video player, BeatRoot's beat tracker as carried in TarsosDSP (GPL-2.0-or-later
and GPL-3.0; the source is in `app/src/main/java/be/tarsos/dsp/beatroot`, with the file and
MIDI input removed), and a build of SQLite with FTS5 and trigram enabled. Full attribution is on the About screen in the app.

The document reader reads Markdown with commonmark-java (BSD-2-Clause) and its own extensions,
plus autolink-java (MIT) for bare addresses. Its alerts, lists and code highlighting are ported
from Mike Penz's multiplatform-markdown-renderer (Apache-2.0), whose list rendering began in Erik
Hellman's MarkdownComposer (MIT); its rounded inline code is ported from Saket Narayan's
extended-spans (Apache-2.0). Each ported file keeps its copyright and licence header. Pictures
load through Coil (Apache-2.0), with AndroidSVG (Apache-2.0) for SVG; code is coloured by
Highlights (Apache-2.0); the alert icons are Material icons (Apache-2.0).

RAR archives are read with libarchive's independent RAR readers (BSD-2-Clause), vendored in
[`core-native/`](core-native/README.md) at about 130 KB per ABI - the only native code in the app.

Every one of those is compatible with GPL-3.0, and `tools/check-licences.mjs` fails the build
if a dependency is added that is not. That check is also where the RAR decision is recorded:
the available Java decoders carry the UnRAR licence, which restricts what the software may be
used to build, and a GPL-3 project cannot accept that restriction.

---

## Licence

GPL-3.0. See [`LICENSE`](LICENSE). The libraries Filet is built on and their licences are listed
under [Built on](#built-on).
