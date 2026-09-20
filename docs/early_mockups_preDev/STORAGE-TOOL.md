# Storage — the spec

> Status: **proposal**. Nothing in here is built. The mock is
> `filet-redesign-mock.html`, Screen → *Storage tool*.

A dedicated screen that scans everything Filet can read, shows where the space went, and
lets you stage what to remove before anything is removed.

---

## The two tools it is trying to be

These are different tools solving the same problem from opposite ends, and Filet needs both
halves because the two questions people actually ask are different.

**TreeSize** — the expert end. Its whole design is one idea: **every folder row carries the
size of everything beneath it**, not its own files. Sort by that, and the biggest thing is
always the top row. Click into it, and the biggest thing inside *it* is the top row. Three or
four clicks and you are standing on the 8 GB nobody remembered. Each row draws an inline bar
for its share, columns give the size, the percentage and the file count, and there is a
treemap view for the people who think in rectangles.

It is fast because NTFS lets it read the Master File Table directly — one pass over a record
table instead of a walk. **This is the fact that decides Filet's architecture, and it is the
one thing that does not transfer.**

**Disk Drill's Clean Up** — the assisted end. It does not ask you to navigate anything. It
scans, then presents *categories* of reclaimable space — caches, old downloads, duplicates,
junk — with a size against each. You tick, a running total shows what you would get back, and
one confirm does the lot. The assumption is that you do not want to learn your own filesystem,
you want the space.

Filet takes the tree from the first and the staged basket from the second. "Appoint for
removal" is the right name for the basket precisely because putting something in it removes
nothing.

---

## The architecture, and the constraint that sets it

### There is no MFT on Android

A phone has no equivalent of the MFT, so a first scan is a walk of every directory. On a
device with 200,000 files that is minutes, not the seconds TreeSize takes. Three consequences,
and they are not negotiable:

1. **The roll-up rides on the crawl that already exists.** `FileIndex` already visits every
   file to build the search index. Sizes are summed on that same pass. A second full walk for
   the storage screen would double the most expensive thing the app does.
2. **A scan is resumable and always says where it is.** Same fix as the indexer: the path
   being read is on screen, because a count that moves in thousands looks identical to one
   that has stopped.
3. **Every number says how fresh it is.** A size from a warm index is a fact from whenever
   the crawl last passed. A stale size is a wrong answer that looks exactly like a right one,
   so the screen distinguishes *counted just now* from *last counted on Tuesday*, and a folder
   nobody has counted shows nothing rather than a zero.

### Android/data is not readable, and the screen says so

`Android/data` and `Android/obb` stopped being handed to file managers on API 30+. On most
phones that is the single largest thing on the device — games, chat media, caches. Filet
cannot walk it and must not pretend the number came from a walk: it shows what the system
reports, labelled as such, and says plainly that this is the one place a file manager cannot
help. A cleaner that quietly under-reports the biggest directory on the phone is worse than
one that has no opinion about it.

### Bars scale to the parent, not the device

A bar showing a folder's share of the whole 118 GB makes everything below the top level a
hairline, which is the mistake most phone cleaners make. The bar is the share of the folder
you are standing in, with the largest child filling the track. The percentage column still
reads against the parent, so the number and the bar answer the same question.

---

## What the screen has

| Mode | What it answers |
|---|---|
| **Folder tree** | Where did the space go? Drill down, biggest first, aggregated sizes. |
| **Largest files** | What are the single biggest things? Skips the drilling entirely — usually the fastest route to several GB. |
| **By type** | Video, photos, audio, apps, archives, everything else. Most phone space is one or two of these. |
| **Clean up** | The Disk Drill half: categories of reclaimable space, ticked into the tray. |

**The tray** sits under all four. Drag a row into it, or tick a category. It shows the count,
the total reclaim, and two ways out:

- **Move to a holding folder** — everything goes to `Filet/Appointed`, recoverable until you
  empty it. Android has no bin, and this is the nearest honest thing to one.
