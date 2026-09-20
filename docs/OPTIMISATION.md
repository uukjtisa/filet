# Optimisation — the roadmap

> Status: **roadmap**. Nothing here is done. This becomes round 17, after the redesign lands.

---

## The rule that makes the rest of it worth anything

**Nothing is optimised without a measurement before and the same measurement after.**

The expensive half of a performance pass is normally spent on code that was never the problem.
An optimisation with no number attached is a guess that has been made harder to revert, and it
carries a second cost that is easy to miss: it is usually also less readable than what it
replaced, so it charges rent for ever.

Two corollaries, both of which have already been earned in this repository:

- **The instrument must not be slower than the thing it watches.** The start-sharing hang was
  the trace, not the server: it wrote a file and read four hundred lines back on the click,
  three times a press.
- **A fix is not verified by the absence of the symptom.** Three separate things in this app
  were "verified" against a build that did not contain them. A measurement names the build.

---

## What flagship apps actually do, and what each one catches

Taken as patterns rather than tools, because the tool changes and the failure does not.

| Pattern | The failure it catches |
|---|---|
| **Baseline profiles** | Cold start is slow because the hot paths are interpreted on the first run. A profile ships them pre-compiled. The single largest startup win available on Android, and it is configuration rather than code. |
| **Startup tracing, split into stages** | An average launch time hides which stage is at fault. Application, first Activity, first frame and first usable frame are four different numbers with four different fixes. |
| **StrictMode with a penalty, in debug** | Disk and network on the main thread. It would have caught the sharing hang the first time the button was pressed, which is the whole argument for it. |
| **A main-thread watchdog** | Hangs that never become ANRs because they are 300ms rather than 5s. Nobody files those and everybody feels them. |
| **Recomposition counting** | A list that re-lays-out every row when one row changes. Invisible in a profile and obvious in a counter. |
| **Frame timing as a histogram, never a mean** | A mean frame time is dominated by the frames that were fine. The complaint is always about the 99th percentile, so that is the number to hold. |
| **A memory ceiling per surface** | A thumbnail cache that grows until the system kills the app, which is reported as a crash rather than as a cache. |
| **Startup work moved behind first frame** | Everything constructed eagerly in the application object is paid for before anything is drawn, whether or not the user goes near it. |

---

## Where this app is likely to be slow, ranked by suspicion

Written before measuring, on purpose, so the guesses are on the record and can be wrong.

1. **Application construction.** `FiletApp` builds the VFS, preferences, ledger, script store,
   script engine, signing keys, APK tools, the Trawl bridge, shortcuts and the home feed
   eagerly. `nearby` is `by lazy` and nothing else is. Every one of those is paid for before
   the first frame, on every cold start.
2. **`Prefs`.** SharedPreferences parses its XML on first touch on the calling thread. If that
   first touch is in composition or in a click, it is a hang with no obvious owner.
3. **The file list.** Rows carry an icon, chips, a subtitle and a thumbnail. Thumbnail decoding
   already has a known history of being cancelled and re-run.
4. **The index crawl against the UI.** The crawl and the list both touch storage, and storage on
   a phone is a queue a few requests deep.
5. **Archive listing.** Entry tables for a large zip are built in memory before anything draws.
6. **Anything else shaped like the sharing bug.** `check-mainthread.mjs` now covers two press
   paths and one screen. Every other press path is unchecked.

---

## The passes, in order

Each is separately shippable and each ends with a number.

### Pass 1 — see it
Debug StrictMode with a death penalty for disk and network on main. A main-thread watchdog that
logs any block over 200ms with a stack. Extend `check-mainthread.mjs` to every press path in the
app rather than the two it covers. **Ends with:** a list of every blocking call on the main
thread, measured rather than suspected.

### Pass 2 — cold start
Stage the startup trace. Move everything in `FiletApp` that is not needed for the first frame
behind a lazy or a background warm-up. Add a baseline profile. **Ends with:** first-frame time
before and after, on the real device, over ten runs, reported as a median and a worst case.

### Pass 3 — the list
Recomposition counts per row type. A thumbnail cache with a stated ceiling and an eviction rule.
Confirm keys are stable so a refresh does not rebuild every row. **Ends with:** a frame-time
histogram for a thousand-row folder, before and after.

### Pass 4 — the crawl
Bound the crawl's concurrency against the foreground. Yield to the UI when the app is in front.
**Ends with:** list scroll frame times during a crawl, before and after.

### Pass 5 — memory
A ceiling per cache, and a trim on the system callback. **Ends with:** peak memory browsing a
large media folder, before and after.

---

## How each number is taken, so two runs can be compared

- **The same device.** The Huawei, plugged in, screen on, airplane mode off, nothing else
  launched. A number from an emulator is not comparable to a number from that phone.
- **The same build type.** Debug is not release; a measurement from one says nothing about the
  other. Startup work especially.
- **Ten runs, median and worst.** A single run measures the scheduler, not the app.
- **Cold means cold.** Force-stop, then `am start`, and drop the first run after an install.
- **The build is named in the record**, because a fix verified against the wrong package has
  already happened here more than once.

---

## Open question

Whether a release build should carry the watchdog. Argument for: the hangs people actually hit
are in release. Argument against: it is one more thing running in the app that has no purpose
for the person using it. Current lean is **debug only**, with the checker as the release-side
guard, because a static check costs nothing at runtime.
