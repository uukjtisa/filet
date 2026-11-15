# SD cards and USB drives - translation spec

Source of truth: `docs/design/mockups/filet-drives-mock.html`. Cited by the function or rule that
draws each thing, not by line, because the mock is edited in place: `sdTile`, `usbTile`,
`goneLine`, `openGone`, `openMenu`, `.st .dot`, `@keyframes arrive`.

## Decisions, in order

1. **Their own cards.** An SD card and a USB drive each get their own icon (`sd`, `usb` in the
   mock's icon table) and are named by what the system calls them - `SD · SanDisk`,
   `USB · Kingston` - rather than by the volume ID. A rename still wins, as on every drive.
2. **An eject mark** on both (`eject:true`). It opens Android's storage settings, because only the
   system can unmount a volume.
3. **While Filet writes to one**, the dot breathes amber and the caption turns amber:
   `Copying 3 files · don't unplug` (`usbTile`, `S.writing`).
4. **A format the phone cannot read** stays as a card: value `Can't read` in the soft colour,
   caption `Its format isn't one this phone reads` (`S.ntfs`).
5. **Older phones (Android 8 to 10)** cannot write to an SD card until it is allowed once. The
   caption is `Read only · Allow writing` in the accent (`.cap.act`), and Allow writing opens the
   system folder picker on that card. Afterwards the card opens the granted tree, so copy, move and
   delete work there, and no second card is drawn for the same volume.
6. **The registry.** Every SD card and USB drive is remembered by its volume ID. Pulled out, its
   card goes and one line says what is not plugged in (`goneLine`); the line opens a sheet with
   each drive's size, when it was last seen and how many of its files are indexed, and Forget
   (`openGone`). Plugged back in, it is the same drive: its name, its index, its place on Home -
   all keyed by the path `/storage/<volume ID>`, which does not change between plugs.
7. **Grouped by kind** is an option of Home's edit state, built earlier; SD cards and USB drives
   land under Plugged in.
8. **Indexing.** An SD card is indexed like the phone's own storage. A USB drive is indexed only
   when asked, from its hold menu.
9. **A hidden drive shows again by itself** the moment it is plugged in.
10. **Forget** removes the drive from the registry and deletes its index.
11. **OTG.** When a USB storage device is attached and no drive shows up for it at all, a line
    says some phones need OTG turned on in Settings. A drive that shows up and cannot be read has
    its own card (4), so the line is not drawn beside it.

## Tokens

| Thing | Value |
|---|---|
| Busy dot | 6dp circle, `warn`, breathing 1.0 → 0.35 alpha, 1100ms linear, reversing (`.st .dot`) |
| Caption, writing | `warn` (`.cap.warn`) |
| Caption, Allow writing | `accent`, semibold (`.cap.act`) |
| Value, can't read | `fg2` (`.val.soft`) |
| Not-plugged-in line | `raised` background, 11dp radius, 9dp × 12dp padding, 15dp icon in `fg3`, 11.5sp text in `fg2` with names in `fg` semibold, chevron 14dp `fg3` (`.gone`) |

## Motion

| Name | Duration | Delay | Easing | Fill | Repeat |
|---|---|---|---|---|---|
| `arrive` - a plugged-in drive's card: scale 0.92 → 1, alpha 0 → 1 | 180ms | 0 | ease-out | both | once |
| Arrival ring: 2dp accent ring fading to transparent | 1200ms | 600ms | ease-out | - | once |
| `breathe` - the busy dot | 1100ms | 0 | linear | - | infinite, alternate |

Reduced motion: the card appears without the scale; the ring is skipped.

## Hold menus (`openMenu`)

- SD card: Open · Rename · Details · Allow writing (older phones, until granted) · Safely remove ·
  Hide from Home.
- USB drive: Open · Rename · Details · Safely remove · Hide from Home, plus Index this drive /
  Stop indexing this drive (decision 8).

## Behaviour that a screenshot does not show

- Detection is live: a mount, unmount, removal or eject broadcast reloads the cards, so plugging a
  drive in needs no restart.
- The registry is this phone's record and is not exported; another phone has seen other drives.
- A drive is never mistaken for another: the volume ID is the identity, and a drive with no ID is
  shown but not remembered.
- Nothing is written to the drive to identify it.

## Acceptance checklist

- [ ] SD card and USB drive cards with their own icons and the system's names.
- [ ] Eject mark opens storage settings; Safely remove in the hold menu does the same.
- [ ] Amber dot and `don't unplug` caption while a job writes to the drive.
- [ ] `Can't read` card for an unreadable format.
- [ ] Read only · Allow writing on Android 8 to 10; after the grant the card opens the tree.
- [ ] Unplugged drive: card goes, the line appears, the sheet lists it with Forget.
- [ ] Plugged back in: same name, place, hidden state cleared.
- [ ] USB drives skipped by indexing until asked; SD cards indexed.
- [ ] Forget deletes the index under the drive.
- [ ] OTG line when a USB storage device is attached and nothing can be read.