- **Review and remove** — a full list, with paths, then permanent deletion.

---

## What Clean Up looks for

Each of these is cheap to detect and expensive to find by hand.

| Find | How it is decided | Why it is safe to offer |
|---|---|---|
| Thumbnail caches | `.thumbnails` directories | Android regenerates them on demand |
| Superseded installers | APKs whose package is installed at an equal or newer version | The installed app is the copy that matters |
| Duplicate files | Size buckets first, content hash only within a bucket | Identical bytes; keeps the one with the oldest timestamp |
| Untouched for over a year | Last-access, falling back to modification time | Age is the single best deletion heuristic, and nothing else surfaces it |
| Empty folders | Zero entries, recursively | Nothing can be lost |
| Archives already extracted | An archive whose entry names all exist beside it | The contents are already on disk |

**The hashing rule:** never hash the whole device. Group by size first — the overwhelming
majority of files are a unique size and are eliminated for free — then hash only inside groups
of two or more, and read the first and last 64 KB before committing to a full hash.

---

## Rules this tool does not get to break

These are the difference between a file manager and a phone "cleaner", and the whole reason
this can ship under Filet's name.

1. **Nothing is ever deleted without being shown first, with its real path.** No "1 tap to
   clean", no progress bar that removes things while it runs.
2. **No heuristic deletes anything.** A find *offers*; a person decides. The tray exists so
   the decision is made once, over a complete list, rather than six times in a row.
3. **No invented numbers.** "Freed up 2.4 GB!" when it deleted a cache Android rebuilds in a
   minute is a lie, and it is the standard behaviour of the category.
4. **No scanning of what was deliberately hidden.** A dot-folder and anything under a
   `.nomedia` are somebody saying what they want. Same rule the media announce already follows.
5. **Exclusions are remembered.** A folder marked *never offer this* stays marked. DCIM/Camera
   is excluded by default, because the one thing that must never be easy to delete by accident
   is photographs.

---

## Proposals — things worth adding, ranked

Each of these is separable; none is required for a first version.

1. **What changed since last time.** Keep the previous scan and diff it: *"Download is 4.2 GB
   bigger than last week"*. TreeSize has this and on a phone it is the killer feature, because
   the interesting question is rarely "what is big" but "what grew". It costs one extra stored
   snapshot.
2. **A treemap.** Rectangles proportional to size, nested by folder. Dense, tappable, and
   reads better on a phone than a desktop. A second view, never the only one — a treemap
   cannot be sorted and cannot be read aloud.
3. **Per-app attribution for what can be read.** `Android/media/<package>` *is* readable, so
   WhatsApp media, Telegram downloads and the rest can be attributed to the app that made
   them even when `Android/data` cannot.
4. **The biggest folder not opened in a year.** The intersection of the size question and the
   age question, which is where the safe deletions actually live.
5. **Scan the SD card and USB-OTG as separate volumes**, never summed. A number that adds two
   volumes together cannot answer "will this fit".
6. **Export the tree** as text or CSV. TreeSize users live on this, and it costs almost
   nothing given the tree already exists in memory.
7. **A size column in the normal file list**, computed lazily, fed by the same roll-up. This
   is the feature people actually want day to day; the dedicated screen is where it is
   explained, the file list is where it is used.
8. **Block-slack reporting** — apparent size versus size on disk. Mostly academic on a phone,
   except for the folder with 40,000 tiny files, which is exactly the folder you are hunting.
9. **A scheduled quiet re-scan** on charge and idle, so the numbers are warm when the screen
   is opened. Never a notification; a cleaner that nags is the thing this is not.

## Open questions

- Should the tray survive leaving the screen? Argument for: a long triage session across
  several folders. Argument against: a forgotten basket that deletes something next week.
  Current lean is **yes, with the contents shown every time the screen opens**.
- Is *Move to a holding folder* the default action rather than the second one? It is the safe
  one, and on a device with no bin the safe one has a claim to being the default.
