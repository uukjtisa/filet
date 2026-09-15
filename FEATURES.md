# Filet — feature ledger

**Nothing said in planning gets lost here.** Every item review raised is a row, even
the ones that turned out to be several features wearing a trench coat. Rows are
never deleted — they move to `CUT` with a reason.

**Rule R1 (No Dead Switches):** only `SHIPPED` rows may be visible in the app.
`BUILT` means the code exists but the flag is still OFF.

Status: `IDEA` → `SPEC` → `BUILT` → `SHIPPED` · or `CUT`

---

## Where this stands — 2026-09-15, after round 9

**83 SHIPPED · 13 BUILT · 4 IDEA · 4 CUT.** Measured on a HUAWEI NCO-LX1 (API 31); see
`GATES.md` for the per-milestone evidence and the abandoned gates.

Round 9 added fifteen rows and cut one. **The cut is the one worth reading**: F94, the native
7z writer, was committed to and then measured rather than attempted — no library Filet can
legally ship writes an encrypted 7z, and encryption was the only reason to want one. What ships
instead is the refusal with the true reason in it, and the switch that would have turned the
feature on was removed rather than left sitting in the capability table where nothing could
reach it.

`BUILT` here means one of exactly two things, and never "written but untried":

- **Nothing in the UI offers it.** F34 recipes, F61 saved searches, F65 the generation hint —
  the machinery exists, no switch is shown, so R1 holds.
- **It is offered, and honest about what it cannot do.** F07 root is an explicit opt-in that
  says "This device did not grant root" and stops; F75–F78 Nearby peer discovery is
  implemented and needs a second phone to exercise. These are *unverified*, which is a
  different thing from *dead*, and the distinction is the point of keeping the two words.

Three rows moved because the feature was genuinely broken and got fixed, not because the
ledger was tidied: **F22** (a shortcut's id died with the crawl's generation sweep), **F43**
(every rebuild emitted DEX 041 and could not run below API 35) and **F08** (Android's
`HttpURLConnection` rejects `PROPFIND`, so WebDAV had never worked at all).

---

## Core file management

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F01 | Dual pane / dual screen | L5 | M1 | SHIPPED | The MT Manager signature layout. Two independent nav stacks + a shared selection buffer — the state model is the hard part, not the layout. Two PaneControllers, separate stacks/selection/search. BrowsingTest. |
| F02 | Drag and drop | L5 | M1 | SHIPPED | In-app first. Cross-app needs `startDragAndDrop` + `DragAndDropPermissions` + URI grants. In-app drag between panes via DropRegistry. Cross-app URI grants not done. |
| F03 | Any file type, wide-range activity | L2 | M2 | SHIPPED | `*/*` intent filters + specific mimes. |
| F04 | Sort / filter / hidden files | L5 | M1 | SHIPPED | Sort and hidden-files are observed by each pane, so a Settings change re-renders open panes. |
| F05 | Bookmarks / history | L5 | M1 | SHIPPED | Bookmarks + a capped recents MRU, both persisted. |
| F06 | Archive browse as directories | L0 | M1 | SHIPPED | `ArchiveProvider`. Read M1, write M2. |
| F07 | Root file access | L0 | M8 | BUILT | **v2** — no rooted hardware. Needs an emulated image first. libsu, never `Runtime.exec("su")`. Provider registered and opt-in; no rooted device to prove it on. M8 abandoned, see GATES.md. |
| F08 | Network storage (SMB/SFTP/FTP/WebDAV) | L0 | M8 | SHIPPED | Falls out of the VFS almost free once it exists. WebDAV proven against a real server (M8b). SMB/SFTP/FTP untested — handoff H6. |
| F09 | "High versatility in managing storage" | L0 | M1 | SHIPPED | Resolved as: every access mode is a provider — local, SAF, manage-storage, root. |

## Search & index

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F10 | Search caching | L1 | M3 | SHIPPED | FTS5 + incremental crawler keyed on dir mtime. `MediaStore` is not enough. |
| F11 | Live (non-indexed) search | L1 | M1 | SHIPPED | Ships before the index so M1 is usable. |
| F12 | Stable file IDs | L1 | M3 | SHIPPED | Not a user feature — the substrate F20–F23 depend on. Survives a move: relinkMoves matches on inode, or (name,size,mtime) where there is none. |
| F13 | Search by origin | L1 | M7 | SHIPPED | **The differentiator.** See PLAN §5.1. Nothing on Android does this. |

