# Filet — the search model

Opened 2026-09-13 · v2 (flagship). Owns L1 of `PLAN.md`.

Two requirements that fight each other:

1. **Fastest search on Android** — results as you type, on a device holding 40k–200k files.
2. **Never a stale entry** — a dead result, or a live file missing from results, is worse
   than a slow search.

One sentence resolves them, and everything below follows from it:

> **The index is a candidate generator. The filesystem is the truth.
> Nothing reaches the screen without being confirmed against the filesystem first.**

That is what lets the index be aggressive, lossy and *fast* without ever lying.

The second organising idea, which is what makes this flagship rather than merely correct:

> **Retrieve, then rerank.** SQL narrows 200,000 files to ~500 candidates in under 10 ms.
> An in-memory scorer then does the expensive, intelligent work on those 500 in ~2 ms.
> Never ask SQL to be clever, and never ask the scorer to be fast at scale.

---

## 0. Scope

| In | Out |
|---|---|
| Names, path segments | File **contents** (grep / full-text) |
| **Inside containers** — APK package/label/version/permissions/classes, archive member names | OCR, image similarity |
| Provenance — source URL, origin app (PLAN §5.1) | Cloud accounts with no VFS provider behind them |
| Type, size, mtime, extension, duplicates | Indexing a network share that was not turned on (§13.6) |
| **Network drives — reachable by search, walked live (§13)** | |

Content search is a different product: 10× the index, a different battery profile, a
different failure mode. Ruling it out is what keeps this one small enough to be instant.

**Why the Windows trick cannot be ported.** *Everything* (voidtools) is instant because it
reads the NTFS **MFT** directly — the filesystem hands it the complete file list for free.
ext4 and f2fs expose no equivalent to userspace, and Android would not grant it if they did.
There is no shortcut around building our own index. Every design decision here follows from
that being unavoidable.

---

## 1. Why the obvious approaches fail

| Approach | Why not |
|---|---|
| Recursive `File.listFiles()` on demand | Every `File` allocates; every `length()`/`lastModified()` is its own `stat()`. 40k files ≈ 80k+ syscalls. Seconds. |
| `MediaStore` as the index | **You do not control invalidation**, and it cannot see network shares, `/data`, `.nomedia` trees or other apps' document providers. Not "media only", though - measured, it holds *more* of scanned shared storage than this index does (§13.10). A first-class candidate source, never the index. |
| `FileObserver` on everything | One inotify watch per directory, per-process ceiling commonly ~8192, does not survive process death, and **SAF volumes have no inotify at all**. |
| Index-only results | Precisely the stale-entry failure. Every "instant search" file manager that shows dead rows made this choice. |
| FTS5 relevance (bm25) alone | Built for prose. Ranks filenames badly — `a.txt` and `annual-report-2026-final.txt` are not a document corpus. |

---

## 2. The index

### 2.1 Bundle SQLite. Do not use the system one.

- **FTS5 is not guaranteed** on Android's system SQLite. Some OEM builds compile it out, and
  you find out from a crash report on a device you do not own.
- The **`trigram` tokenizer needs SQLite ≥ 3.34**; the system library reaches that around API
  33. Filet's floor is API 26.

`requery/sqlite-android` or an equivalent bundled build. ~4 MB per ABI, and it buys
determinism on every device — the same reasoning that picks ARSCLib over a native aapt2.

### 2.2 Schema

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous  = NORMAL;
PRAGMA temp_store   = MEMORY;
PRAGMA mmap_size    = 268435456;

CREATE TABLE volume (               -- one row per mounted thing
  id      INTEGER PRIMARY KEY,
  kind    TEXT,                     -- internal | sd | saf | smb | sftp
  uuid    TEXT UNIQUE,              -- StorageVolume.getUuid() — survives re-slotting
  label   TEXT,
  mounted INTEGER
);

CREATE TABLE node (
  id        INTEGER PRIMARY KEY,
  vol_id    INTEGER NOT NULL REFERENCES volume(id),
  parent_id INTEGER,                -- NULL at a volume root
  name      TEXT    NOT NULL,
  name_fold TEXT    NOT NULL,       -- NFC + casefold + diacritics stripped
  is_dir    INTEGER NOT NULL,
  size      INTEGER,
  mtime     INTEGER,
  dir_mtime INTEGER,                -- directories: mtime at last readdir
  flags     INTEGER DEFAULT 0,      -- hidden | nomedia | container | indexed_inside
  gen       INTEGER NOT NULL
);
CREATE UNIQUE INDEX node_uq   ON node(vol_id, parent_id, name);
CREATE INDEX        node_par  ON node(parent_id);
CREATE INDEX        node_gen  ON node(gen);
CREATE INDEX        node_pfx  ON node(name_fold COLLATE BINARY);  -- short queries, §5.2
CREATE INDEX        node_size ON node(size) WHERE is_dir = 0;     -- dupes + size: filters

-- external content: names stored once, in node
CREATE VIRTUAL TABLE node_fts USING fts5(
  name_fold, content='node', content_rowid='id',
  tokenize="trigram case_sensitive 0"
);

-- what is INSIDE a container (§3)
CREATE TABLE inner (
  node_id INTEGER NOT NULL REFERENCES node(id) ON DELETE CASCADE,
  kind    TEXT NOT NULL,            -- pkg | label | version | perm | class | member
  value   TEXT NOT NULL
);
CREATE INDEX inner_node ON inner(node_id);
CREATE VIRTUAL TABLE inner_fts USING fts5(
  value, content='inner', content_rowid='rowid', tokenize="trigram case_sensitive 0"
);

-- frecency (§5.4)
CREATE TABLE usage (
  node_id  INTEGER PRIMARY KEY REFERENCES node(id) ON DELETE CASCADE,
  opens    INTEGER DEFAULT 0,
  last_at  INTEGER,
  pinned   INTEGER DEFAULT 0
);

