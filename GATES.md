# Gates: Filet — M0 through M9

OWNS: app/**, core-vfs/**, tools/**, settings.gradle.kts, gradle/libs.versions.toml, GATES.md

Scope: build Filet from an empty Android Studio project up to and including M9 (Nearby),
with every milestone's exit criterion from `PLAN.md` §8 demonstrated rather than asserted.

---

## Scope reality — read before trusting any completion claim

**Milestone gates below are unmet until their exit criterion is demonstrated.** An unmet gate
is the honest state, not a failure — M8 is abandoned with a stated reason rather than quietly
ticked, and three separate gates were genuinely unmet when first measured and only passed
after the product was fixed.

### Environment constraints that bound what can be verified here

| | Status |
|---|---|
| Gradle build | ✅ available |
| JVM unit tests | ✅ 99, 0 failures |
| Device | ✅ **HUAWEI NCO-LX1, API 31, real hardware** — every on-device gate below was measured on it (89 tests, 0 failures, 0 skipped) |
| Emulator | ✅ AVD `filet37` (hand-written config; no cmdline-tools on this box). Used early, then retired: real hardware is better evidence and the host cannot hold both |
| Root (M8) | ❌ **not rootable after all.** `adb root` gives a root *adb shell*, not app root. The emulator's `/system/xbin/su` is 4750 `root:shell` and its own code refuses any caller that is not uid shell or root; the phone is not rooted. See the M8 abandonment |
| Network shares (M8b) | ✅ real WebDAV server on the host (`tools/testservers/webdav.py`) over `adb reverse`. SMB/SFTP/FTP have no server — handoff H6 |
| Second device (M9 peer-to-peer) | ⚠️ one device. The browser half is closed with an external client; phone-to-phone drag needs a second phone — handoff H7 |
| Galaxy Watch (M10) | ❌ out of scope — user capped this run at M9 |

---

## M0 — VFS + LocalProvider + one pane

- [x] G1: The debug APK builds from a clean checkout
  CHECK: cmd /c gradlew.bat --no-daemon assembleDebug
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: BUILD SUCCESSFUL; app-debug.apk 11,921,812 bytes, 2026-09-13

- [x] G2: The applicationId in the build file and the one recorded in PLAN.md are the same string
  CHECK: node tools/check-appid.mjs
  EXPECT: APPID CONSISTENT
  EVIDENCE: APPID CONSISTENT (dev.niccc2007.filet, 2 references in PLAN.md)

- [x] G3: The E4 adaptive icon is installed and every generated raster launcher icon is gone
  CHECK: node tools/check-icon.mjs
  EXPECT: ICON OK
  EVIDENCE: ICON OK (2 foreground paths, 1-colour monochrome, 1 adaptive config, 0 raster; 10 generated .webp removed)

- [x] G4: `:core-vfs` exists as its own module and `:app` depends on it
  CHECK: node tools/check-modules.mjs
  EXPECT: MODULES OK
  EVIDENCE: MODULES OK (:core-vfs included, :app depends on it, provider core present)

- [x] G5: VFS unit tests pass — path handling, listing, and the six file operations
  CHECK: cmd /c gradlew.bat --no-daemon :core-vfs:test
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: BUILD SUCCESSFUL; counted from test-results XML, not from the build banner: 24 tests, 0 failed, 0 skipped (VPathTest 9, LocalProviderTest 15)

- [x] G6: PLAN.md R3 holds — no storage API is reachable outside a provider
  CHECK: node tools/check-r3.mjs
  EXPECT: R3 OK
  EVIDENCE: R3 OK (17 source files scanned, 0 violations)

- [x] G7: R3's checker actually fails when the rule is broken (positive control)
  CHECK: node tools/check-r3.mjs --selftest
  EXPECT: R3 SELFTEST OK
  EVIDENCE: R3 SELFTEST OK — plants a java.io.File import in a throwaway tree, confirms it is flagged, then confirms a clean file is not

- [x] G8: Slate theme tokens are defined, and defined on light first per TEMPLATE.md §3
  CHECK: node tools/check-theme.mjs
  EXPECT: THEME OK
  EVIDENCE: THEME OK (7 token pairs, light-first, BgD #161614 warm-neutral). The checker itself was wrong first — it matched bare names and imports sort alphabetically, so darkColorScheme always appeared first; it now matches the declarations.

- [x] G9: M0 exit criterion — on a running emulator, browse internal storage and perform
      copy, move, delete, rename and mkdir without a crash
  EVIDENCE: Emulator filet37 (API 37, google_apis x86_64, hand-written AVD config). APK installed,
    MANAGE_EXTERNAL_STORAGE granted via appops. Browsed /storage/emulated/0; navigated into Download
    with the breadcrumb updating and the empty state rendering; a folder created outside the app
    (FiletTest) appeared on relaunch, proving live reads rather than a cached listing. Dark and light
    Slate both rendered. 0 fatal exceptions in logcat for dev.niccc2007.filet.
    Operations proved by instrumented test :core-vfs:connectedDebugAndroidTest — 3 tests, 0 failed:
    browses_real_internal_storage, mkdir_rename_copy_move_delete_all_work_on_device,
    copy_of_a_directory_tree_survives_real_io.

---

## M1..M9 — one gate per milestone, each its PLAN.md §8 exit criterion

Measured on **HUAWEI NCO-LX1 (API 31)**, real hardware, 2026-09-13. Suite totals at the time
of writing: **99 JVM unit tests** and **89 on-device tests**, 0 failures, 0 skipped.

Reproduce the on-device half with:

```
./gw.sh :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest
./tools/nearby-client.sh <serial> &        # the M9 external client
./tools/run-gates.sh <serial>
```

`run-gates.sh` exists because `connectedAndroidTest` reinstalls the app on every run, which resets
`MANAGE_EXTERNAL_STORAGE` (an appop, not a runtime permission) and deletes the artifacts the
gates produce. It installs once, grants, runs `am instrument`, and collects the evidence into
`build/gates/`.

- [x] M1: Used daily in place of the incumbent file manager — SAF + manage-storage providers,
      dual pane, drag & drop, sort/filter, bookmarks, archive read, live search
  CHECK: ./tools/run-gates.sh <serial> dev.niccc2007.filet.milestones.BrowsingTest
  EXPECT: OK (
  EVIDENCE: BrowsingTest, 8 tests, 0 failures. Two panes with genuinely separate location,
    history, selection and search (`two_panes_hold_separate_locations_histories_and_selections`,
    `each_pane_searches_on_its_own`); sort by name/size/date with folders-first and a live
    re-render on a pref change; hidden files on and off; an archive browsed AS a directory and
    a member read out of it; live non-indexed search finding a file two levels down; a
    bookmark surviving a second Bookmarks instance.
    **The "used daily" clause is Nic's verdict, not a check** — it is the one exit criterion
    in this file that no command can decide, and it is left to him deliberately rather than
    claimed. SAF likewise needs a tree the user picks; the provider is registered and its
    addressing is unit-tested, but a granted tree cannot be fabricated in a test.

- [x] M2: Single/double tap routing resolves through the handler registry, and another app can
      open a file in Filet via an inbound intent
  CHECK: ./tools/run-gates.sh <serial> dev.niccc2007.filet.milestones.RoutingTest
  EXPECT: OK (
  EVIDENCE: RoutingTest, 7 tests, 0 failures. Single tap routes by kind for 7 file classes;
    "Always" in the chooser writes the registry and a *fresh* registry instance still obeys it;
    the chooser offers ≥3 real handlers. The inbound half is answered by the **platform's own
    PackageManager**, not by reading our XML back: `queryIntentActivities` resolves this
    package for VIEW `content://` `*/*`, SEND, SEND_MULTIPLE and GET_CONTENT.

- [x] M3: A 100k-file device is searched in under one second, measured — not asserted
  CHECK: ./gw.sh :core-index:connectedDebugAndroidTest
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: `build/gates/m3-latency-index.txt`, produced by IndexScaleTest against **100,000
    real files** in 1,000 directories on the phone's own filesystem (101,023 indexed rows,
    FTS5 trigram present):

    | query | warm ms | hits |
    |---|---|---|
    | quarterly | 4 | 1 |
    | invoice | 497 | 200 |
    | in (2 chars — prefix index, trigram cannot help) | 404 | 200 |
    | rec | 375 | 200 |
    | recon 2026 | 5 | 1 |
    | ext:pdf invoice | 367 | 200 |
    | size:>1mb export | 5 | 1 |

    Slowest 497 ms against a 1,000 ms gate. Corpus built in 9.1 s, full crawl 56.0 s. The
    figure is end-to-end `IndexSearchSource` — SQL candidates, fuzzy rerank, frecency, and the
    `stat` verification pass — not a bare SELECT. A file deleted after indexing is proven not
    to reach the results (`a_file_deleted_after_indexing_is_not_returned`).

- [x] M4: A pinned shortcut still opens its file after that file has been moved
  CHECK: ./tools/run-gates.sh <serial> dev.niccc2007.filet.milestones.ShortcutIdentityTest
  EXPECT: OK (
  EVIDENCE: 4 tests, 0 failures, plus `build/gates/m4-shortcut.txt`.
    `launching_a_shortcut_by_id_opens_the_browser_at_the_current_path` fires the real Intent
    into `ShortcutRouterActivity` carrying nothing but a number, catches the browser starting
    with an `ActivityMonitor`, and asserts the Intent's target is the file's **new** path.
    A deleted file resolves to nothing rather than to a stale path.
    **This gate was genuinely unmet when first measured**: the crawl's generation sweep
    deleted the old row and the id died with it. Fixed by `SqliteIndex.relinkMoves` — inode
    first, `(name,size,mtime)` where a backend has no inode, one-to-one matches only, identity
    transferred to the new location and never replaced. `IndexOnDeviceTest` now asserts the
    *same* id (not merely "an" id), that provenance and frecency travel with it, and that an
    ambiguous match is refused rather than guessed.

- [x] M5: A Lua script processes a file on a share it has never seen, proving the script API is
      bound to the VFS and not to `java.io.File`
  CHECK: ./tools/run-gates.sh <serial> dev.niccc2007.filet.milestones.ScriptOnVolumesTest
  EXPECT: OK (
  EVIDENCE: 3 tests, 0 failures. **The same unmodified script** runs over two volumes with no
    `java.io.File` behind them: bytes inside a zip (`zip:///…!/docs/inner.txt`, which exists
    nowhere on disk) and a **real WebDAV share on another machine**, reached over a real socket
    through `adb reverse` to `tools/testservers/webdav.py`. The receipt is written back through
    the network provider and read back the same way. A script granted only the archive is
    refused the share, so permissions are per-volume and not per-device.