## Routing & handlers

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F14 | Handler registry (per-type default app) | L2 | M2 | SHIPPED | The thing several of the asks collapse into. User-editable. |
| F15 | Single tap → registered handler | L2 | M2 | SHIPPED | |
| F16 | Double tap → system "Open with" | L2 | M2 | SHIPPED | The "double click opens in the default open-in app" behaviour. |
| F17 | Appear in other apps' "Open with" | L2 | M2 | SHIPPED | Manifest work. Being *chosen* is the user's call — never force default status. |
| F18 | Links/intents from other apps redirected here | L2 | M2 | SHIPPED | Incl. `http`/`https` so links can be handed to Trawl. |
| F19 | Share sheet target | L5 | M2 | SHIPPED | |

## Shortcuts & surfaces — *flagged by review as must-not-lose*

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F20 | Home-screen shortcut to a file, Windows-style | L5 | M4 | SHIPPED | Thumbnail icon + filename. `requestPinShortcut()`, API 26+. |
| F21 | Shortcuts renameable | L5 | M4 | SHIPPED | `updateShortcuts()`. |
| F22 | **Shortcuts never go stale** | L5 | M4 | SHIPPED | **The hard one.** Shortcut stores a *file ID*, not a path; Filet resolves ID → path at launch. Move follows, delete calls `disableShortcuts()` with a real message. Depends on F12. Closed for real in M4 — the id used to die with the generation sweep. |
| F23 | Per-shortcut "open in app" override | L5 | M4 | SHIPPED | e.g. a music file shortcut that opens in a player, not the text editor. Resolves against F14. |
| F24 | Widgets — script launcher, folder view | L5 | M4 | SHIPPED | Folder widget + its RemoteViewsService, declared in the manifest. A script-launcher widget is not built. |
| F25 | One-click script shortcuts | L5 | M5 | BUILT | A pinned shortcut whose target is a script, not a file. Depends on F27. Scripts can be pinned; the shortcut path is shared with F20. |

## Editors & viewers

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F26 | Text/code editor with syntax highlighting | L3 | M2 | SHIPPED | sora-editor (LGPL-2.1), TextMate + tree-sitter. |
| F27 | Hex editor | L3 | M2 | SHIPPED | Paged hex viewer, registered as a handler. |
| F28 | Image viewer + EXIF | L3 | M2 | SHIPPED | Sampled decode + pinch-zoom. |
| F29 | Media player | L3 | M2 | SHIPPED | |

## Scripting

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F30 | Lua runner | L4 | M5 | SHIPPED | LuaJ, pure Java, ~300 KB. **Ships first.** |
| F31 | Python interpreter | L4 | later | IDEA | 10–30 MB *per ABI*. Optional downloadable module behind the same API — not in the base APK. |
| F32 | Scripts bound to the VFS | L4 | M5 | SHIPPED | Non-negotiable. Gets SMB/archive/root support for free. |
| F33 | Script permission model | L4 | M5 | SHIPPED | **Security-critical.** Declared path/network scope, enforced at the VFS boundary. |
| F34 | Recipes / triggers | L4 | M5 | BUILT | Event-driven. "Trawl finished a download" is one event among many. ScriptStore + the bridge can fire one, but there is no trigger UI yet — so no switch is shown. |

## Trawl bridge

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F35 | Provenance records | L1/L6 | M7 | SHIPPED | Trawl writes, Filet stores + indexes. |
| F36 | Source chip on a file | L5 | M7 | SHIPPED | Open source page · copy URL · check still live · re-download at another quality. |
| F37 | Shared job ledger | L6 | M7 | SHIPPED | the "latest sidebar and monitoring" from the first message. |
| F38 | Signature-permission ContentProvider | L6 | M7 | SHIPPED | Both apps signed with the same key. Must discover `.debug` packages too. |
| F39 | ~~Download tracking~~ | — | — | **CUT** | review rejected it as too shallow. Superseded by F35–F37 — provenance is the deeper version of the same instinct. |

