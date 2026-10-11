# A clipboard that holds more than one thing

Design for the reported request: collect several copy and move batches from different places,
then paste them where you want, individually or all at once — and keep a copy loaded so it can
be pasted in several places.

Status: **design only.** Nothing here is built yet.

---

## What exists today

One clipboard, replaced wholesale.

- `Clipboard(items: List<VPath>, op: PendingOp)` lives on `AppState`, written by
  `copySelection()` and `cutSelection()`.
- Each Copy or Cut **overwrites** whatever was there. There is no way to add to it.
- A MOVE paste clears it. A COPY paste leaves it, so a copy can already be pasted in several
  places — that half of the request works and nothing on screen says so.
- It is shown as `PastePill`, a floating per-pane pill reading "Paste 7 here" with an × to
  clear. Per-pane and not focused-pane, so in split view "here" is a place rather than a guess.
- It does not survive the process.

So the gap is precisely: **you cannot hold more than one batch**, and the one you do hold does
not tell you it is still loaded after a paste.

---

## The model

The clipboard becomes an ordered list of batches. A batch is one Copy or one Cut.

```kotlin
data class ClipBatch(
    val id: String,
    val items: List<VPath>,
    val op: PendingOp,          // COPY or MOVE
    val from: VPath?,           // the common parent, for the subtitle
    val addedAt: Long,
    val pastedTo: List<VPath> = emptyList(),
)

data class ClipTray(val batches: List<ClipBatch> = emptyList())
```

### Why a batch and not a flat list of files

Flattening is the obvious move and it is wrong twice over.

1. **A move and a copy cannot be merged.** They are different verbs with different
   consequences, so a flat list would need a verb per file, and pasting it would be two
   operations pretending to be one.
2. **Provenance is how you tell two batches apart.** "7 from DCIM/Camera" and "3 from
   Download" are legible; fourteen filenames in one list are not. Grouping is the feature, not
   an implementation detail.

Per-file removal inside a batch still exists — see the tray — so nothing is lost by grouping.

### Copy persists, move is consumed — and that is not a preference

- A **move** batch is **single-use**. After a successful paste the source files are gone, so
  the batch cannot be pasted again; it is removed. This is physics, not policy, and it must not
  be made configurable.
- A **copy** batch **stays** until it is cleared, which is the request. Each paste appends to
  `pastedTo`, and the tray shows where it has already landed.

The request asked for this as "an option". It is proposed as the **behaviour**, with one
setting to turn it off (`Clear a copy after pasting it`, default off) rather than an option to
turn it on. A visible list with an explicit Clear explains itself; an option you have to find
before your first use does not.

### Collisions between batches

Two of these are real and need deciding up front, because both silently corrupt the user's
intent rather than erroring:

- **The same path in two MOVE batches.** The second paste fails with "no longer there", which
  reads as a bug. On adding a Cut, any path already in a pending MOVE batch is dropped from the
  new one, and the tray says so once: "3 already queued to move".
- **A MOVE batch pasted into its own source folder.** A no-op that looks like a failure.
  Refused at the pill with a reason, the same way a read-only volume already is.

The same path in two COPY batches is harmless and is left alone.

### Staleness

Batches hold paths, not nodes, so a file can be deleted or renamed between Copy and Paste.
`OpResult` already separates `succeeded`, `failed` and `denials`, so a paste reports per item.
The rule: **a partly-failed batch keeps its failed items and stays in the tray** with them
marked, rather than vanishing. A clipboard that empties itself after a half-failure is how you
lose track of what actually moved.

---

## The surface

### Rejected: a rearrangeable floating side dock

It was the first idea and it should not be built.

- The bottom of a 360dp screen already carries the selection bar, the now-playing strip, the
  nav bar and this pill. A fifth persistent, draggable band is one too many, and it covers a
  file list that is the thing being read.
- "Rearrangeable" means a persisted position, an edge-snap, a collapsed state and a collision
  rule against the four bands above. That is a lot of surface for a feature used in bursts.
- Filet's drag gesture is already bound to split-pane moves, so a drag-into-dock has to
  compete with it.

### Proposed: the pill already there, escalating

`PastePill` is the surface that already appears the instant anything is copied, in the right
pane, with a reason when it refuses. Extend it rather than adding a competitor.

| Clipboard holds | The pill reads | Tapping it |
|---|---|---|
| nothing | not shown | — |
| one batch | `Paste 7 here` | pastes, exactly as today |
| several | `Paste 3 batches here` over a stacked-cards glyph | pastes **all** of them, in order |

In the several case the pill grows a chevron that opens the tray. One batch behaves identically
to today, so the common case gains nothing to learn.

### The tray

A bottom sheet, opened from the pill's chevron and from the pane overflow menu
(`Clipboard (3)`), so it is reachable with nothing copied as well.

```
┌─────────────────────────────────────────────┐
│  Clipboard                      Clear all   │
│─────────────────────────────────────────────│
│  ⧉  Copy · 7 items                      ⌄   │
│     DCIM/Camera · pasted twice              │
│                       [ Paste here ]  [ × ] │
│─────────────────────────────────────────────│
│  ✥  Move · 3 items                      ⌄   │
│     Download/invoices                       │
│                       [ Move here  ]  [ × ] │
│─────────────────────────────────────────────│
│  ⧉  Copy · 1 item                       ⌄   │
│     Music · 1 failed, no longer there       │
│                       [ Paste here ]  [ × ] │
│─────────────────────────────────────────────│
│                        [ Paste all here ]   │
└─────────────────────────────────────────────┘
```

- **"here" is the pane the tray was opened over**, named in the sheet header, so split view is
  unambiguous for the same reason the pill is per-pane.
- Expanding a batch (`⌄`) lists its files, each with an × that removes it from the batch.
  Removing the last one removes the batch.
- A batch on a volume that is not currently reachable is dimmed with the reason, not hidden.

### Drag, kept without the dock

While a drag is running the pill becomes a drop target reading **Add to clipboard**. That is
his drag-into-it idea at the cost of one drop zone on a control that is already on screen,
rather than a dock.

---

## Pasting several batches

`Paste all here` runs the batches **in tray order**, as one job in the ledger with one summary:
`14 copied, 3 moved, 1 failed`. Each batch keeps its own verb; they are not merged into one
operation.

Name collisions inside the destination use the existing per-item conflict policy unchanged. A
collision *between* two batches in the same paste is just the second item hitting a file the
first one wrote, which that policy already covers.

---

## Surviving the process

Today the clipboard dies with the app. For a tray filled over several minutes across several
folders that is a real loss, and for a pending MOVE it is worse than a loss: the files did not
move and nothing says so.

Proposal: persist the tray to prefs as `clip.tray` (paths, verb, timestamps). On launch,
restore it and **prune every path that no longer stats**, reporting the count once. A restored
batch is marked with its age in the tray.

---

## Phasing

**Phase 1 — the feature.**
Batch list in `AppState`, Copy and Cut append, pill count and escalation, the tray sheet,
per-batch paste, paste-all, copies persist with a `pastedTo` count, the two collision rules.

**Phase 2 — the edges.**
Per-file removal inside a batch, drag-to-add, persistence across restart, the
`Clear a copy after pasting it` setting.

Phase 1 is the request. Phase 2 is what stops it being annoying.

---

## What this does not change

- Copy and Cut stay where they are in the selection bar. Only their toast changes:
  `7 copied · 3 batches on the clipboard`.
- `copyPathToClipboard` and the share-paths-as-text action are the **system** clipboard and are
  unrelated; the naming overlap is worth keeping an eye on in the code but not worth a rename.