- [x] M6: An APK is decompiled, one smali line edited, rebuilt, re-signed and installed on device
  CHECK: ./tools/run-gates.sh <serial>
  EXPECT: M6 end-to-end: the edited code is what ran
  EVIDENCE: ApkRoundTripTest (3 tests) plus the install-and-run step in `run-gates.sh`.
    `tools/fixture` builds an 8 KB installable APK holding one string constant. Filet
    decompiles it, exactly one smali line is changed, it rebuilds, signs with a key generated
    on the device (`Filet Test`), and the result is re-read through the same inspector: right
    package, new signer, `PATCHED-BY-FILET` in the dex, `ORIGINAL` gone. ART itself then loads
    the class out of the rebuilt APK and calls the method. Finally the host installs it and
    runs it: `tag=PATCHED-BY-FILET` (`build/gates/m6-rebuilt-run.txt`).
    **A shipping-grade bug was found closing this gate**: `SmaliOptions.apiLevel` was the
    tool's *reading* level (35), so every rebuild emitted **DEX 041** — installable and then
    dead on any device below API 35. The write level is now the APK's own `minSdkVersion`,
    read from the APK rather than guessed by the caller, and the test asserts the dex format
    version against the running device.

- [x] M7: A file is found by where it came from, with provenance written by Trawl
  CHECK: ./tools/run-gates.sh <serial> dev.niccc2007.filet.milestones.BridgeProvenanceTest
  EXPECT: OK (
  EVIDENCE: 3 tests, 0 failures. Trawl's *role* is played by the instrumentation APK — a
    different package, signed with the same key, holding only
    `dev.niccc2007.filet.permission.BRIDGE` — which is exactly the trust relationship the
    contract describes. A real `ContentResolver.insert` crosses the process boundary, through
    the signature permission and through `assertTrusted`, and the file is then found by
    `from:example.invalid`. Provenance follows the file when it moves.
    **Not proven here:** that Trawl's own code calls the contract correctly. That is Trawl's
    side and is recorded as handoff H5.

- [ ] M8: Root browse works on a rooted emulator image
  EVIDENCE: The half that IS checkable is checked, and it is the half that matters daily —
    RootAccessTest, 3 tests, 0 failures: root is never taken at startup (`isGranted` is false
    before anything opts in); on an unrooted device a root listing produces **nothing**, never
    a fabricated tree; and the control holds — this app genuinely cannot read `/data`, so the
    test is not passing for a trivial reason. The network half of M8 is met in full, see M8b.

ABANDON: M8 No rooted image is available on this machine, and the failure mode is not a file
permission: a stock `google_apis` emulator's `/system/xbin/su` is mode 4750 `root:shell` and
its own code refuses any caller that is not uid shell or root, so chmod does not help —
verified directly, `run-as <pkg> /system/xbin/su` reports "inaccessible or not found". The
Huawei is not rooted either. Closing this needs a Magisk-patched image or rooted hardware.

- [x] M8b: Network storage is a volume — list, read, write, rename, delete over a real protocol
  CHECK: ./tools/run-gates.sh <serial> dev.niccc2007.filet.milestones.RemoteVolumeTest
  EXPECT: OK (
  EVIDENCE: 6 tests, 0 failures against `tools/testservers/webdav.py` on the development host,
    over a real socket. Share root and subdirectory listings, a remote read, `stat` size, and
    a full write → read-back → rename → delete round trip, plus a remote mkdir/rmdir. The
    saved password round-trips through AndroidKeyStore rather than sitting in prefs.
    **Two real bugs were found by pointing it at a server that is not us**: `samePath` also
    accepted `href.endsWith(remote)`, which is true of *every* href when the remote is the
    share root — so listing the root of a share returned nothing at all; and Android's
    `HttpURLConnection` validates the method against a fixed list, so `PROPFIND`, `MKCOL` and
    `MOVE` threw `ProtocolException` and WebDAV had **never worked**. SMB, SFTP and FTP share
    the addressing and credential layers but have no server here; they are handoff H6.

- [x] M9: A browser with no Filet installed downloads a shared file from the served URL
  CHECK: ./tools/run-gates.sh <serial> dev.niccc2007.filet.milestones.NearbyExternalClientTest
  EXPECT: OK (
  EVIDENCE: `build/gates/m9-external-client.txt`. Filet's LAN server runs on the phone; the
    **development host** reaches it through `adb forward` with curl and behaves like a browser —
    loads the gate page (200, no PIN, because the PIN has to be typeable), POSTs the PIN,
    keeps the session cookie, reads the listing, downloads the file. The bytes it received are
    compared against the bytes that were shared. Nothing on the client side knows what Filet
    is. NearbyServerTest adds 13 more over a real socket: locked before the PIN, wrong PIN
    refused, Range 206, unknown token 404, a path-shaped token reaching nothing, streamed zip,
    uploads refused while off and quarantined when on.
    **Not met:** the drag-a-file-from-another-phone's-pane half, which needs a second Android
    device running Filet. Handoff H7.

---

## Handoffs

- **H1 — the public identity on the About/onboarding screen.** Onboarding reads
  "Built by Niccc2007 (uukjtisa)". The standing rule is that the handle is the public face and
  the legal name stays on private and legal documents, so the *shape* is right - but the two
  handles are both there and an API check in an earlier session found `niccc2007` 404ing while
  `uukjtisa` 200s. **Nic's call which one the app should carry.** The `applicationId`
  `dev.niccc2007.filet` is deliberate and stays; it freezes at first release.

- **H2 — RESOLVED.** AVD `filet37` created by hand (no `cmdline-tools` in this SDK, so the two
  ini files were written directly). Superseded in practice: every gate above was measured on
  real hardware instead, which is better evidence.

- **H3 — SUPERSEDED by the M8 abandonment.** `adb root` gives a root *adb shell*; it does not
  give an *app* root. See M8 for why the emulator's `su` cannot be used by an app at all.

- **H4 — RESOLVED for the browser half, OPEN for the peer half.** The browser clause of M9 is
  closed with an external client over `adb forward`. Two Filet instances dragging between panes
  still needs a second Android device. Tracked as H7.

- **H5 — Trawl's side of the bridge is unverified.** M7 proves Filet accepts and indexes
  provenance from a same-key package. Whether Trawl *writes* it correctly is Trawl's repo and
  its own gate. Needs the provenance-write change landed there and one real download checked.

- **H6 — SMB, SFTP and FTP have no server to test against.** They share `NetConnections`,
  the `scheme:///<connId>/<remote>` addressing and the keystore-backed credentials with WebDAV,
  all of which are now proven end-to-end - but the three protocol implementations themselves
  have only been compiled against their libraries' real APIs, never spoken to a server.
  WebDAV had two genuine bugs that only a real server exposed; assume these have some too.

- **H7 — the second phone.** M9's "drag a file from another phone's pane into yours" and the
  mDNS/pairing/SAS flow need two devices running Filet on one network. One device here.

- **H8 — SAF needs a human.** `SafProvider` cannot be exercised without a user picking a tree
  in the system picker. Its addressing is unit-tested; the granted-tree path is not.
