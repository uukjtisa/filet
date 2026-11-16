# Folder sizes: fast, live, cheap and ahead of the question

Status: **plan, approved 2026-11-16**. Nothing here is built. Read against the tree at `6f2d7dd`.

Scope: one size engine behind two surfaces, Row details' folder total and Disk map (issue #48). It also fixes the open bug "Disk map: Walk again walks the whole drive". The Disk map's look (strip, lit cards, tree, By type screens) belongs to `filet-storage-redesign-plan.md`. This plan owns the figures those screens read.

Paths: `B/` = `app/src/main/java/dev/niccc2007/filet/browser/`, `S/` = `.../filet/storage/`, `IDX/` = `core-index/src/main/java/dev/niccc2007/filet/index/`.

## 1. Diagnosis

### 1.1 How a row gets its figure today

1. `MainActivity.kt:103-114` provides `LocalFolderBytes` as a wrapper over the process-wide `FolderMeasure` (`B/FolderMeasure.kt:337-343`, which runs in `graph.scope`).
2. Each folder row starts its own `produceState` loop (`B/RowViews.kt:398-422`). It calls `of(path)`, calls `measure(path)` if the answer is not final, sleeps 1,200 ms, and repeats up to 25 times (`RowViews.kt:721`, `:731`).
3. `of` is `FolderMeasure.total` (`:94-105`), which takes the newer of two caches (`B/FolderTally.kt:206-211`):
   - a finished walk from this process (`confirmed`, an LRU of 64, `FolderMeasure.kt:54-56`, `:316`);
   - the index's sum for the subtree (`indexTally`, `:192-208`).
4. The index sum streams every indexed row under the folder through a recursive query (`IDX/SqliteIndex.kt:1364-1385`) into a `TallyBuilder`. It is memoised per crawl generation in a second LRU of 64.
5. `measure` puts the folder in a FIFO queue of at most 48 (`FolderMeasure.kt:126-134`, `:335`). One worker walks one whole folder tree at a time (`:136-153`, `:262-293`), listing every directory with `vfs.list`.

### 1.2 Why the figure is slow to arrive

- **One whole tree at a time, first come first served.** A 39 GB `Download` ahead in the queue holds up twenty small folders behind it. Rows that scrolled away keep their place (`FolderMeasure.kt:117-120`), so the rows on screen after a fling wait for the rows flung past.
- **Requests are dropped without notice.** Past 48 pending, `measure` returns (`:129`). The row re-asks only while its 30-second poll lasts, then stays on a dash or a floor until it is scrolled away and back.
- **The result is seen late.** A finished walk reaches the row at its next poll, up to 1.2 s later.
- **No progress is shown.** `total` returns `Unknown` before it checks whether a walk is running (`:95`). A folder the index has not reached shows a dash for its whole walk. Where the index has a floor, the row shows that floor, not the walk's running count.
- **Nothing is shared between levels.** Walking `Download` yields one number. Stepping into it finds every child unmeasured and walks each again, so a file at depth d is statted up to d times.
- **Nothing survives the process.** `confirmed` is memory only, so every cold start begins from index floors.
- **Each stat is a round trip to another process.** Shared storage is a FUSE mount, so the number of calls is the cost (`Stats.kt:21-34`: 37 ms to read a 227-entry directory, 626 ms to describe its rows before the one-stat fix). The same work is billed to the media provider's battery (`media/MediaAnnounce.kt:36-39`).
- **The cache thrashes in a big folder.** With more than 64 subfolders, both LRUs evict what was just measured, and scrolling back re-aggregates and re-walks.

### 1.3 Why it is slow to update, and sometimes wrong

- **A finished walk is never invalidated.** `worthWalking` refuses any folder with a complete entry in `confirmed` (`:156-161`), and the row stops polling at `Settled` (`RowViews.kt:413`). Copies, deletes and downloads inside a measured folder never move its figure for the life of the process. The process lives for days behind the playback service.
- **The row is not keyed on the folder's modified time** (`RowViews.kt:398`), so even a re-listed row does not ask again.
- **Bug identified by reading, not reproduced: a settled figure degrades to a floor.**
  - When a full index pass finishes after a walk, `pick` prefers the index (`FolderTally.kt:209`) and `total` reports `AtLeast` (`FolderMeasure.kt:99-103`).
  - The row asks for a walk and `worthWalking` refuses it.
  - The row then shows `703 MB+` where it showed `39 GB`, and the poll gives up.