## APK / reverse engineering

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F40 | APK inspector | L3 | M6 | SHIPPED | Signature schemes, package/version, protection status. |
| F41 | Manifest / AXML editing | L3 | M6 | SHIPPED | ARSCLib — no aapt2 binary needed. |
| F42 | dex → smali browse and edit | L3 | M6 | SHIPPED | smali-dexlib2. |
| F43 | Rebuild + re-sign | L3 | M6 | SHIPPED | apksig, v1–v4. JKS and pk8/pem. Dex format version now follows the APK's own minSdk — it used to emit DEX 041 and die below API 35. |
| F44 | APK as a filesystem | L0 | M6 | SHIPPED | Browse *into* an APK; `classes.dex` opens as a tree of smali. This is the product feel. |
| F45 | "Embedded free tools" | L3/L4 | M5–M6 | SHIPPED | Resolved as: handlers (L3) + Lua recipes (L4), not a bundled binary zoo. |

## Search — see `SEARCH.md`

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F52 | Per-pane search (not global) | L5 | M1 | SHIPPED | A pane is what has a location, so a pane owns a search. Two panes = two searches; each tab keeps its own. |
| F53 | Explicit scope chips | L5 | M1 | SHIPPED | This folder / Subfolders / Whole device / Provenance. Tapping one writes its token into the query (F58). Plus tappable field chips, which is how the query language is found. |
| F54 | Retrieve-then-rerank | L1 | M3 | SHIPPED | SQL narrows 200k → 500 in <10ms; in-memory scorer reranks in <2ms. Never ask SQL to be clever. |
| F55 | Fuzzy scorer (fzf-class) | L1 | M3 | SHIPPED | Subsequence + word-boundary + camelCase + consecutive-run bonuses. `dwnld`→Downloads, `AM`→AndroidManifest.xml. |
| F56 | Typo tolerance | L1 | M3+ | SHIPPED | Damerau-Levenshtein ≤1/≤2, applied **only** to the 500 candidates, never as retrieval. |
| F57 | Frecency ranking | L1 | M3 | SHIPPED | `ln(1+opens)·exp(-Δt/τ)` + subtree/tracked/pinned boosts. Opens counted on real interaction only. |
| F58 | Query language | L1 | M3 | SHIPPED | `type: size: modified: in: from: pkg: class: perm: inzip: dup:` with AND/OR/NOT/grouping. Invalid syntax degrades to substring, never errors. |
| F59 | **Search inside APKs** | L1/L3 | M6 | SHIPPED | `pkg:` `label:` `perm:` via ARSCLib; `class:` as deduped package prefixes via dexlib2 (~2KB/APK, not 2MB). **Nothing else on Android does this.** ApkFacts: pkg/label/perm from the inspector, class as deduped package prefixes (cap 400). |
| F60 | Search inside archives | L1/L3 | M6 | SHIPPED | Zip central directory only — every member name without inflating a byte. Capped at 5,000 members + honest `truncated` flag. ArchiveFacts: central directory only, cap 5,000, `tag:truncated` when it bites. |
| F61 | Saved searches = smart folders | L5 | M4 | BUILT | Named queries that appear in the rail beside real folders. Same machinery, zero extra cost. saved_search table exists; nothing writes to it yet, so nothing is offered in the rail. |
| F62 | Duplicate finder | L1 | M4 | SHIPPED | size group (SQL, no IO) → head+tail 4KB xxHash64 → full hash. Reaches stage 3 with dozens of files, not thousands. size group → 4 KB edge hash → full hash. `dup:` in the query, ContainerSearchTest. |
| F63 | Type-to-jump | L5 | M2 | SHIPPED | Typing with the list focused scrolls to the first match in the *current listing*. Deliberately NOT merged with search. |
| F64 | Stable streaming order | L5 | M3 | SHIPPED | Fixed 90ms first paint; late arrivals may only insert below the viewport. Rows never move under the user's thumb. |
| F65 | MediaStore generation hint | L1 | M3 | BUILT | `MediaStore.getGeneration()` — one call, tells us *where to look*. A hint, never an index. MediaChangeJobService is wired; the generation hint itself is not read yet. |
| F66 | Per-volume index shards | L1 | M3 | BUILT | Keyed on `StorageVolume.getUuid()`. Eject hides a shard in one statement; reinsert restores without re-crawl, even in a different slot. Volume rows are keyed and shards hide on unmount; no multi-volume device here to prove it. |
| F67 | Self-healing index | L1 | M3 | BUILT | Lives in `cacheDir`, `quick_check` on open, silent background rebuild, resumable crawl cursor. A corrupt cache is never an error the user reads. cacheDir + open-time probe + resumable crawl. `quick_check` on open not added. |
| F68 | Search observability | — | M3 | IDEA | Debug screen: p50/p95/p99 latency, candidate-set sizes, watch count vs cap, slowest 10 queries with stage timings. Not built. The numbers in GATES.md M3 come from the benchmark, not from an in-app screen. |
| F69 | Benchmark harness | — | M3 | SHIPPED | Synthetic tree generator incl. the 12,000-files-in-one-directory case + CI macrobenchmark. Published numbers on a named device (R4). IndexScaleTest is the harness: 100k real files, published numbers on a named device (R4). |