CREATE TABLE saved_search (         -- smart folders (§6.3)
  id INTEGER PRIMARY KEY, name TEXT, query TEXT, icon TEXT, sort INTEGER
);
```

**`parent_id`, never a stored full path.** Renaming or moving one directory costs one row
update instead of rewriting every descendant. On a phone, moving a folder of 3,000 photos is
a normal Tuesday. Paths are reassembled by walking parents — a handful of PK lookups.

**`name_fold` is what gets indexed.** NFC-normalised, case-folded, diacritics stripped, so
`Résumé` is found by `resume` and a filename that arrived NFD from a Mac still matches one
typed NFC. Indexing the raw `name` instead is a bug that only shows up on other people's
files.

**Index names, not paths.** Every ancestor is already its own row; indexing full paths would
store each ancestor's name once per descendant.

### 2.3 The trigram gotcha nobody mentions

**Trigram cannot accelerate queries shorter than 3 characters.** A one- or two-letter query
degrades to a full table scan of the FTS index — the exact opposite of fast.

So the query planner branches on length:

| Query length | Path |
|---|---|
| 1–2 chars | `node_pfx` prefix index: `name_fold >= q AND name_fold < q+'￿'`, `LIMIT 500` |
| 3+ chars | `node_fts MATCH` (trigram), `LIMIT 500` |

Both feed the same reranker, so the user never sees the seam.

### 2.4 Size budget — 41k files, names averaging ~28 chars

| | |
|---|---|
| `node` | ~4.5 MB |
| `node_fts` trigram | ~3.4 MB |
| `inner` + `inner_fts` (≈40 APKs, ~120 archives) | ~6 MB |
| indexes, usage, WAL | ~3 MB |
| **Total** | **≈ 17 MB** |

Container contents are the expensive half and the most differentiating, so they are
**budget-capped and evictable** (§3.4).

---

## 3. Index what is *inside* things — the differentiator

No Android file manager does this. It is also the capability most aligned with what Filet
already is.

### 3.1 APKs

On first sight of an `.apk`, parse and store to `inner`:

| kind | From | Cost |
|---|---|---|
| `pkg` | `AndroidManifest.xml` via **ARSCLib** — already a dependency | ~10 ms |
| `label`, `version`, `versionCode` | same | free |
| `perm` | same | free |
| `class` | dex header → `class_defs` → `type_ids` → `string_ids` via **dexlib2** | 30–200 ms |

Queries that become possible:

```
pkg:com.whatsapp          which APK on this device is WhatsApp
class:okhttp3             every APK bundling OkHttp
perm:RECORD_AUDIO         every APK that wants the microphone
label:"Fruit Ninja"       find it by the name on the launcher, not the filename
```

**Class lists are opt-in per volume and capped** — a 300 MB game APK has ~80k classes. Store
only **package prefixes** (`okhttp3`, `com.google.android.gms.ads`), deduplicated: ~2 KB per
APK instead of ~2 MB, and it answers the question people actually ask.

### 3.2 Archives

A zip's **central directory** sits at the tail of the file. Read the EOCD, seek, and you have
every member name **without inflating a single byte** — ~5 ms for a 500 MB zip.

```
inzip:AndroidManifest     which of my 40 zips holds it
member:*.psd
```

Capped at 5,000 members per archive; beyond that, store the first 5,000 and a `truncated`
flag that the UI surfaces honestly.

### 3.3 Media and documents — cheap metadata only

`MediaMetadataRetriever` for duration and ID3, EXIF for capture date and camera, PDF page
count. Extracted lazily, on first display or on an idle sweep — never during the crawl,
because these are slow and the crawl must not be.

### 3.4 Container indexing is a *budget*, not a promise

- Runs on a **WorkManager job**: charging + idle + unmetered.
- Capped (default 200 MB of `inner`), **LRU-evicted** by last query hit.
- Never runs on a network volume.
- Queries know what is unindexed and say so: *"12 archives not yet indexed — index now?"*
  rather than silently returning nothing. R4 (every claim checkable) applies to search
  results too.

---

## 4. Never stale — five layers

No single mechanism suffices. Each covers what the one above it misses.

### 4.1 inotify on hot directories — *instant, narrow*

`FileObserver` on a **budget-capped LRU set** — both open panes, Downloads, DCIM, tracked
folders, last ~N visited. **Cap 512**, well under the ceiling, covering everywhere the user
is actually looking. Use the `FileObserver(File, int)` constructor; the `String` one is
deprecated and misbehaves on Android 10+.

### 4.2 Directory-mtime validation on read — *the workhorse*

A directory's mtime moves whenever an entry is **added, removed or renamed** in it. So before
listing or searching inside a directory: one `stat`, compare to stored `dir_mtime`, re-read
only that directory if they differ.

**One syscall per directory, not per file.** This is what makes the index trustworthy with
nothing watched at all — after an eviction, after a process kill, after a reboot.

Its limit, stated so nobody relies on more: a directory's mtime does **not** move when a
descendant changes. It guarantees a correct *listing of that directory*, not a correct
subtree. §4.3 and §4.4 exist for that.

### 4.3 Verify-before-display — *the actual guarantee*

Candidates come back from the index. Before **any** row is painted:

- `stat` the **visible window** (~50 rows) — sub-millisecond each, ~2 ms total
- Gone ⇒ dropped from the result set and the row deleted
- Changed ⇒ refreshed in place
- Below the fold ⇒ verified as it scrolls in

**A dead file cannot reach the screen**, because the screen never renders the index — it
renders the confirmed stat. The index only decides *which 50 things to check*, which is the
part that would otherwise cost seconds.

### 4.4 Generation sweep — *catches the rest*

Bump a global counter, walk the **stored** tree:

- Each directory row: one `stat`. `dir_mtime` matches ⇒ children are current; stamp the new
  gen and descend **without a `readdir`**.
- Differs ⇒ `readdir`, diff against stored children, insert/update/delete.
- Finally `DELETE FROM node WHERE gen < :current` — tombstone sweep for whole vanished
  subtrees.

41k files across ~3,000 directories ≈ **3,000 stats ≈ 30–60 ms.** Cheap enough for every app
start.

Triggers: app start · `ACTION_MEDIA_MOUNTED` / `MEDIA_EJECTED` / `MEDIA_REMOVED` · SAF tree
granted or revoked · WorkManager on charging + idle · pull-to-refresh.

### 4.5 `MediaStore` as a free change *hint*

`MediaStore.getGeneration(context, volumeName)` (API 30+) is a monotonic counter the media
scanner bumps whenever anything in the media collections changes. One call, no permissions
beyond what we hold, no cursor.

Store it; if it moved since last check, something changed in the media directories — go
validate them first. **A near-free signal that tells us where to look.** Not an index, not a
source of truth, and it says nothing about non-media files. Free is free.

The generation counter stays exactly this: a hint. What `MediaStore` *also* turned out to be -
the best candidate source for scanned shared storage, which is a separate claim with a
measurement behind it - is §13.10.

### 4.6 Volumes with no inotify

**SAF trees (SD card, `Android/data`) and network volumes get nothing from §4.1.**

- `DocumentsContract.buildChildDocumentsUriUsingTree()` returns a whole directory's children
  in **one cursor** including `COLUMN_LAST_MODIFIED` and `COLUMN_SIZE` — far cheaper than
  per-file IPC, and it makes §4.2 work over SAF unchanged.
- `registerContentObserver()` on that children URI — the platform provider notifies;
  third-party providers are inconsistent, so treat it as a bonus, never a mechanism.
- **Network volumes are never background-crawled.** Indexed lazily on visit with a short TTL
  and searched live. Indexing a NAS over someone's mobile data is a bug.
- Volumes are **index shards** keyed on `volume.uuid`. Ejecting an SD card marks the shard
  unmounted and hides its rows in one statement; reinserting it restores them without a
  re-crawl, and `uuid` means it still works if it comes back in a different slot.

---

## 5. Matching — where flagship is actually won

### 5.1 Retrieve, then rerank

```
query ─┬─ 1–2 chars → node_pfx prefix scan  ─┐
       └─ 3+  chars → node_fts trigram      ─┤
                       inner_fts (containers)─┼─→ ≤500 candidates
                       provenance table     ─┘         │
                                                       ▼
                                    in-memory scorer (§5.2–5.4)
                                                       │
                                             top 200, stable order
                                                       ▼
                                        verify visible 50 (§4.3) → paint
```

SQL is asked only to be **fast and dumb**. The scorer is allowed to be **slow and clever**,
because it never sees more than 500 rows.

### 5.2 The scorer — fzf-class, not `LIKE`

Subsequence matching with positional bonuses, run over `name_fold`:

| Signal | Weight |
|---|---|
| Exact full-name match | +1000 |
| Match starts at position 0 | +200 |
| Match starts at a **word boundary** (`-`, `_`, `.`, space, camelCase hump) | +120 per boundary hit |
| **Consecutive** matched characters | +40 per run, superlinear |
| Match is in the name vs an ancestor directory | +150 / +0 |
| Extension exact (`apk` query, `.apk` file) | +80 |
| Distance from match start to end of name | small penalty |
| Gaps between matched chars | −8 each |

So `dwnld` finds `Downloads`, `mpmgr` finds `MP-Manager-1.4.apk`, and `AM` finds
`AndroidManifest.xml` by its camel humps. Trigram retrieval alone cannot do any of that —
it only guarantees the candidate is *in the pile*.

**Typo tolerance** is bounded edit distance (Damerau-Levenshtein ≤1 for queries ≤6 chars,
≤2 above) applied **only to the 500 candidates**, never as a retrieval strategy. `screnshot`
finds `screenshot`; the cost is microseconds because the set is tiny.

Implement the scorer in Kotlin against a `CharArray`, no regex, no allocation per candidate.
500 candidates × ~30 chars is a trivial amount of work — budget **< 2 ms**.

### 5.3 Stable streaming order — the UX failure nobody plans for

Results arrive from several sources at different times (index, container index, live scan).
Naively appending makes the list **reshuffle under the user's thumb** as they reach for a
row. That is the single most-hated behaviour in any search UI.

Rules:

1. First paint happens at a **fixed 90 ms** after the last keystroke — not on first result.
   Everything arriving inside that window is sorted together.
2. After first paint, a late arrival may only be **inserted below the visible viewport**, or
   it waits.
3. Rows never move once painted, unless the query changes.
4. A row proven dead by §4.3 fades out in place and leaves a gap that closes on the next
   scroll — it does not yank the list upward.

### 5.4 Frecency — what makes it feel psychic

```
boost = 1 + 0.8·ln(1+opens)·exp(−Δt / τ)      τ = 14 days
      + 0.5 if in the pane's current subtree
      + 0.3 if in a tracked folder
      + 1.2 if pinned