- **The index figure is a floor by construction.** Unasked passes are 25 s and restart from the roots (`index/IndexCoordinator.kt:194-196`, `:534`), so the cold end of a large tree is never reached. This is the 703 MB against 39 GB case recorded in `B/FolderBytes.kt:17-24`.
- **The Details sheet and the multi-select card bypass the queue.** `FolderFacts` starts its own walk (`B/FolderDetails.kt:48-51`). `PropertiesManyDialog` starts one per selected folder, all at once (`B/Dialogs.kt:541-545`).

### 1.4 What is cached, and what invalidates it

| Cache | Where | Invalidated by |
|---|---|---|
| Finished walks, 64 | `FolderMeasure.kt:54` | Eviction, or a new walk from the Details sheet. Never by a change. |
| Index sums, 64 | `FolderMeasure.kt:66` | Crawl generation; 4 s while a crawl runs (`:326`). |
| Disk map, per drive | `S/DiskMaps.kt:21` | Filet's own deletes (`BrowserViewModel.kt:1764`), Walk again, process death. |
| Item counts, folder peeks | `B/FolderCounts.kt:38`, `B/FolderPeek.kt:62` | Folder modified time. These are correct, and a second and third reader of the same directories. |

### 1.5 What triggers recomposition and frames

- The row reads `total` in the body of `FileRowBody` (`RowViews.kt:398`, used at `:440-479`). Every change recomposes the whole row.
- **Standing cost, by reading:** `rememberRowPulse` runs an infinite transition for as long as the activity is composed, whether or not any row is pending (`MainActivity.kt:118`, `ui/RowPulse.kt:121-133`). A row that can never settle reads that clock in its draw pass (`RowViews.kt:599-605`, `:677-683`). This covers a network folder, indexing off, or a request that was dropped. A list of such rows redraws at the display rate for as long as it is on screen.
- The walk queue is not tied to the lifecycle. It keeps reading storage after Filet leaves the screen (`FolderMeasure.kt:138`).

### 1.6 The analyzer

- **A second engine.** `DiskMap`, `SizeMap` and `StorageWalk` share nothing with `FolderMeasure`. One folder can show two figures, from the drive's walk on the row and from a separate walk in Details.
- **Every process re-reads the whole drive.** The map is memory only and is built on first request (`DiskMaps.kt:24-26`). It is started from composition (`B/PaneView.kt:246`), and only once Disk map is turned on.
- **Depth first from the drive root** (`S/StorageWalk.kt:49-50`, `:68-73`). The folder on screen can be reached last.
- **Bug, confirmed by reading: Walk again is drive-scoped.** The button calls `map.walk()` (`S/DiskMapViews.kt:183`), which clears the whole map (`DiskMaps.kt:71`) and walks from the drive root (`:77`). The strip's "files read" is the drive-wide counter (`DiskMapViews.kt:86`, `:162-163`, `S/SizeMap.kt:45`), so a folder of a few files reports thousands. Every unrelated folder also drops to unread.
- **Only deletes reach the map.** `DiskMaps.removed` has one caller. Moves, copies, extracts and outside changes wait for Walk again.
- **Bug, by reading:** `SizeMap.removeFile` subtracts from ancestors that may not have counted the file yet (`SizeMap.kt:212-221`). The clamp at zero hides it and the total stays low.
- **Race, by reading:** `walk()` cancels the old job without waiting for it. A batch in flight can land in the cleared map, and the old job's `finally` can mark the new walk as not running (`DiskMaps.kt:69-82`, `StorageWalk.kt:94-103`).
- **Main-thread work remains.**
  - Each tick re-sorts the folder on the main thread (`B/PaneController.kt:1476-1481`, `:1504`, `:1429-1443`).
  - `render` can read under the walk's lock (`:1488-1498`).
  - `removed` runs on main.
  - Each tick prints every row's path again (`DiskMaps.kt:102`).
- **Largest files is lossy.** It is a drive-wide top 2,000 (`SizeMap.kt:262`). A folder whose files are not in it reports "No files here." (`DiskMapViews.kt:232-236`), and deletes are never backfilled.
- Turning Disk map off does not stop the walk (`BrowserViewModel.kt:332-335`).