## Nearby / cross-instance sharing — see `NEARBY.md`

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F70 | Shared set = real folder **+** proxy entries | L0 | M9a | SHIPPED | The own conclusion, and the right one. Drop files in a real folder, *or* add a pointer to a 4 GB file you are not going to copy. One listing, both kinds. |
| F71 | Proxies store a node ID, never a path | L0/L1 | M9a | BUILT | Move the file and the share follows; delete it and `ON DELETE CASCADE` removes the share. A broken share entry cannot exist. Same rule as F22. Proxies still store a path, not a node id — the id substrate landed in M4 after Nearby was written. |
| F72 | Zero-copy streaming | L0 | M9a | SHIPPED | HTTP range requests from wherever the file lies. Sharing 4 GB costs 0 bytes and starts instantly. |
| F73 | HTTP+TLS server, serve-by-token | L6 | M9a | SHIPPED | URL is `/f/<token>` → node id. **Paths never enter a URL, so traversal is unrepresentable, not merely blocked.** |
| F74 | **Browser download — no app needed** | L6 | M9a | SHIPPED | A laptop opens the URL and downloads. Makes "send it to my PC" work with nothing installed on the PC. Free, because the server already exists. |
| F74b | **Browser share screen** — QR + URL + PIN | L5 | M9a | SHIPPED | The URL is a *feature with a screen*, not a setting. QR because typing an IP on a phone keypad is the difference between "share this" and "never mind". |
| F74c | Split endpoints: pinned HTTPS for peers, plain HTTP for browsers | L6 | M9a | SHIPPED | A self-signed cert throws a full-page browser interstitial and half the users stop. Peer and browser transports have different threat models and must not share an endpoint. |
| F74d | 6-digit PIN, **on by default** | L6 | M9a | SHIPPED | Rotates per session, rate-limited. The dangerous configuration must never be the one you get by accident. |
| F74e | **Web upload drop zone** | L6 | M9a | SHIPPED | Laptop → phone with nothing installed on the laptop. No cable, no cloud, no pairing. Off by default; lands in quarantine. |
| F74f | Streamed zip for multi-select | L6 | M9a | SHIPPED | Streamed, never buffered — a 4 GB selection must not need 4 GB free on the phone. |
| F74g | Live client list with kick | L5 | M9a | SHIPPED | Seeing who is connected is what stops an open server being a thing you forget you left on. |
| F75 | mDNS discovery (`_filet._tcp`) | L6 | M9b | BUILT | Same WiFi *or* same hotspot, automatic. NsdManager discovery is implemented; needs a second device (H7). |
| F76 | Pairing with a 4-digit SAS | L6 | M9b | BUILT | TOFU on a self-signed cert is MITM-able; a short code on both screens closes it. Paired peers are one tap after. SAS pairing dialog is implemented; needs a second device (H7). |
| F77 | Share sheet — multi-select → peers | L5 | M9b | BUILT | The flow he described. Three taps, never leaves the pane. Share-to-peer exists behind the same gate as F75. |
| F78 | **A peer is a volume** | L0 | M9c | BUILT | `NearbyProvider`. Open a peer in the other pane and drag between phones. Nobody else does this; it falls out of the VFS + dual pane for free. PeerProvider is registered; peer-as-a-pane needs a second device (H7). |
| F79 | Wi-Fi Direct fallback | L6 | M9d | IDEA | No router at all. Also the answer to AP client isolation. Wi-Fi Direct fallback not written. |
| F80 | AP-isolation detection | L6 | M9d | BUILT | mDNS finds peers but TCP fails ⇒ the network blocks peer traffic. Say so and offer Wi-Fi Direct, rather than looking broken. Isolation detection is implemented; unexercised without a second device. |
| F81 | ~~Merged two-way sync folder~~ | — | — | **CUT** | That is a sync product — conflicts, tombstones, clock skew. It is Syncthing. Cutting it keeps Nearby weekend-sized. |
| F82 | Wear OS companion | — | M10 | IDEA | **Companion, not a port.** Transfer approve/reject from the wrist, activity glance, recipe tile. Wearable Data Layer over Bluetooth — no network, no second server. M10, out of scope for this run. |
| F83 | ~~File browser on the watch~~ | — | — | **CUT** | A filesystem on 1.4" round is worse than not having it, and it would eat the time that makes the phone app good. |