final = match_score × boost
```

Opens are recorded on real interactions only (opened, edited, shared, pinned) — never on a
mere appearance in a result list, or the ranking gradually eats itself.

**What you are looking at is what you probably mean.** The subtree boost matters more than
any clever relevance maths.

### 5.5 Latency budget

| Stage | Target |
|---|---|
| Debounce | **120 ms** (300 feels laggy, <100 wastes queries) |
| Retrieval, `LIMIT 500` | < 10 ms warm |
| Score + rerank 500 | < 2 ms |
| Verify visible 50 | ~2 ms |
| **Keystroke → painted** | **< 30 ms** |

Every query carries an **epoch**; a new keystroke increments it and every in-flight stage
checks it and abandons. Structured concurrency — one scope per query, cancelled wholesale.
Never on the main thread, not even a "small" one.

### 5.6 Two different interactions, deliberately not merged

| | |
|---|---|
| **Type-to-jump** | Typing with the list focused and no search open scrolls to the first match *in the current listing*. Instant, no index, Explorer behaviour. |
| **Search** | ⌕ opens the pane search (see the mock). Scoped, ranked, streamed. |

Collapsing these into one is why some file managers feel like you cannot simply *get to* a
file you can already see.

---

## 6. The query language

### 6.1 Grammar

```
query      := or_expr
or_expr    := and_expr ( "OR" and_expr )*
and_expr   := unary ( " " | "AND" unary )*          -- space = AND
unary      := "-" unary | "(" or_expr ")" | term
term       := field ":" value | quoted | bare
field      := name|ext|type|size|modified|created|in|from|pkg|class|perm|label
            | inzip|member|dup|tag
