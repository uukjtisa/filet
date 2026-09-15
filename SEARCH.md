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
| Provenance — source URL, origin app (PLAN §5.1) | Cloud / remote index |
| Type, size, mtime, extension, duplicates | |

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
| `MediaStore` as the index | Media only, incomplete for arbitrary files, and **you do not control invalidation**. Cannot answer "where did my .apk go". Useful as a *hint* — see §4.5. |
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

1. **Verify-before-display is not a performance knob.** If profiling says the stat pass is
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