## 2. The design

### 2.1 Rules

1. **One engine, one figure per folder.** Rows, Details, the multi-select card, Disk map and the compress estimate all read the same record.
2. **The unit of work is one directory listing, never a tree walk.** A subtree total is arithmetic over stored directory records.
3. **A change replaces one directory's record and bubbles the difference to its ancestors.** Nothing below or beside it is read again.
4. **A listing anybody pays for is offered to the engine.** Each folder is read once, by whoever gets there first.
5. **Figures are ready before they are asked for.** Records persist, changes are pushed in, and a paced sweep fills what was never measured.
6. **No work without a reader.** Nothing runs while Filet is off screen, except a measurement started by hand, which carries a Stop.

### 2.2 The record and the roll-up

One record per directory, in a private SQLite file (`filet-sizes.db`, platform SQLite, the app's own database folder, never a browsed path).

- **Own facts**, from one listing:
  - bytes and count of direct files, count of direct folders;
  - files with no reported size;
  - bytes and count per extension (capped at 24 per folder, the rest under "other");
  - the folder's modified time and inode, and when it was listed.
- **Subtree facts**, maintained incrementally: bytes, files, folders, folders beneath not yet listed (`open`), folders beneath that refused a listing (`blocked`).
- **Large files**: every direct file of 1 MiB or more, with name, bytes and extension. This table serves Largest files and the per-type file lists.

Records are keyed by parent id and name, as the index keys its nodes, so a move is one re-parent plus two bubbles. Kinds are derived from the extension through `FileTypes` when read, so the type table stays the only place that decides them.

Replacing a record computes `new own − old own` and adds it to every ancestor. Deltas are held in memory and flushed in one transaction every 250 ms, so a first read of a drive does not write six rows per directory. The same flush moves the live figures. A single writer thread applies every change. Workers only list and compute.

A pure in-memory `SizeStore` with the same contract backs the tests. It also holds the schemes that must never touch disk (section 2.8).

### 2.3 States, and what each one promises

`FolderTotal` gains one state. Every exhaustive `when` over it then refuses to compile until decided.

| State | Promise | Row |
|---|---|---|
| `Unknown` | No record and nothing will run unasked. | Still dash, no breath. |
| `Confirming(bytes)` | A floor; folders beneath are being read now. | `X+`, spinner, the count running. |
| `AtLeast(bytes)` | A floor; nothing is running (stopped, a folder refused, files with no size). | `X+`, steady. |
| `LastKnown(bytes, checking)` — new | Complete when measured; not yet re-checked this session. | The figure, breathing while `checking`. |
| `Settled(bytes)` | Every folder beneath is listed, and each listing was checked this session against the folder's time and inode, with no change source naming it since. | The figure. |

`wantsWalk` goes. Rows no longer ask for work.

The honest limit of `Settled`: a file rewritten in place to a new size, by another app, in a folder off screen, that the media store does not report. Two things bound it. The sweep re-lists (rather than stat-checks) any record older than 14 days, and a hand-started re-measure re-lists everything.

### 2.4 Staying live

Each source is one case of a `ChangeSource` enum. An exhaustive `ChangeRule.action(source)` decides what it does.

| Source | Reach | Action | Hook |
|---|---|---|---|
| Filet's own writes | Exact paths, any backend | Re-list the parent directory | One `graph.wrote(paths)` door replacing the seven `MediaAnnounce.announce` call sites |
| `Vfs.onRelocated` | Renames and moves by Filet | Re-parent the subtree | `Vfs.kt:111` |
| Pane listing | The folder on screen | Replace its record, free | `PaneController.kt:504`, `:587`, `:826` |
| Index crawl, `refreshFolders` | Whatever the index lists | Replace records, free | New `onListed` callback on `SqliteIndex` |
| inotify | The folder on screen and its visible folder rows, within `HotWatcher`'s cap of 96 | Re-list that directory | `FiletApp.kt:531-555` |
| Media store delta | Any depth on shared storage, any app; adds and modifications | Re-list each changed file's folder | A `MediaChangeFeed` split out of `MediaWatch`, so it works with indexing off |
| Check pass | Deletes by other apps, deep | One stat per folder beneath the visible rows; re-list where the time moved | Engine |

The check pass is rate-limited by a pure `Recheck.due(folders, checkedAt, now, hint)`. A small folder is checked on every open. A whole drive is checked at most every half hour while on screen, sooner when the media observer fires with no delta to explain it.

A check costs one stat per folder, not per file. That is about 1,800 stats for a phone of 40,000 files (the ratio recorded in `IDX/IndexDb.kt:255-258`).

A re-list is idempotent: it replaces the record and never subtracts. The delete-during-walk undercount therefore cannot occur.

### 2.5 Proactive order

A pure `SizeQueue` orders directories, not trees.

1. `EXPLICIT`: a re-measure started by hand.
2. `VISIBLE`: folder rows on screen, newest demand first, round-robin between rows. Small folders settle in the first rounds while large ones show a growing floor.
3. `NEAR`: the rest of the open folder below the fold, and the siblings of its parent chain.
4. `PLACES`: bookmarks, drive roots, recent folders.
5. `SWEEP`: the rest of each local drive, in the order never measured, then hinted, then oldest checked.

The frontier is the set of unlisted records, so a sweep interrupted at any point resumes where it stopped.

The list declares demand once. `FolderBody` reports its visible folder keys when a scroll settles. Rows are pure readers. Withdrawing the demand (tab switch, pane closed, app stopped) removes the work.

When navigation starts, the pane primes the engine with one query for all children of the folder, in parallel with the listing. Rows then find their figure in memory on their first frame.

On return to the app, the media delta is read once, and the folders changed while away are corrected before anybody navigates to them.

### 2.6 Pace: concurrency, priority, heat, battery

A pure `SizePace.of(conditions)` returns workers, duty and the allowed classes. It is exhaustive over a `Thermal` enum.

| Condition | Result |
|---|---|
| A list is scrolling, or a pane listing is in flight | No new listings; memory reads only. |
| Normal, on screen | 2 workers for `EXPLICIT` and `VISIBLE`; 1 worker at 20% duty for the rest. |
| Battery saver, or under 20% and not charging | `EXPLICIT` and `VISIBLE` only, 1 worker. |
| Thermal `MODERATE` | `VISIBLE` only, 1 worker, 25% duty. |
| Thermal `SEVERE` or worse | `EXPLICIT` only. |
| No activity started | Nothing; the current directory finishes and deltas flush. |

Signals: `PowerManager` thermal status listener (API 29 and later, `UNKNOWN` below), `isPowerSaveMode`, `BatteryManager`, and the existing activity callbacks (`FiletApp.kt:694-715`).

Workers are two named threads at `THREAD_PRIORITY_BACKGROUND`, which also moves them to the background CPU set.

The engine schedules no job, alarm or wakelock, so Doze has nothing to defer.

### 2.7 Delivery to the screen

- `watch(path)` is a `StateFlow` held only for paths with a collector.
- The row collects in the leaf that prints the figure (`DetailLine`, `SideCell`), not in `FileRowBody`.
- The flow is deduplicated on the printed string, so a running measure recomposes one `Text`, at most four times a second.
- The pulse clock runs only while a breathing row is composed (`PulseGate`).
- No engine call on the main thread touches the database or storage.

### 2.8 Backends

Each provider declares a `Sizing` value, with no default on the interface, so a new provider cannot be forgotten. `SizePolicy.of(sizing)` is the one exhaustive `when`.

| `Sizing` | Providers | Measured unasked | Checked unasked | Persisted | Notes |
|---|---|---|---|---|---|
| `LOCAL` | `local` | Yes, all classes | Yes | Yes | The full design. |
| `TREE` | `saf` | Visible rows only, 1 worker | By time where the provider reports one; else by age | Yes | A file with no size makes the total a floor. |
| `CONTAINER` | `zip`, `apk` | Answered from the kept archive tree, no I/O | Not needed | No | Settled at once. |
| `REMOTE` | `smb`, `sftp`, `ftp`, `webdav`, `peer` | Never | Never | Yes | Figures come from folders already browsed, from an opted-in share's index pass, or from Measure now with Stop. Shown as last known. |
| `PRIVATE` | `locker`, `root` | Visible rows only | Yes | **Never** | A Locker name must not reach a plain database. Dropped on lock. |

### 2.9 Chosen and rejected

| Option | Decision | Why |
|---|---|---|
| Persistent cache with time validation | **Chosen**, per directory, validated by modified time and inode | Index generation says when the crawl ran, not whether the folder changed. |
| Bubbling a change to ancestors | **Chosen** | It removes re-walks, the stale settled figure and the scoped Walk again bug at once. |
| Index sums as the figure | **Rejected** | A floor fifty times too low is the inaccuracy being removed. The engine's own running count replaces it within one tick. `indexTally` and its memo are deleted. |
| Index as a listing donor | **Chosen**, one way | Crawl and `refreshFolders` listings are already paid for. |
| Engine listings written into the index | **Rejected for now** | It would be a second writer into rows the crawl owns (`FolderMeasure.kt:35-37`). Revisit once the engine has shipped. |
| Media store as a size source | **Rejected** | No aggregates, one row per file over IPC, and incomplete. Kept as a change source. |
| Media store generation as "nothing changed, skip checks" | **Rejected** | Deletes are not shown to be in it (`MediaChanges.kt:37-39`). |
| Watching the whole tree with inotify | **Rejected** | The per-process watch limit, and FUSE does not report the media provider's own deletes (`FolderChanges.kt:24-29`). |
| A WorkManager size sweep | **Rejected** | The on-charge index pass already feeds the engine through the tap. |
| I/O priority (`ioprio`) | **Rejected** | The cost is CPU in the FUSE daemon, limited by the request rate, not disk queueing. |
| Whole tree in memory plus a snapshot file | **Rejected** | More resident memory, rewrite-all saves, and no query for the file lists. |

## 3. Budgets

Rules from `docs/OPTIMISATION.md` apply: same phone, release build, ten runs, median and worst, build named in the record.

Step 0 takes each number on today's code first. A target that the measured per-call cost makes unreachable is revised here, with the measurement, never silently.

The bench tree is `Filet-bench/`: 20 folders, each 2,000 one-kilobyte files in 100 folders. A script in `tools/` makes it over adb.

Instrument: `SizeBench` holds atomic counters (`readdir`, `stat`, worker CPU from `Debug.threadCpuTimeNanos`). It logs one line under the tag `FiletSizes` each time a screen's demand settles, and once a minute while a sweep runs. It is on only with Detailed logging.

| # | What | Target | Command |
|---|---|---|---|
| B1 | First figure for a folder with a record | On the row's first frame; `first_ms` ≤ 50 at p95 | `adb logcat -d -s FiletSizes:I` |
| B2 | A stored, unchanged screen settles (30 rows, 3,000 folders beneath) | `settle_ms` ≤ 1,500 at p95; `readdir` = 0 | same |
| B3 | First-ever measure of one bench folder | Running figure ≤ 300 ms; settled ≤ 2 s; a screen of 20 ≤ 20 s | same |
| B4 | Update latency (`update_ms` by source) | Own operation ≤ 500 ms; visible folder, other app ≤ 1.5 s; other app, any depth, add or modify ≤ 4 s; deep delete by another app ≤ 15 s while on screen | same |
| B5 | Fling through 1,000 folder rows, totals on against off | Janky share within 1 point; no frame over 700 ms | `adb shell dumpsys gfxinfo dev.niccc2007.filet reset`, fling, then `adb shell "dumpsys gfxinfo dev.niccc2007.filet \| grep -E 'Total frames\|Janky frames\|95th\|99th'"` |
| B6 | Idle, all figures settled, 60 s | `readdir` = 0, `stat` = 0; frames rendered ≤ 2 | The two commands above |
| B7 | Sweep | 1 worker; `cpu_ms` ≤ 12,000 per minute; stops under every condition in 2.6; first read of the drive resumes after a kill | `adb logcat -d -s FiletSizes:I`; `adb shell "dumpsys thermalservice \| grep -i 'Thermal Status'"` |
| B8 | Disk map on a measured drive | Every row has a figure on the first frame; Walk again in a folder of n folders reads n + 1 directories; main-thread tick ≤ 1 ms | `adb logcat -d -s FiletSizes:I` |
| B9 | Memory at 50,000 folders | Engine heap ≤ 8 MB; database ≤ 20 MB | `adb shell dumpsys meminfo dev.niccc2007.filet` |

## 4. The analyzer

Disk map stops being a walk and becomes a view of the engine.

- **Deleted:** `DiskMaps`, `DiskMap`, `SizeMap`, `StorageWalk`, `DiskTotals`, and the swap of `LocalFolderBytes` at `PaneView.kt:316-318`. Disk map rows read the same `watch` as every other row.
- **Kept as a shape:** `DiskSizes`, the immutable per-tick copy for one folder, now built from the engine off the main thread.
- **Turning it on** registers a demand for the folder on screen at `VISIBLE`. On a measured drive every figure is already there, so nothing is walked. Turning it off withdraws the demand.
- **Walk again** becomes `remeasure(folder)`, for the folder on screen:
  - it marks only that subtree for re-listing and keeps the old figures on screen as last known;
  - it replaces records one directory at a time;
  - ancestors stay correct at every instant, and nothing else on the drive is touched;
  - at a drive's top the same call covers the drive.
  
  A pure `MeasureScope.of(folder, driveRoot)` decides the label and which counter the strip prints. The strip's count is that request's own progress.
- **Stop** cancels the request. Records already replaced stay. The rest go back to last known, not to floors.
- **By type and the file lists** are queries scoped to the folder on screen:
  - per-extension bytes and counts summed over the subtree, grouped into kinds when read;
  - files of a kind or extension from the large-file table, largest first, paged;
  - a closing line gives the count and bytes of files under 1 MiB, so the list always adds up to the folder's figure.
  
  "Delete all of a type here" enumerates at action time by listing only the folders the extension table names, so it acts on what is really there.
- **Size sort on measured totals** and the order freeze are the redesign's. The engine supplies `known(path)` for the comparator, and the sort moves off the main thread.
- **The gap** ("Apps and system") is the volume's used bytes minus the root's total, shown once the root is settled.
- **Network drives** keep the strip's network line and Stop. The read is `remeasure(folder, force = true)`.
- **Tree with Disk map:** every level's figure, file count and share exist already, because every directory has a record.
- **Later features** this makes cheap: compare with last time (a dated copy of subtree totals) and the duplicate finder's size groups (the large-file table).

## 5. Steps

Each step ships alone and ends with a check. Steps 1 and 2 need no device.

| # | Step | Files | Pure functions and tests | Risk |
|---|---|---|---|---|
| 0 | Baseline. `SizeBench` on today's paths, the bench-tree script, numbers recorded in this file. | `log/SizeBench.kt`, `FolderMeasure.kt`, `StorageWalk.kt`, `tools/` | `SizeBench.line` format test | None |
| 1 | Stop the standing costs. Pulse clock gated on breathing rows; a row nothing will measure draws a still dash. | `ui/RowPulse.kt`, `RowViews.kt`, `MainActivity.kt` | `PulseGate.runs` test | Low |
| 2 | The model. Records, deltas, the in-memory store. | new `sizes/`: `DirFacts`, `SizeDelta`, `SizeStore`, `MemorySizeStore`, `LoopRule` | `DirFactsTest`; `SizeStoreContractTest` (after any sequence of put, remove and move, every subtree equals a recount from scratch); `LoopRuleTest` | Low |
| 3 | The fifth state. `LastKnown`, `wantsWalk` removed, every `when` decided. | `FolderBytes.kt`, `RowDetails.kt`, `RowViews.kt`, `DiskSizes.kt`, `RowDetailCard.kt` | `FolderState.of` test; `FolderTotalTest`, `RowDetailsTest` extended | Low; compile-enforced |
| 4 | The engine core, memory store, not yet wired. Queue, pace, policy, lister, single writer. | `sizes/SizeEngine.kt`, `SizeQueue.kt`, `SizePace.kt`, `SizePolicy.kt`; `Sizing` on `FileSystemProvider` and all eleven providers | `SizeQueueTest`, `SizePaceTest`, `SizePolicyTest`; `SizeEngineTest` on a real temp tree (scoped re-measure reads only its subtree; a delete during a measure cannot undercount; a superseded result is dropped) | Medium: concurrency |
| 5 | Rows, Details, multi-select and compress on the engine. List-level demand, leaf collectors, priming. `FolderMeasure` deleted. `tools/check-sizes.mjs` added. | `MainActivity.kt`, `RowViews.kt`, `PaneView.kt`, `PaneController.kt`, `FolderDetails.kt`, `Dialogs.kt`, `SelectionSummary.kt`, `BrowserViewModel.kt`, `FolderTally.kt` | `TotalShown.of`, `SizeDemand.of`; `SelectionSize` over figures | Medium. Check B3, B5, B6. |
| 6 | Persistence. SQLite store, last-known on cold start, the check pass, clear and forget hooks. | `sizes/SizeDb.kt`, `sizes/Recheck.kt`, `data/FiletStore.kt`, drive-forget and remote-forget sites | `DirCheck.decide`, `Recheck.due`; the contract test run on device against SQLite | Medium: schema. A broken file is set aside and rebuilt. Check B1, B2, B9. |
| 7 | Live sources. `graph.wrote`, `onRelocated`, pane and index taps, visible-row watches, `MediaChangeFeed`. | `FiletApp.kt`, the seven announce call sites, `IDX/SqliteIndex.kt`, `index/MediaWatch.kt`, `HttpServer.kt` | `ChangeRule.action` (exhaustive); `ChangeRouting.folders` | Medium: wide, each tap small. Check B4. |
| 8 | Proactive. `NEAR`, `PLACES`, `SWEEP`; heat and battery signals; pause on stop; resume. | `sizes/SizeEngine.kt`, `sizes/PowerSignals.kt`, `FiletApp.kt` | `SizeDemand.proactive`; sweep-order test | Medium: battery. Check B7. |
| 9 | Analyzer on the engine. Scoped Walk again, scoped counters, By type and file-list queries, sort off main. Old storage classes deleted. | `S/*`, `PaneView.kt`, `PaneController.kt`, `BrowserViewModel.kt` | `MeasureScope.of`; `TypeSplit.of`; store query tests; old `SizeMap` cases moved to the contract test | Medium: shared files with the redesign. Check B8. |
| 10 | Provider fast paths, only where step 0 shows the cost: a lean local listing with no row object per file, archive totals from the kept tree, server-reported folder sizes on WebDAV. | `LocalProvider.kt`, `ArchiveProvider.kt`, `WebDavProvider.kt` | `DirFacts` parity test against `list` | Low |
| 11 | Close out. `check-sizes` extended to the storage package, the size engine's row added to the table in `MediaAnnounce.kt`, `REDESIGN-SPEC.md` regenerated. | `tools/`, `media/MediaAnnounce.kt` | Checker selftest | Low |

`check-sizes.mjs` rules, each with a selftest:

- No tree walk for bytes outside `sizes/`.
- `LocalFolderBytes provides` appears only in `MainActivity` and the settings sample.
- `MediaAnnounce.announce(` appears only inside `graph.wrote`.
- No import of the deleted classes.

File ownership against the redesign plan: this plan owns `sizes/`, `S/DiskMaps.kt`, `DiskSizes.kt`, `SizeMap.kt`, `StorageWalk.kt`. The redesign owns `DiskMapViews.kt` and the row's drawing. Step 9 lands before the redesign's By type screens, which need its queries.

Known behaviour change: a hard link in two different folders is counted once per name, because de-duplication is per directory. Shared storage cannot hold hard links, so this differs only under root.

## 6. Hooks

Every site that reads or shows a folder size today, and where it goes.

| Site | Today | Moves to |
|---|---|---|
| `MainActivity.kt:103-114`, `:124` | Provides `FolderMeasure` wrapper | Provides the engine (step 5) |
| `RowViews.kt:378-422`, `:721-731` | Per-row lookup, poll, `measure` | Leaf `watch`; poll constants deleted |
| `RowViews.kt:556-718` | `DetailLine`, `SideCell`, spinner | Five states; gated pulse |
| `RowDetails.kt:270-290` | `totalOf`, `needsTotals` | New state; `needsTotals` feeds `SizeDemand` |
| `EntryDisplay.kt:223-241` | Passes `total` through | Unchanged |
| `FolderBytes.kt` | Type and interface | New state; `watch` mandatory; `of` and `measure` removed |
| `FolderMeasure.kt` (all) | Queue, walk, index memo | Deleted |
| `FolderTally.kt` | `TallyBuilder`, `LoopGuard`, `WalkState`, `TallySource`, `FolderSizes.pick`, `walksOnOpen`, `view` | `DirFacts`, `LoopRule`, `SizePolicy`. `FolderTally` stays as the sheet's view, mapped from the record. `INDEX` source removed. |
| `FolderDetails.kt:43-71` | Own walk | `watch`; Measure now is `remeasure(force)` |
| `Dialogs.kt:478`, `:536-551` | Sheet; one walk per selected folder | `watch` per folder |
| `SelectionSummary.kt:76-105` | Sums `WalkState`s | Sums figures |
| `BrowserViewModel.kt:1867-1878`, `Dialogs.kt:616`, `:622` | Compress estimate ignores folders | `known(path)`; still "at least" unless settled |
| `BrowserViewModel.kt:321-384` | `diskMaps`, `setDiskMap`, `diskGoTo`, `openDiskMap` | Engine demand and snapshot |
| `BrowserViewModel.kt:1760-1764` | `diskMaps.removed` | Deleted; `graph.wrote` |
| `PaneView.kt:245-259`, `:306-322`, `:878` | Strip wiring, Largest, Kinds, provider swap | Engine-backed; swap deleted |
| `PaneController.kt:1429-1507` | Disk order, reader, `showDisk` | Snapshot from engine; sort off main |
| `S/DiskMaps.kt`, `SizeMap.kt`, `StorageWalk.kt` | Second engine | Deleted |
| `S/DiskSizes.kt` | Snapshot and `DiskTotals` | Snapshot kept; `DiskTotals` deleted |
| `S/DiskMapViews.kt:80-90`, `:159-187`, `:228-294`, `:324-329` | Strip counts, Walk again, Stop, Largest, Kinds | Scoped counters, `remeasure`, cancel, store queries |
| `settings/RowDetailCard.kt:203-207`, `:411-414` | `SampleTotals` | Stays a fixed stand-in on the new interface; the one allowed second provider |
| `home/DriveMenu.kt:97-99`, `B/Menus.kt:204-205` | Entry points | Unchanged callers |
| `data/FiletStore.kt:366-375`, `home/HomeOverview.kt:935-939`, `settings/SettingsPage.kt:2496-2500` | `java.io.File` walk of Filet's own folders on each open | `watch` on those paths; `sizeOf` deleted |
| `locker/LockerViews.kt:190-203` | Reads the vault's own manifest | Stays; it is not a walk. Folder rows inside the Locker use the engine in memory only. |
| `BrowserViewModel.kt:880-885` | Counts indexed files of an absent drive | Stays on the index; it is an index fact, allow-listed in the checker |
| `IDX/FileIndex.kt:38-40`, `:343` | `subtree` sink | Kept for the line above only; `onListed` added |
| `B/FileKind.kt:113` | Size sort ignores folders | New reader of `known`, owned by the redesign |
| `media/MediaAnnounce.kt:41-51` | Table of storage costs | Gains the engine's row |
| Tests | `FolderMeasureMemoTest`, `FolderWalkQueueTest`, `FolderTallyTest`, `FolderTotalTest`, `DiskSizesTest`, `DiskOrderTest`, `SizeMapTest`, `SizeMapBatchTest`, `StorageWalkTest`, `index/FakeIndex.kt` | Replaced or extended in the step that removes their subject |

## 7. Decisions (2026-11-16)

1. **How far ahead should the sweep measure?** The whole drive is read once per install (about as much storage work as one Disk map walk today) and kept current after that.
   Decision: sweep when a folder total is shown in the current view, or Disk map was opened in the last 30 days. Otherwise only the free sources keep records current, and a first Disk map still needs one read.
   The alternative is always sweeping, which makes the first Disk map instant for everyone at the cost of one drive read nobody asked for.

2. **A switch for it, or none?**
   Decision: none. Battery saver, heat and low battery already pause it, and the redesign calls for fewer controls. Add one row under Row details only if the B7 check shows a cost on the phone.

3. **A mark for a figure that cannot be re-checked** (network folders, granted folders whose provider gives no times, or checks paused by battery saver).
   Decision: print it as `~4.6 GB`, and name its age in Details. Local figures need no mark; they are checked within about a second and breathe meanwhile.

4. **File lists stop at 1 MiB.** Largest files and the per-type lists show every file of 1 MiB or more, then one line with the count and bytes of the smaller ones.
   Decision: accept. The list always adds up to the folder's figure, and the alternative is a second copy of every file name on the phone.

5. **Walk again: fix now or with the engine?** Step 9 fixes it by construction. A patch on the old walk is possible sooner, but it would be thrown away and loses entries from Largest files after each use.
   Decision: fix it with the engine, and take steps 0 to 9 before the redesign's By type screens.