value      := comparator? literal | range
comparator := ">" | ">=" | "<" | "<="
range      := literal ".." literal
```

```
type:apk size:>50mb modified:<7d -in:Android
from:youtube modified:today
(pkg:com.google OR pkg:com.android) perm:RECORD_AUDIO
class:okhttp3 -label:"Google Play services"
dup:exact size:>100mb
```

Parsed to an AST, then split: everything expressible as SQL becomes a predicate **before**
retrieval (so it costs nothing), the rest becomes a post-filter on the 500.

`from:` is the one nothing else on Android has — it searches **provenance** (PLAN §5.1).

### 6.2 The UI must teach the grammar

A grammar nobody discovers is a grammar nobody uses. So the scope chips in the pane search
bar are not decoration — **tapping one writes its token into the query**. `Whole device`
inserts nothing, `Provenance` inserts `from:`, a long-press on a file's type chip inserts
`type:apk`. The user learns the language by using the buttons.

Invalid syntax never errors. It degrades to a literal substring search, with the unparsed
fragment marked in the field.

### 6.3 Saved searches are smart folders

Any query can be saved, named, given an icon, and **appears in the rail beside real
folders**. It refreshes on open and on index change.

```
Big APKs        type:apk size:>50mb
From YouTube    from:youtube.com
This week       modified:<7d -type:dir
Camera dupes    dup:exact in:DCIM
```

Same machinery as search, zero extra cost, and it turns a search box into a filesystem view.

### 6.4 Duplicate finding, free from the index

The index already holds every size. Three-stage cascade, each stage only touching what
survived the last:

1. **Group by exact size** — pure SQL, no IO. Kills >95% of candidates instantly.
2. **Head+tail hash** — first 4 KB + last 4 KB, xxHash64. Two reads per file.
3. **Full hash** — only for groups still colliding after stage 2.

A 41k-file device typically reaches stage 3 with a few dozen files. Seconds, not minutes.

---

## 7. Background execution — the indexing service

### 7.1 The principle everything else follows from

> **Never run a service to stay correct. Run work to stay *fast*.**

Correctness comes from the freshness stack in §4, which is stateless and works after any kill,
any reboot, any three-week gap. Background execution is a **performance optimisation**.

It can be denied, throttled, deferred for days, killed mid-run, or never scheduled at all — and
the app must be exactly as **correct**, just colder. Any design where "the index is right"
depends on "the service ran" is broken on Android, and spectacularly broken on the half of the
market running an aggressive OEM.

That is the difference between a background indexer that ships and one that collects one-star
reviews on Xiaomi.

### 7.2 Why a persistent foreground service is not an option

Not a style preference — three hard walls:

1. **Android 15 (API 35) caps `dataSync` foreground services at ~6 hours per 24 h.** Past the
   cap the system calls `Service.onTimeout()` and you stop or you are killed. A
   permanently-running indexer is not *rude*, it is **not permitted**.
2. **Doze and App Standby buckets.** In `rare` a job may run once a day; in `restricted`,
   effectively never. The OS decides, not you.
3. **OEM process killers.** MIUI, EMUI, ColorOS and FuntouchOS kill background processes
   regardless of what AOSP policy says. `dontkillmyapp.com` exists because of this.

So: no permanent service, and no design that needs one.

### 7.3 Five tiers, in ascending cost

| Tier | Mechanism | Runs when | Cost |
|---|---|---|---|
| **0** | In-process inotify watchers (§4.1) | App alive | Free |
| **1** | **Push from Trawl** (PLAN §5) | A download completes | Zero — it is an event |
| **2** | `JobScheduler` **TriggerContentUri** | OS wakes us on content change | Near-zero |
| **3** | **WorkManager** maintenance | Charging + idle | Deferred, batched |
| **4** | Short-lived **foreground service** | User asked and is watching | Visible, bounded, cancellable |

**Tier 0** dies with the process and that is fine — §4.2 and §4.4 restore correctness on next
launch in a few tens of milliseconds.

**Tier 1 is the best kind of background work: the kind you do not do.** Trawl already tells
Filet when a download lands. That is an event, not a scan. Any future writer of provenance gets
the same deal.

### 7.4 Tier 2 — wake-on-change, the piece nobody uses

`JobInfo.TriggerContentUri` asks the **system** to wake a job when content at a URI changes.
Batched, Doze-aware, no process held, no wakelock, essentially free:

```kotlin
JobInfo.Builder(JOB_WATCH, ComponentName(ctx, IndexTriggerJob::class.java))
    .addTriggerContentUri(
        JobInfo.TriggerContentUri(
            MediaStore.Files.getContentUri("external"),
            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
    .setTriggerContentUpdateDelay(10_000)   // batch a burst of changes
    .setTriggerContentMaxDelay(120_000)     // but never sit on them forever
    .build()
```

This is how Filet notices new files **while it is closed** without running anything.

Three things that must be right or it silently does nothing:

- **Content-trigger jobs are one-shot.** Re-schedule from inside `onStartJob` *before* doing
  work, or the watch quietly ends after the first fire.
- `JobParameters.getTriggeredContentUris()` names what changed — validate **only those
  directories**. A trigger is a pointer, not a reason to sweep the device.
- That call returns `null` when more than 50 URIs changed — the overflow case. Fall back to
  validating the affected volumes rather than assuming nothing happened.

**Honest limit:** it fires for MediaStore-visible content — photos, video, audio, downloads,
documents the scanner sees. Not arbitrary files in obscure directories. Combined with §4.2 and
§4.4, the coverage gap closes on next open, which is exactly when it matters.

### 7.5 Tier 3 — WorkManager maintenance

Everything unattended: the generation sweep, container indexing, stale eviction,
`PRAGMA optimize`, orphan cleanup.

```kotlin
PeriodicWorkRequestBuilder<IndexMaintenanceWorker>(12, TimeUnit.HOURS)
    .setConstraints(Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresDeviceIdle(true)
        .setRequiresStorageNotLow(true)
        .setRequiresBatteryNotLow(true)
        .build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
    .build()
```

Enqueued with a **unique name** and `ExistingPeriodicWorkPolicy.KEEP`, so app restarts do not
stack duplicates. Survives reboot for free.

`setExpedited` is reserved for a **user-initiated** re-index — expedited quota is small, and
spending it on maintenance means it is gone when it actually matters.

### 7.6 Tier 4 — the foreground service, used sparingly and correctly

Only for work the user **asked for and is watching**: first-run crawl, manual re-index, an
explicit "index this archive".

- `android:foregroundServiceType="dataSync"` plus `FOREGROUND_SERVICE_DATA_SYNC` (API 34+)
- Progress notification with a real **Cancel** action, wired to actual cancellation
- **Ends when the work ends.** Never sticky, never `START_STICKY` with nothing to do
- **Implements `onTimeout()` (API 35):** checkpoint the crawl cursor and stop cleanly. Not
  implementing it is an ANR on Android 15
- Registers in the **job ledger** (PLAN §5.2) like every other long operation

### 7.7 Every run is interruptible and resumable

```sql
CREATE TABLE crawl_cursor (
  vol_id     INTEGER PRIMARY KEY,
  phase      TEXT,      -- enumerate | attrs | containers | sweep
  last_id    INTEGER,   -- resume point
  started_at INTEGER,
  gen        INTEGER
);
```

- Work is **chunked**: at most 256 directories or 400 ms per unit, then check `isStopped()` and
  checkpoint. WorkManager grants roughly ten minutes; never assume even that.
- Killed at 60 % resumes at 60 %. A crawl that restarts from zero on every interruption never
  finishes on a device that is interrupted constantly — which is every phone.

### 7.8 Backing off before the system has to

The indexer reads the device's own signals and yields *first*. Being throttled is a bug you
could have avoided.

| Signal | Response |
|---|---|
| `PowerManager.getCurrentThermalStatus()` at `MODERATE`+ | Halve the batch; suspend at `SEVERE` |
| Battery under 20 % and not charging | Maintenance skipped entirely |
| `UsageStatsManager.getAppStandbyBucket()` = `RARE` / `RESTRICTED` | Do not schedule the deep sweep; do a cheap §4.4 validation on next launch |
| `ConnectivityManager.isActiveNetworkMetered()` | Network volumes never indexed |
| `StorageManager.getAllocatableBytes()` low | Evict the container index first, then pause |

### 7.9 The indexer must never make the app feel slow

A background index that makes typing stutter is worse than no index.

- **Separate SQLite connection** for the writer; WAL keeps readers unblocked.
- **A foreground query pre-empts the indexer.** The writer checks a priority flag between
  batches and parks itself. User-facing latency always wins.
- `PRAGMA busy_timeout` is set, but treated as a backstop rather than the plan.
- The indexing thread runs at `THREAD_PRIORITY_BACKGROUND`, raised only for Tier 4, where the
  user is actually waiting on it.

### 7.10 When the OEM kills it anyway

Detect it rather than guessing: if N consecutive scheduled runs never started, the app is being
killed.

Then, **once** — never a nag, never on first launch:

> *Indexing keeps being stopped by your device's battery manager. Search will still work, but it
> will be slower after the app has been closed for a while. Exempt Filet from battery
> optimisation?*

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is **Play-policy restricted** — a file manager is not an
approved category — so it ships in the `github` and `fdroid` flavours only. If the user
declines, nothing breaks; the index just stays colder, which is the whole point of §7.1.

### 7.11 The user's controls, and the readout that makes them honest

**Settings → Indexing**

| Mode | Behaviour |
|---|---|
| **Automatic** (default) | All five tiers |
| **While charging only** | Tiers 0–2 always; Tier 3 gated on power |
| **Manual only** | Tier 0 plus explicit re-index |
| **Off** | No index at all — search falls back to the live scan (§5.3) |

**Off is a genuine option and search still works.** R1 (No Dead Switches) applies to settings
too: a toggle that quietly degrades the app into uselessness is a dead switch wearing a costume.
 deserves an answer:

> **41,206 files** indexed across 2 volumes · last swept **2 h ago** · 12 archives unindexed ·
> 3 runs blocked by battery optimisation this week

---

## 8. Permissions

### 8.1 What Filet actually needs

| Permission | API | For | Type | Degrades to |
|---|---|---|---|---|
| `READ_EXTERNAL_STORAGE` | <= 32 | Reading files | Runtime | Must have |
| `WRITE_EXTERNAL_STORAGE` | <= 28 | Writing pre-Q | Runtime | Read-only |
| `MANAGE_EXTERNAL_STORAGE` | 30+ | **Full file access** | **Special** — `Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`, checked with `Environment.isExternalStorageManager()` | SAF-only: trees granted per folder |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` | 33+ | Media metadata and thumbnails when MANAGE is absent | Runtime, granular | No thumbnails |
| `READ_MEDIA_VISUAL_USER_SELECTED` | 34+ | Partial photo access | Runtime | Selected items only |
| `ACCESS_MEDIA_LOCATION` | 29+ | EXIF GPS | Runtime | EXIF without coordinates |
| SAF tree grants | all | SD card, `Android/data` | `ACTION_OPEN_DOCUMENT_TREE`, persisted | That volume is invisible |
| `POST_NOTIFICATIONS` | 33+ | Job ledger progress, FGS notification | Runtime | Silent jobs; **the FGS still runs** |
| `FOREGROUND_SERVICE` | 28+ | Tier 4 | Install-time | — |
| `FOREGROUND_SERVICE_DATA_SYNC` | 34+ | Tier 4 type | Install-time | — |
| `RECEIVE_BOOT_COMPLETED` | all | Re-arm jobs after reboot | Install-time | First launch re-arms instead |
| `INTERNET`, `ACCESS_NETWORK_STATE` | all | Network providers, updater | Install-time | — |
| `REQUEST_INSTALL_PACKAGES` | 26+ | Install APKs, self-update | Special | **`github` flavour only** — never in the F-Droid manifest |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 23+ | §7.10 | Special | A colder index |

### 8.2 The rules for asking

1. **Nothing at first launch except what the next screen needs.** A permission wall before the
   app has shown what it is gets denied, and a denial is far harder to recover than a delay.
2. **Explain before the dialog, never after.** The onboarding permissions page gives one line of
   *why* per permission, so the system dialog is a confirmation rather than an ambush.
   `MANAGE_EXTERNAL_STORAGE` sends the user out to a Settings screen and back — an unexplained
   trip out of the app reads as a crash.
3. **Every permission has a named degraded mode** — the last column above. None of them is "the
   app is broken now".
4. **Never re-ask in a loop.** Two denials and we stop asking; it becomes an inline banner at
   the point of use, with a button to Settings.
5. **Re-check on every resume.** Special permissions are revocable from Settings while the app
   is backgrounded, and Android 11+ auto-revokes them for unused apps. The provider layer asks
   the OS, never a cached boolean.
6. **Ask at the point of need for anything volume-specific.** The SD card tree is requested when
   the user opens the SD card, not during onboarding.

### 8.3 The `MANAGE_EXTERNAL_STORAGE` reality

It is the permission that makes a file manager a file manager, and the one Google scrutinises.
Three facts to design around:

- **Play requires a declaration form** and approves it only for listed categories. File managers
  are on that list, but review is adversarial — which is why `PLAN.md` puts Play behind GitHub
  and F-Droid.
- `Environment.isExternalStorageManager()` is the **only** trustworthy check. Never infer it
  from anything else.
- **The app must be fully usable without it.** `SafProvider` is not a fallback bolted on later —
  it is a first-class provider (PLAN L0), which is exactly why the VFS exists. A user who
  declines gets a file manager scoped to the trees they grant, and nothing crashes.

### 8.4 Never ask for these

`QUERY_ALL_PACKAGES` (use `<queries>` intent filters instead), `SYSTEM_ALERT_WINDOW`,
`READ_PHONE_STATE`, any location permission, and `PACKAGE_USAGE_STATS` for anything
user-facing. A file manager requesting these is the profile of adware, and the audience Filet
targets reads the permission list before installing.

---

## 9. Production hardening

### 7.1 The index must self-heal

- `PRAGMA user_version` carries a **schema version**; a mismatch triggers migrate-or-rebuild.
- `PRAGMA quick_check` on open. Failure ⇒ delete and rebuild in the background. **Never show
  the user a corrupt-database error for a cache** — it is derived data, it is rebuildable, and
  it should rebuild itself silently.
- The index lives in `cacheDir`, not `filesDir`. The OS may delete it under storage pressure;
  that must be a non-event.
- Rebuilds are resumable: a `crawl_cursor` table means a kill mid-crawl resumes rather than
  restarting.

### 7.2 Partial results beat late results

Multi-volume searches run per-volume with **independent timeouts** (local 2 s, SMB 5 s).
. Results are grouped by volume so a partial answer is legible as
partial.

### 7.3 Privacy

Filenames are sensitive — they carry medical documents, legal papers, people's names.

- The index never leaves the device. No telemetry contains a filename or a query.
- `.nomedia` and a user exclusion list are honoured at **crawl** time, so excluded paths are
  never in the database at all — not merely filtered on the way out.
- A search history exists only if the user turns it on, and is one tap to clear.
- Index files are excluded from Android Auto Backup (`android:fullBackupContent` rules), or a
  device restore silently carries another device's filenames onto this one.

### 7.4 Observability, because "search feels slow" is otherwise unactionable

A debug screen (settings → about → long-press build, i.e. findable but not in the way):
index size, node/inner counts per volume, last sweep duration, watch count vs cap, p50/p95/p99
query latency histograms, candidate-set sizes, and the slowest 10 recent queries with their
stage timings. This is the difference between "it feels slow" and "the rerank stage is at
p99 40 ms on 8k candidates because the cap is not being applied".

### 7.5 The benchmark harness is part of the feature

A synthetic tree generator (depth, fanout, name distributions, a pathological *"12,000 files
in one directory"* case) plus a macrobenchmark run in CI. Numbers published in the README
against a named device — R4 applies to performance claims as much as to feature claims.

Regression gates: cold crawl, sweep, p95 keystroke latency, index bytes per 1k files.

---

## 10. What this buys

| | |
|---|---|
| Keystroke → painted | **< 30 ms** warm |
| Matching | fuzzy subsequence, camelCase, acronyms, typo-tolerant |
| Cold crawl, 41k files | 2–6 s, backgrounded, searchable throughout |
| Staleness check per app start | ~3,000 syscalls, 30–60 ms |
| **Stale rows shown** | **Zero** — verification sits between the index and the screen |
| Index size, 41k files | ~17 MB incl. container contents |
| Watches held | ≤ 512 |
| Answers nobody else can | `pkg:` `class:` `perm:` `inzip:` `from:` `dup:` |

---

## 11. Milestone fit

| M | Scope |
|---|---|
| **M1** | Live scan of the current subtree. No index. Ships early, good enough for one folder. |
| **M3** | §2 schema · §3.1 APK metadata (not classes) · §4.1–4.6 · §5.1–5.5 · simple `field:value` |
| **M3+** | JNI `readdir` behind the same seam · §5.2 typo tolerance |
| **M4** | §6.3 saved searches — they are rail entries, so they land with the shortcut work |
| **M6** | §3.1 dex class prefixes · §3.2 archive members — both need the APK/archive providers |
| **M7** | `from:` lights up once the Trawl bridge writes biography rows |
| **M8** | §7.2 per-volume timeouts, when network volumes exist |

---

## 12. Decisions that must hold

1. **Verify-before-display is not a performance knob.** *Amended by §14.3: nothing unverified can be acted on; a row may be seen, tagged Confirming, while its check runs.* If profiling says the stat pass is
   expensive, shrink the verified window — never skip it. Skipping it is how the index starts
   lying, and it is unrecoverable as a reputation.
2. **Retrieve-then-rerank.** Never push fuzzy matching into SQL; never run the scorer over
   more than the candidate cap.
3. **Bundle SQLite.** FTS5 presence and trigram availability cannot be assumed.
4. **`parent_id`, never stored paths.** Moves stay O(1).
5. **Container indexing is a capped, evictable budget** that reports its own coverage — never
   a silent partial answer.
6. **No content indexing.** Different product.
7. **The index is a cache.** It lives in `cacheDir`, it rebuilds itself, and losing it is
   never an error the user has to read.

---

## 13. Reach — which drives a search covers

### 13.1 The fault this section was written for

`SearchScope` is one enum carrying two unrelated questions and one thing that is not a question
at all:

| Member | Actually asks |
|---|---|
| `FOLDER` | how far down the tree |
| `SUBFOLDERS` | how far down the tree |
| `DEVICE` | which drives |
| `PROVENANCE` | which *field* — not a reach at all |

So the chip row reads as a muddle, and the middle of it is the worst case: **"Whole device"
names a set nobody can point at.** It could be internal storage, or every local volume
including the SD card and the root mounts, or everything including the network shares. In
practice it meant "whatever the index happens to hold rows for", which is not a set a person
can reason about and not one that is stable between two launches.

A scope whose extent is unknowable cannot be trusted, and an untrusted scope gets worked
around - the search gets retyped at a narrower rung until the result looks plausible, which is
the behaviour of somebody who has learned the control lies.

### 13.2 The ladder

One **monotonic ladder**, built from where the pane is standing. Every rung is strictly larger
than the one above it and every rung names a set that can be pointed at:

| Rung | In a local folder | In a share | On Home |
|---|---|---|---|
| 1 | This folder | This folder | — |
| 2 | Everything under here | Everything under here | — |
| 3 | *the drive's own label* | *the share's own label* | — |
| 4 | This phone | — | This phone |
| 5 | Everywhere | Everywhere | Everywhere |

Three properties carry the whole design:

- **Rung 3 renders the volume's own label**, not a generic word - `Internal storage`,
  `SD card`, or the share's label. "This drive" in the abstract is the same unpointable set
  that "Whole device" was.
- **Rung 4 does not exist inside a share.** "This phone" from a position on a NAS is either a
  lie or a jump, and both are worse than the rung being absent.
- **Rung 5 is the only rung that touches the network**, so it is the only rung that can be slow
  or partly fail. Keeping that on exactly one rung is what lets rungs 1-4 promise §5.5 latency
  without an asterisk.

**Rung 4 is internal, the card and a stick - never the root mounts.** With root granted,
`RootProvider` publishes `/data` and `/system` as volumes, and they stay out of the rung
deliberately. Somebody searching "this phone" is looking for their own files, and
`/data/app/~hash~/base.apk` ranked beside a photo is the rung's entire value spent. The root
mounts are tickable in the volume sheet (§13.3), which is where a deliberate search of the
system belongs - named, chosen, and not the thing that happens by default.

The label survives that honestly because the rung names the volumes it covers (§13.2, rung 3)
and the sheet lists the rest. A rung that silently covered nine volumes and called itself the
phone would be the unpointable set again; a rung that covers three and shows which three is not.

And wherever they are reached from, **the root mounts are never crawled.** A ticked root volume
is answered by the bounded live walk of §13.6, streaming in as it arrives. `/data` is tens of
thousands of files that churn constantly and that nobody searches by name; crawling it would
rebuild the unbounded-crawl battery defect inside the feature that was supposed to end it.

`SearchReach.scopesFor` is already the single declaration point for what a pane offers; it
becomes `ReachLadder.rungsFor(cwd, volumes, remotes)`, pure, and the chips, the scope sheet,
the empty-state note and the request builder all read it. One function with a test, not a rule
each surface remembers - `check-reach.mjs` fails the build on a surface that builds its own
list.

### 13.3 Reach is a value, not an enum

```kotlin
sealed interface Reach {
    data object Folder : Reach                            // rung 1
    data object Subtree : Reach                           // rung 2
    data class Drives(val keys: Set<VolumeKey>) : Reach   // rungs 3 and 4
    data object Everywhere : Reach                        // rung 5
}
```

An enum cannot express **two drives but not the third**, and that is the case the ladder alone
misses:
from Home there is no folder to stand in, so rung 3 is unavailable, yet one specific drive is
still a thing to search. The ladder is the fast path; a **Drives…** chip at the end of the row
opens a sheet listing every volume - internal, SD, USB, root when granted, and each share - as
checkboxes. A chip rather than a long press: the sheet is the only way to reach the root mounts,
and a capability whose only door is a hidden gesture is a capability nobody finds. The chip is
also where a hand-picked set shows as the reach in force, so no rung is ever lit for it wrongly. The sheet writes `Drives(...)`; the ladder writes the same type with one
or all local keys in it.

`Reach.prune(available)` is pure and mandatory on read: a reach naming a card that has been
pulled or a share that has been deleted must narrow, visibly, rather than return nothing.

### 13.4 Provenance is a field, not a reach

`from:` moves out of the reach row and in with `pkg:` / `class:` / `inzip:` / `dup:`, where the
other field filters already live. It never constrained which drives were searched; it
constrained which column was matched. Having it in the reach row is why that row looked like
four unrelated options instead of one ladder.

This is pure subtraction - §6.1 already parses the grammar, and §13.2's `rungsFor` loses the
`provenance` parameter `scopesFor` carries today.

### 13.5 Grouped by drive, once a share is in reach

A flat list reranked by §5.1 mixes a file on internal storage with a file four seconds away
over Wi-Fi, and nothing on the row says which. The cost of a hit is a property of its drive, so
the drive is the grouping - but only where the costs actually differ.

**Local volumes are never grouped.** Internal, the card, a stick and the root mounts open at the
same speed and never fail differently, so splitting them into four short lists costs the ranking
and buys nothing - the path under each name already says which one a file is on.

**Groups appear exactly when a share is in reach**, which makes the headers themselves the
signal: seeing them means the network is involved, and that part of this answer is slower and
can fail. The phone is one group, first; shares follow in `lastUsedAt` order.

#### The header

One 32dp row. Not a card - no fill, no border, no elevation. A group whose header needs a box is
a group that should not have existed.

| Part | |
|---|---|
| Glyph | the volume's own icon, 16dp, `fg3` - the same one the storage card and the rail use, so a drive is recognised by shape before the label is read |
| Label | 12sp semibold, tracking +0.4, `fg2`. Never upper-cased: a share's name is somebody's own wording and `SPARE TABLET` is a different word |
| Count | a bare number after a middle dot, `fg3`. No pill, no badge |
| Rule | a 1dp hairline in `line`, from after the count to the right edge, vertically centred |
| State | replaces the count when there is one - `accent` while working, `warn` when it failed, with a 10dp indeterminate arc for searching and no arc for anything else |

The hairline is the only ornament, and it is what makes the row read as a section instead of as
another list item. Headers are sticky while their group scrolls, so which drive a result belongs
to is answerable at every scroll position without going back up for it.

The states, in the words they appear in:

```
Internal storage · 2,314
Workshop NAS · searching…
Spare tablet · unreachable, last seen on 192.168.1.9
Backup drive · walking, 1,204 folders
```

**A drive that cannot answer says so.** Silence from an unreachable share is indistinguishable
from no matches on it, which is the one answer a search must never fake.

### 13.6 A share is walked live, and indexed only where that is turned on

Default for every network drive: **no index rows, ever.** A search that reaches it does a
bounded, cancellable live walk with a visible folder counter, and partial results beat late
results (§9.2).

Per-share opt-in lives in the share's row menu - *Keep an index of this share* - because the
case for it is real and narrow: walking a 222 GB share per keystroke is hopeless, and somebody
who searches one NAS daily should be able to pay for it once. Opting in is what `CrawlFrontier`
exists for. A local bounded pass can afford to restart at the roots when it is truncated; a
remote pass cannot, because Wi-Fi drops, the phone sleeps and the share is three orders of
magnitude larger than the budget. **The frontier's first two consumers are per-share indexing
and the manual Index-now run.**

What a remote index holds, even when turned on:

| Stored | Never stored |
|---|---|
| name, `name_fold`, size, mtime, is_dir | content hashes (§6.4 duplicates) |
| directory `dir_mtime` for §4.2 validation | container interiors (§3) |
| | thumbnails |

Fact extraction and hashing open files. Opening every file on a 222 GB share over Wi-Fi is the
unbounded-crawl battery defect again with a worse constant, so the opt-in buys names and
nothing else.

### 13.7 What invalidates a remote's rows

The index stores `(vol_id, parent_id, name)` and never a full path (§2.2), and `volume.uuid`
for a share is its root VPath - which carries the **connection id**, not the host. That is
already deliberate: `NetConnection.id` appears in the path precisely so that renaming a server
or changing its address does not invalidate the bookmarks and shortcuts pointing into it. The
index inherits that property for free.

| Change to a saved remote | Effect on its rows |
|---|---|
| Label renamed | **None.** The label is in neither the path nor the uuid. |
| Address changed; `altHosts` added; `lastGood` moved; `deviceId` learned | **None.** Routing only. |
| Password, private key, port, timeout, passive, read-only, anonymous | **None.** |
| `startPath` | **None.** A landing folder, not a path prefix. |
| **SMB `share`, or WebDAV base path, edited** | **Drop every row.** `/<id>/notes.txt` now resolves to a different file - the rows are *wrong*, not stale. |
| **Protocol changed on an existing entry** | **Drop every row.** The scheme in the volume root no longer matches. |
| Entry deleted | **Drop the volume row and cascade.** |
| The same server added again | New id, so it starts empty. Correct, and cheap. |

The two "drop" cases are the whole hazard, and at the save site they are indistinguishable from
the harmless ones - which is why the decision is a pure function rather than a rule inside a
save handler:

```kotlin
/** True when this edit changes what an already-stored path MEANS, rather than how it is reached. */
fun meaningChanged(old: NetConnection, new: NetConnection): Boolean
```

Exhaustive over every field of `NetConnection`, so adding a field to that class forces a
decision here instead of silently defaulting to "harmless". True bumps the volume's epoch,
which drops its rows.

**Deletion currently leaks.** `NetConnections.delete(id)` rewrites the connection list and
nothing else, so a removed share leaves its volume row, its nodes and its cached thumbnails
behind - rows for a drive that cannot be reached, mounted, or explained. Needed: an orphan
sweep on delete, and a sweep at startup for volumes whose uuid names no saved connection.

**Trust, on display.** A remote row cannot be verified cheaply, so §4.3's guarantee is met the
other way: remote hits are emitted as `HitState.CONFIRMING`, and a tap stats the file before
opening it, reporting that it is no longer there rather than failing inside a viewer.
`HitState` already carries both states; nothing new is needed.

### 13.8 A latent defect in volume attribution

```sql
SELECT id, root FROM volume WHERE ? LIKE root || '%'
```

A bare string prefix match. If one volume root is a prefix of another, every row under the
longer one is attributed to the shorter drive - wrong group, wrong label, wrong reach filter.
It is not firing today: connection ids are `"n"` plus eight base36 characters of
`currentTimeMillis` and stay nine characters long into the 2060s, and no two real local volume
paths prefix each other. A per-share index is exactly the feature that would surface it. The
fix is one line - match `root || '/'`, with the root itself handled as an equality - and it is
worth taking before the table holds remote volumes.

### 13.9 Battery and network rules

1. **Rung 5 is never the default.** Home opens on *This phone*, which is free and instant.
   Reaching the network is one tap and it is a deliberate one.
2. **No indexing pass over a share on mobile data**, and none when the share was reached
   through an address that is not on the local network. `Endpoints` and `lastGood` already know
   which route answered.
3. **A share's indexing pass is charger-and-Wi-Fi work**, on the same 12h maintenance tier as
   §7.5, never on open.
4. **A live walk is bounded by the search, not by a budget.** It stops when the query changes,
   the pane closes or the walk is cancelled - and it reports how far it got.
5. **The root mounts are never crawled, and never in a rung.** They are reached only by being
   ticked in the volume sheet, and answered by a live walk. Nothing writes `/data` into the
   index.

### 13.10 Candidate sources, and what each is for

Reach decides *which drives*; this decides *who answers for them*. Retrieve-then-rerank (§5.1)
is unchanged - these are candidate generators feeding the same scorer.

| Source | Answers for | Cost | Why it is in the list |
|---|---|---|---|
| This index | every local volume; shares that opted in | in-process SQLite, sub-ms | the only source that can answer `pkg:` `class:` `perm:` `inzip:` `from:` `dup:`, and the only one holding frecency |
| `MediaStore` | scanned shared storage | Binder round trip, single-digit ms to tens | **complete from a cold start, at zero battery cost** |
| Live walk | the current subtree; any share with no index | seconds, bounded | never stale, and the only source for a share that opted out |

**On `MediaStore` being "media only" (§1).** That row overstated the case and is corrected
here. Measured on a 215 GB phone: `MediaStore.Files` held **11,046** rows, **4,899** of them
non-media, against this index's **7,736**. For scanned shared storage it is the *more* complete
of the two, and the system maintains it for free. What it cannot see is the part that matters
for a file manager, and the list is specific rather than "incomplete":

- network shares, at all;
- anything outside shared storage - `/data`, `/system`, the root mounts;
- trees exposed by *other apps'* document providers;
- `.nomedia` directories and dot-directories, which the media scanner skips by design and a
  file manager must show;
- archive and package interiors (§3);
- and none of this index's own columns: frecency, duplicates, provenance, stable shortcut ids.

So `MediaStore` is promoted from a change *hint* (§4.5) to a first-class **candidate source**,
and this index is demoted from a mirror of the filesystem to **the sidecar holding what only it
can know**. Search latency is unchanged - the same scorer runs over the merged candidates - and
the crawl that used to maintain the mirror is what paid for the demotion.

### 13.11 Decisions that must hold

Continuing §12.

8. **Every rung names a set that can be pointed at.** A scope whose extent depends on what a
   crawl happened to reach is not a scope.
9. **Exactly one rung touches the network.** Rungs 1-4 keep §5.5 without an asterisk.
10. **A drive that cannot answer says so.** Silence from an unreachable share must never be
    rendered as "no matches".
11. **Network drives are not indexed unless that share was turned on**, and a share's index
    holds names only.
12. **What a stored path MEANS is a pure function of the saved remote**, and the two edits that
    change it drop rows rather than refresh them.
13. **The root mounts are reachable, never automatic, and never indexed.** A search of
    `/data` is a thing to ask for explicitly; it is not what "this phone" means.
14. **A group header appears only when a share is in reach**, so the header's presence is itself
    the statement that part of the answer is slower and can fail.

---

## 14. Index-led search, and keeping the index fresh without crawling

### 14.1 The fault this section was written for

Measured against the 0.1.19 release on the same phone, searching "minecraft" from Home: the
release answered instantly with only real matches and missed files the crawl had not reached;
the next build answered one junk row first, then nothing visible for seconds, then real
matches mixed with rows that had none of the word in them. Five causes, each read in the code:

| | Cause |
|---|---|
| 1 | **The whole-device walk in 0.1.19 never ran.** Tabs were restored before the drives loaded, so every restored pane held an empty volume list and the walk on Home had no roots. The release was index-only by accident - fast, strict, and incomplete. |
| 2 | **Fixing that list made the walk real** - over every drive, network shares included, in **one breadth-first queue**. Internal storage waited behind Wi-Fi round trips. |
| 3 | **The walk admitted scattered subsequences.** `7_6M_views_51K_reactions_EPIC_spinning_backfi…` contains m…i…n…e…c…r…a…f…t in order and was the first hit for "minecraft". The index could not do this: its SQL demands the word as one contiguous piece before the scorer sees a row. |
| 4 | **"Confirming" meant "the crawl has not re-passed this row"**, not "not yet checked". Every index hit was already checked before display; the label waited on a crawl, and an unbounded crawl on every open kept it up for minutes. |
| 5 | **New hits published every 24 rows**, so a slow trickle sat invisible for seconds with nothing saying the search was still working. |

### 14.2 One job per source

| Source | Job | Never |
|---|---|---|
| **The index** | The answer. In-process, sub-millisecond, first paint inside §5.5. | Waits on anything to paint. |
| **MediaStore** | Feeds the index: the first build is one query over `MediaStore.Files` (11,046 rows on the phone measured, in seconds) and later deltas by generation (§14.6). | Queried per keystroke. A Binder round trip per letter typed is slower than the table it would replace. |
| **The walk** | Fills gaps: a folder or subtree reach, a drive the index holds nothing for, a folder the index knows changed (§4.2). | Re-reads what the index already answers. |

This supersedes §13.10's "MediaStore as a candidate source". Querying it live was the weaker
design - it would have traded the index's in-process speed for completeness. Feeding it into the
index buys the completeness and keeps the speed.

**One walker per drive, run in parallel.** A single queue over every root is how internal
storage came to wait on a NAS; per-drive walkers make local results independent of the slowest
share by construction.

### 14.3 Confirming means "not checked yet", and checking starts at once

- Index rows paint immediately, tagged **Confirming**, and can be tapped.
- A checker runs beside the search, in rank order with bounded concurrency, so the rows at the
  top of the list - the ones on screen - are checked first. Each flips to available within
  milliseconds on local storage, or leaves the list if the file is gone.
- **A tap on a row still Confirming checks it first.** A stale row can be seen for a moment; it
  cannot be opened, shared, or acted on.
- The tag is tied to the row's own check and to nothing else. It never waits on a crawl again.

**Decision #1 in §12 is amended accordingly**, deliberately. It used to forbid an unchecked row
reaching the screen; it now forbids an unchecked row being **acted on**. The cost is a removed file visible for
the few milliseconds its check takes; the gain is that first paint waits on nothing.

### 14.4 One relevance gate for every source

A name is admitted only if **each word of the query** is, against that name:

1. a **contiguous piece** of it (`craft` in `Minecraft_world.zip`); or
2. a **word-start acronym** of it (`mc` against `Mine Craft`, `MineCraft`, `mine-craft`); or
3. within the **typo budget of one word in it** - one edit up to six letters, two beyond -
   measured against the name's own words, never against the whole name.

**Scattered letters alone never admit.** The subsequence scorer stays, for ranking; it no
longer decides membership. The gate is one pure function both the walk and the index rerank
call, so a query cannot behave differently depending on which source happened to answer.

Order of rank: exact name, starts with, starts a word, contains, acronym, typo - then
frecency (§5.4), then the subtree being stood in. **The matched characters are highlighted on
every row**, so why a result is there is always visible.

`RelevanceTest` carries real junk as fixtures, starting with the one above: "minecraft" never
admits `7_6M_views_51K_reactions_EPIC_spinning_backfire.jpg`.

### 14.5 Hidden folders are the search bar's own switch

A toggle **in the search bar**, persisted on its own, separate from the browser's show-hidden:
**results from hidden folders**, off by default.

Off means a result is excluded when its own name **or any folder above it** starts with a dot.
The index used to test only the row's own name, so a file inside `.hwthumbnails` passed with
hidden files off - a defect, fixed here for both sources. On means they are searched like
anything else.

Its own switch because the two questions differ: whether to *see* dot-folders while browsing is
not whether thumbnail caches belong in an answer to a search.

### 14.6 A fresh index without crawling

| When | What keeps the index current | Cost per change |
|---|---|---|
| **Filet in the foreground** | inotify on hot folders (§4.1) **and** a MediaStore `ContentObserver` registered in-process. Each change re-reads **only its own folder**, coalesced over about two seconds. | One `readdir`. |
| **For a while after Filet is minimised** | The same listeners, kept for the window set in Settings - **Off, 2, 5 or 10 minutes, 10 by default** - and then released. Nothing is scheduled; nothing can wake the app. | One `readdir`. |
| **Coming back** | `MediaStore.getGeneration` against the generation last seen: the rows that changed while Filet was away, and only their folders re-read. | One query, then one `readdir` per changed folder. |
| **What MediaStore cannot see** | `.nomedia` trees, dot-folders, granted trees - caught by the directory-mtime check (§4.2) when the folder is opened or searched. | One `stat`. |

The retired Tier 2 (`MediaChangeJobService`, explained in `IndexCoordinator`) drained a day's battery because
each media change woke a twenty-second crawl of every root. This tier answers each change with
one folder read. That difference in shape is the whole case for bringing a listener back.

The window is a number the setting shows and the code obeys. An early release "after two quiet
minutes" was considered and rejected: a setting that silently ends sooner than it says is a
setting that lies.

The bounded pass on open (§13.9) shrinks to a fallback for the cases above that cannot answer -
below API 30, or a generation delta too large to be worth reading row by row.

### 14.7 Shares are indexed from what searches already read

Amends §13.6. A share walked live by a search writes what that walk listed into the index, so
the next search of the same folders is instant. **No traffic is added to do it** - the walk has
already listed those folders over the network, and writing names into SQLite costs nothing on
the wire. What §13.6 ruled out, and still rules out, is **crawling** a share in the background.

The per-share switch changes meaning with it: **Keep an index of this share** now asks for a
*complete* index, built on charge and on Wi-Fi with the crawl frontier so an interrupted pass
resumes rather than restarting at the root. That is the frontier's first consumer.

Rows from a share are always emitted Confirming, and checked over the network like any other
(§14.3). §13.7's invalidation rules apply unchanged.

### 14.8 Feedback that never lies

- Results publish **on a clock** - about every 100 ms when anything is new - never by count.
- **One thin progress line** under the search field, up until **every** source is finished:
  the index, the checker and each walker. A spinner that stops while results are still
  arriving is the thing most worth not doing.
- A status line under the chips naming what is running:
  `Index · 14  ·  checking 3  ·  reading C drive, 1,204 folders`.
- A share's header counts the folders its walker has read (§13.5).

### 14.9 Decisions that must hold

15. **The index answers; the walk fills gaps.** A walk is never a co-equal source for a reach
    the index covers.
16. **One walker per drive.** No queue is shared between a local volume and a network one.
17. **Membership is decided by the relevance gate, ranking by the scorer.** Scattered letters
    never admit a row, from any source.
18. **Confirming is a property of the row's own check**, and an unchecked row cannot be acted on.
19. **Nothing indexes a share in the background unless that share was asked for a complete
    index.** Indexing what a search already read is not background work.
20. **The listener's window is the number in Settings**, and nothing ends it early or extends it.
21. **A pass deletes only what it walked.** A folder handed to the crawl is walked as a folder
    of the volume holding it; it never becomes a volume, never advances the global generation,
    and its sweep reaches nothing outside it (§14.10).
22. **Unreachable is not gone.** A check that cannot reach a share leaves the row Confirming;
    only a check that reached the drive and found nothing removes it, and the index forgets it
    then.

### 14.10 What building this turned up

**The sweep reached the whole database.** The end of a complete pass deleted every row older
than its generation, everywhere. Two callers hand the crawl a folder rather than a volume - the
pass that runs when a folder is walked into, and the hot-folder watcher when a folder on screen
changes. The folder was registered as a volume of its own, walked in well under its budget, and
then every row on every other drive was deleted for not carrying the folder pass's generation.
Walking into `Download` emptied the index of everything outside `Download`; the next bounded
pass rebuilt part of it, and the next folder opened emptied it again. On the phone this presented
as the 0.1.19 index being strict, fast, and missing files a pass had indexed an hour earlier.

The fix is a classification before the walk, in `CrawlScope`:

| Root | Walked as | Sweep | Completes the volume |
|---|---|---|---|
| a volume's own root | the volume | that volume's rows | yes, under `complete:<uuid>` |
| a folder in a volume the index holds | a subtree of it | rows under that folder | no |
| neither | not walked | - | - |

The volumes the defect had already registered are dropped on the next pass over the phone's
drives. Every test passed throughout, because `SqliteIndex` runs only on a device and the sweep
was correct in isolation; `tools/check-sweep.mjs` now fails the build if a generation sweep
loses its scope or the global stamps move out from behind the volume guard.

**An unreadable folder was an empty one.** A listing that failed was skipped without being
stamped, so the sweep deleted everything under it. A local folder that cannot be read is now
carried forward as last known; on a share, the root refusing or three refusals in a row end the
pass where it stands, frontier intact, rather than completing it unread.

**Completion is per volume.** A share's complete pass and the phone's are separate passes, and
one finishing says nothing about the other. The generation a volume last completed at is kept
for it alone, which is also what decision 15 reads: `FileIndex.completeVolumes` is the set of
drives the walk leaves to the index.

**A typo had no source left.** The gate admits a word within its budget, but retrieval is strict,
so "minecarft" retrieved nothing and the gate never met `Minecraft_world.zip`. The walk used to
hide this by reading every name on the phone. With the walk standing down for covered drives,
the index asks again when the strict answer is thin: every trigram of each word of four letters
or more, ORed, ranked by FTS5's bm25 so the names sharing the most and rarest trigrams come
first (`TypoRetrieval`). SQL only narrows; the gate still decides.

**A share asked for a complete index is walked on Wi-Fi, on charge, alone.** The bounded pass on
open and the twelve-hour sweep are the phone's; `ShareIndexWorker` walks each opted-in share in
a pass of its own, so each keeps its frontier, under an eight-minute budget that resumes rather
than restarts. Index now walks the phone and then each share in turn.

**Every pass takes its own stamp.** A pass used to write `META_GEN + 1`, and only a pass over
whole volumes advances `META_GEN` - so every folder pass between two complete passes wrote the
same number, and a file deleted between two of them was never older than the sweep that should
have removed it. Passes and listings now take a stamp from a counter that only goes up;
`META_GEN` keeps its meaning as the newest generation a complete pass wrote. A listing is
dropped while a crawl holds the index, and while a share has a complete pass unfinished, so it
can never outlive a sweep it raced.
