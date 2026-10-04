# Remote files — reading them without copying them

OWNS: `core-vfs/**/net/**`, the archive and APK read paths, the staging prompt

Scope: what happens when a file on a mounted share is inspected, browsed into, or acted on.
The short version: **reads are ranged and silent, writes never go back over the wire unless
asked.**

---

## The fault this fixes

An archive reads its index from the **end** of the file and then seeks to each entry. An APK is
an archive. Both need random access, and a WebDAV mount gave only a forward stream — so
`ApkTools` asked for a real on-disk path, got nothing, and the answer to "inspect this APK on
my tablet" was that you could not.

The capability was already half-built and nobody had noticed: **the server serves `Range`
requests** (`DavRange`), and **the client has never sent one**. Teaching the client to ask is
what unlocks everything below.

---

## Three tiers, and you only ever see the third

The tier is a **consequence of what was tapped**, never a setting. A setting here asks somebody
to predict a cost they cannot see, on an operation that is usually a few kilobytes.

### Tier 1 — metadata, ranged, automatic

Listing an archive is one range for the last 64 KB (the index), then one or two for headers.
**Two to four requests for an archive of any size.** An APK's manifest and resource table are
single entries; pull those bytes and ARSCLib reads package, version, minSdk, permissions and
the icon out of them.

No prompt, no download, milliseconds on a LAN.

### Tier 2 — one member of an archive, ranged

Opening a file inside a zip fetches that entry's byte range and decompresses it. The rest of
the archive is never transferred.

### Tier 3 — a real local copy, and the only place anything is asked

Three things genuinely need the whole file on the device:

- **Handing a path to Android or to a tool** — install, decompile, rebuild, sign.
- **Solid archives** — `.tar.gz`, `.tar.xz`, solid `.7z`. One continuous compressed stream, so
  entry 400 is only reachable through entries 1 to 399. Ranged access cannot help and
  pretending otherwise would be slower than downloading.
- **A server with no `Accept-Ranges`.** Ours has it; a NAS may not. Detected on HEAD rather
  than assumed, and the fallback is a plain fetch rather than ranges it will mishandle.

### The small-file shortcut

Under **2 MB**, fetch the whole thing instead of ranging it. Twenty round trips to avoid
downloading two megabytes is the optimisation making things worse.

---

## PackageManager is for local files only

`getPackageArchiveInfo` takes a path, not a stream, so it cannot read a remote APK — and it
does not need to. **ARSCLib reads everything the inspector shows**, from bytes, over ranges,
including the icon.

So the split is by source, not by field:

| Source | Parser | Why |
|---|---|---|
| Local file | PackageManager, then ARSCLib | It is the OS's own parser, so it agrees with what will actually install |
| Remote file | ARSCLib only | No path exists, and nothing shown needs one |

This is what shrank tier 3 from "anything involving an APK" to "operations that produce or
install a file".

---

## The staging prompt

Shown once, before anything begins, when tier 3 is reached. Never per-step and never silent.

```
┌─────────────────────────────────────────────┐
│  ⤓  Needs a copy on this phone              │
│     app-release.apk · 34.4 MB               │
│                                             │
│  Rebuilding works on a file stored here,    │
│  so it has to be downloaded first.          │
│                                             │
│  ◉ Temporary                                │
│    Cleaned up on its own afterwards.        │
│                                             │
│  ○ Keep it in  Download                     │
│    Change folder                            │
│                                             │
│              [ Cancel ]  [ Download ]       │
└─────────────────────────────────────────────┘
```

**One download either way.** Choosing a folder means the file is downloaded *there* and the
action runs on *that* file. It is a destination, not an extra copy.

### Where this differs from the first sketch, and why

The original idea was: download it, then select it, then trigger the ordinary copy action so a
folder picker opens. That is rejected, and the reason is worth keeping:

- It is **two decisions for one intention**. You asked to rebuild; being handed a file-manager
  copy flow in the middle of that is the app changing the subject.
- It **downloads once and then copies again**, so a 34 MB file costs 68 MB of work.
- It conflates **where the file is kept** with **what was asked for**, and the second is the
  thing actually in flight.

A destination chosen inside the one prompt gives the same control — including the same folder
picker, behind *Change folder* — for one decision and one transfer.

### Rules

1. **Refuse before starting, not at 90%.** Not enough room says so up front, with the figure:
   `Needs 340 MB free.`
2. **Default is Temporary.** The common case is inspect-and-forget, and a file that cleans
   itself up is the one that does not accumulate.
3. **The chosen folder is remembered**, the Temporary-or-Keep choice is not. Somebody who picks
   a folder has a place they like; somebody who keeps a file once has not decided anything.
4. **Already cached means no prompt.** Ask once per file, not once per action.
5. **Keyed on path, mtime and size.** A remote file that changed produces a different key, so
   nothing ever acts on a stale copy.
6. **The cache is size-capped and evicted least-recently-used.** Temporary has to mean it.

---

## Output goes local, and going back over the wire is a separate step

**The decision that matters most here.** A rebuild is minutes of work. Streaming the result
back over Wi-Fi can fail at 95% and leave a corrupt APK sitting where the good one was — the
one outcome that is worse than not having the feature.

So the output of decompile, rebuild and sign lands **on the phone**, always. When it succeeds,
an explicit **Copy back to the share** is offered: cheap, retryable, and refusable.

Nothing in this path deletes the remote. A move-after-rebuild would be the natural shortcut and
it is how somebody loses the only copy of something.

---

## Build order

Each step is useful on its own and none of them needs the next one to be worth shipping.

| # | Step | What it turns on |
|---|---|---|
| 1 | Ranged reads in `WebDavProvider`, `Accept-Ranges` probe, `Capability.RANDOM_ACCESS` declared where real | nothing visible; everything below rests on it |
| 2 | Zip central directory over ranges | **archives browse on a share** |
| 3 | ARSCLib manifest and icon over ranges | **the APK inspector works on a share** |
| 4 | The staging prompt, its cache, and copy-back | install, decompile, rebuild, sign, solid archives |

Steps 2 and 3 are the ones that are felt.