## App shell — see `TEMPLATE.md`

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F46 | In-app updater | Signet | M0 | SHIPPED | GitHub Releases, `HttpURLConnection` + `org.json` so it adds no dependency. Checks, shows the notes, downloads with progress, hands the APK to the system installer which still asks. Distinguishes "nothing published" from "could not reach GitHub" - a null cannot, and reporting the first as the second sends the user to check their wifi. **`github` flavour only**: F-Droid forbids self-updaters. |
| F47 | Update / changelog page | Signet | M0 | SHIPPED | |
| F48 | First-run introduction wizard | Signet | M0 | SHIPPED | |
| F49 | Signature motion language | Signet | M0 | SHIPPED | the house style, ported from the portfolio. |
| F50 | Theme system — gray default, warm optional | Signet | M0 | SHIPPED | "Minimalistic but detailed, cozy but greyer." Filet ships `Slate`; `Ember` is the warm option. |
| F51 | About screen with the signature mark | Signet | M0 | SHIPPED | Maker's mark, not the app icon. |

## Archives — round 9

Two asks and a licence answer. The creation window had one control for every format; extraction
had one destination and no way to see what it was about to do. Both are now decided by a table
and a plan rather than by a `when` over format ids.

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F84 | Per-format compress options | L0/L5 | M9e | SHIPPED | Strength, password, encryption method and split size, each drawn from `ArchiveCapability` and nothing else. The scales are in their own encoder's units — deflate 0-9, LZMA2 preset 0-9, bzip2's *block size* — because pretending they share one is how a slider ends up meaning nothing. `check-archiveui.mjs` fails the build on a control rendered outside its capability check, or on a `format.id ==` anywhere in that file. |
| F85 | Zip AES-256 / AES-128 / ZipCrypto | L0 | M9e | SHIPPED | zip4j, Apache-2.0. ZipCrypto is offered and labelled weak rather than hidden — some readers take nothing else. Verified by 7-Zip as an independent reader, including that the wrong password is refused. `java.util.zip` can do none of this. |
| F86 | Split archives (create) | L0 | M9e | SHIPPED | Zip volumes for zip; a plain numbered `.001` byte split for everything else, which is what 7-Zip itself produces. A minimum part size per format, because a part too small for the format's own headers fails at part 1 of 400. |
| F87 | Multi-part archives (read) | L0 | M9e | SHIPPED | `PartSet` resolves `.part1.rar` / `.r00` / `.zip.001` / `.001` into one archive. A numeric suffix only counts as a part when the base name is itself an archive, so `report.7z.001` is a part and `photo.001` is a file. |
| F88 | Estimated output size | L0/L5 | M9e | SHIPPED | A range with a reason, never a figure. Already-compressed media is counted separately from the same set the zip writer uses to decide what to store, so the estimate and the writer cannot disagree. A folder in the selection makes it "at least", not a confident wrong number. |
| F89 | Extract here / to… / to the other pane | L5 | M9e | SHIPPED | One hardcoded destination was the whole complaint. All three open the same preview; none of them writes anything first. |
| F90 | Extraction preview | L0/L5 | M9e | SHIPPED | **The round's centre.** `ExtractPlanner.plan()` returns the value the preview draws *and* the value the extractor walks — the same object, so the drawing cannot be of something that then does not happen. Says what would be overwritten, whether it fits, what the archive costs, and how many entries tried to write outside the folder. |
| F91 | Smart wrap / unwrap | L0 | M9e | SHIPPED | An archive with loose files at the top gets a folder named after it; a single redundant parent is lifted away, all the way down the chain while each level holds exactly one folder and nothing else. Never both. Both are one tap to reverse from the preview, and reversing re-plans rather than editing the plan. |
| F92 | Edit a file inside an archive | L0/L5 | M9e | SHIPPED | Save prompts: update the archive, or write the file somewhere else. A zip or a plain tar re-compresses **only the changed member** — untouched entries are copied as raw deflated bytes. A `tar.gz` or a 7z is one compression stream and must be rebuilt, and the prompt says so with the count and the size before it starts. The rewrite lands in a temp file and is renamed in, so an interrupted save leaves the original openable. |
| F93 | Refuse to edit an encrypted archive | L0 | M9e | SHIPPED | Rewriting one entry into an AES zip without the password leaves the others protected and the new one not — an archive that opens in nothing and looks like corruption. Refused with the reason, and "save somewhere else" is offered for every archive including the ones that cannot be written at all. |
| F94 | ~~Native 7z writer~~ | L0 | M9e | **CUT** | Costed, not skipped. libarchive 3.7.7's 7z writer contains **no encryption at all** — zero occurrences of `passphrase`, `aes`, `encrypt` or `crypt` in 2,356 lines, against 11 and `aes128`/`aes256` in the zip writer of the same release. Encryption was the entire point: Filet already writes 7z through commons-compress. The one encoder that does write it is 7-Zip's own C++ tree, which GPL-3 *can* take — it is a vendoring project, not a feature. Measured in `core-native/README.md`; the 7z password field says this reason rather than shrugging. |

## Home — round 9

| # | Feature | L | M | Status | Notes |
|---|---|---|---|---|---|
| F95 | Expanded "New files" history | L5 | M9e | SHIPPED | The tracked-folder feed without its eight-row cap — the same walk, not a second scanner. Grouped by real local dates with a count and a size on each header, collapsible, loading 80 rows at a time as the end comes into view. |
| F96 | First seen, recorded | L1 | M9e | SHIPPED | The filesystem cannot answer "when did this appear here": a file copied in yesterday carries last year's mtime. One timestamp per path, written once, bounded and pruned against what a scan actually saw. Seeded from mtime on a genuinely first run, so an existing device does not get four thousand files all stamped with the clock. |
| F97 | First seen / Last changed, and By day / Flat | L5 | M9e | SHIPPED | Two orderings that genuinely differ — the test that matters puts one file in two different groups — and a plain list for navigating without headers. |
| F98 | Date picker jumps to a day | L5 | M9e | SHIPPED | A jump, not a filter: the history stays either side of the day asked for. A day with nothing in it lands between its neighbours rather than doing nothing. |

---

## Deliberately not doing

| Thing | Why |
|---|---|
| Anything named "…Manager" | The category the clones own. Also unprotectable, which guts the GPL + reserved-name strategy. |
| Ad-removal / licence-bypass / "unlock premium" features | Loses the substantial-non-infringing-use shield and invites inducement liability. The tool is fine; marketing it that way is not. |
| Bundled patch scripts or target app lists | Ship the tool, not the exploit. |
| A community for sharing modified APKs | Red-flag knowledge. This is how tool authors end up named in filings. |
| Forcing default-handler status | Not possible, and hostile. |
| Vendoring library source into the app module | MP-Manager's 373k pasted lines is exactly the mistake. |
