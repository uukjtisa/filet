# Locker upgrades: the design cemented, Keep unlocked, a Home of its own

Status: **plan, approved 2026-11-16**. Nothing here is built. Read against the tree at `49ead2b`.

Paths: `L/` = `app/src/main/java/dev/niccc2007/filet/locker/`, `F/` = `app/src/main/java/dev/niccc2007/filet/`.

## 1. What the Locker is today

- **It is encrypted, not hidden.** Every file is sealed on its own with AES-256-GCM in 64 KB
  chunks (`L/LockerCrypto.kt`). One random 256-bit file key seals them all; the password, stretched
  by PBKDF2-HMAC-SHA256 at 210,000 rounds, seals that key (`L/LockerVault.kt`).
- **Nothing ever decrypts the whole Locker.** Unlocking opens the index (names and folders) and
  nothing else. Opening a file decrypts only the chunks being read; a 2 GB video seeks straight
  to its chunk. A "second level, per file" is therefore how the first level already works.
- **There is no stored password hash.** The sealed key is its own check: only the right password
  opens it. Deleting the key file already makes every sealed file unreadable by anyone, Filet
  included, so a rule that wipes everything when a hash goes missing adds no protection. It only
  adds a way for one damaged file to erase every other one.

### The faults found

| # | Fault | Who can use it |
|---|---|---|
| 1 | Lock leaves up to 24 MiB of decrypted pieces in the stream server's cache, and its tokens live on (`F/stream/StreamServer.kt`, `F/stream/StreamTokens.kt`) | Root, or code in the process, after lock |
| 2 | Lock waits while any Locker stream is open, so a paused video keeps the key in memory and the Locker reads Open after Lock | Anyone holding the unlocked phone |
| 3 | A copy of the Locker folder can be guessed offline on a PC. A 6-letter lowercase password falls in about 2 hours on one high-end graphics card (estimate, not measured) | Root, or a forensic copy |
| 4 | No limit on wrong tries | Anyone holding the phone |
| 5 | With the key file gone, set-up is offered again, and the next unlock's `sweep()` deletes every old sealed file | Any damage to one small file |
| 6 | A damaged index reports "That is not the password" | Any damage |
| 7 | Blob contents can be swapped between entries, and an older index restored, without detection | Root with write access |
| 8 | Every file added rewrites and fsyncs the whole index: about n²/2 × 200 bytes for a batch of n | Nobody; it is the real slowness |

What no design can stop, and the screens say so: anything with root reading what is open while
the Locker is open; rollback by root; the count and sizes of sealed files; flash residue of
originals moved in.

## 2. Decisions (2026-11-16)

| Question | Decision |
|---|---|
| Copied folders guessed offline | Tie the Locker to this phone's secure chip. A recovery key or a sealed backup file carries it elsewhere |
| Key file missing or damaged | Say so, never delete anything on its own. Erase only after a typed word |
| Wrong tries | A growing wait, and a recovery pop-up after a wrong try when a recovery way exists |
| Password rule | A strength meter, never enforced. The 6-character minimum stays |
| A second password per file | Not built. One password already seals every file separately |
| Keep unlocked | Two holds: **In Filet** and **Everywhere**. Everywhere survives Filet being closed; a phone restart always locks |
| Card figures while locked | Hidden by default, with a switch to show them |
| Extras | Block screenshots, lock from anywhere, send into the Locker, sealed backup file |
| Sharing a Locker file | Fast share and Android Send. Not the nearby list, not Portal |

## 3. The design

### 3.1 Keys

The file key `MK` does not change, so every file sealed today stays readable. The `key` file
becomes version 2: a table of slots, each wrapping `MK`, with every parameter (version, rounds,
salt, slot kind, tie flag) inside the slot's associated data, so none of them can be altered
without the slot failing to open.

| Slot | Wraps MK under | Tied to the phone |
|---|---|---|
| Password | `HMAC_keystore(PBKDF2(password))` when tied, else `PBKDF2(password)` | Yes, when a secure chip exists |
| Recovery | HKDF of a random 24-character code (120 bits), shown once | Never: it must work after the phone is gone |
| `bio` file (exists) | A fingerprint-held keystore key | Yes |
| `hold` file (new) | A keystore key needing no fingerprint, valid until the hold's deadline | Yes |

- **The tie.** A non-exportable Keystore HMAC-SHA256 key (StrongBox where present, else the TEE)
  is applied to the PBKDF2 output, so every password guess needs the chip and a copied folder is
  useless anywhere else. Decision: a keyed step inside the derivation, not a second wrap around
  the slot. A second wrap fails because root can remove it with one keystore call and then guess
  offline as before. When the keystore is software-only (`KeyInfo`), the tie is not offered and
  the screen says so.
- **Rounds** are calibrated once at set-up by a pure `Kdf.rounds(msPer100k, targetMs, floor)`,
  with a floor of 210,000 tied and 600,000 untied. PBKDF2 stays: no new dependency.
- **New files** use format `FLK2`: a per-file key `HKDF(MK, blobId)` with the blob id in the
  associated data, which ends blob swapping and the shared nonce prefix. `FLK1` is read forever;
  nothing is re-sealed by force.

### 3.2 Lock

- Readers are cut at once: the vault tracks open input streams and closes them on lock. A copy
  into or out of the Locker still finishes, so a lock never cuts a file in half; the strip says
  "Locks when the copy finishes" rather than Open.
- `onLocked` empties `StreamServer`'s piece cache and drops Locker tokens; the server refuses a
  cached Locker piece while locked.
- The extra copy of the key made in `unlock()` is wiped.

### 3.3 Health instead of silent loss

Pure `LockerHealth.of(keyFile, keyBak, aliasPresent, index, indexBak, blobCount)` returns
`NONE | OK | KEY_MISSING | KEY_DAMAGED | TIED_ELSEWHERE | INDEX_DAMAGED`, read by an exhaustive
`when` that draws one screen for each.

- `create()` refuses while sealed files exist (fault 5).
- A damaged index has its own message (fault 6). `key.bak` and `index.bak` are kept beside the
  originals. When the index came from the copy, files it does not know are adopted as
  "Recovered file N", their type sniffed from their first bytes, instead of being swept.
- Erase needs a typed word.

### 3.4 Wrong tries

Pure `LockerAttempts.wait(fails, lastFailAt, now)`: 5 free tries, then 30 s, 1 min, 5 min,
15 min and 1 h. Stored in `locker/attempts`. After a wrong try, when a recovery slot exists, a
pop-up offers "Open with the recovery key". Once open, one line reports the wrong tries and when.

### 3.5 Index speed

A sealed snapshot plus an append-only sealed journal (`index.log`): one small fsynced record per
change instead of the whole index. The record is on disk before a move deletes its source. The
snapshot is rewritten at lock or past 256 KB; a torn last record is dropped. `LockerIndex` gains a
parent map and running totals, so listing a folder and the card's figures cost nothing.

## 4. Features

### 4.1 Keep unlocked

Reached from the Locker Home's header and the Locker card's menu. A sheet with three wheels
(hours, minutes, seconds) as a clock app's timer has them, a lever between the two holds, an info
button, and Start. While a hold runs: "Open · 12:34 left" and Stop.

- `ui/Wheel.kt`: a snapping wheel with a haptic tick; the arithmetic in a pure `WheelMath`.
- Pure `HoldRule.locks(event, hold, prefs)`, exhaustive over `LEFT_FOLDER`, `LEFT_FILET`,
  `SCREEN_OFF`, `TIMER_UP`, `LOCK_PRESSED`, `PROCESS_START`, `REBOOT`.
- **In Filet:** stays open across tabs and folders; leaving Filet, or the screen going off, locks.
- **Everywhere:** the `hold` file seals `MK` under a keystore key with an end date. The deadline is
  kept as `elapsedRealtime` with `Settings.Global.BOOT_COUNT`, so changing the clock does nothing
  and a restart locks. An alarm deletes the file at the deadline if Filet is not running. A quiet
  ongoing notification, "Locker open · Lock now", shows for the whole hold. The info button says
  plainly that anyone holding the unlocked phone gets in until the time is up.
- The last duration and hold are remembered (declared in `F/data/ConfigKeys.kt` with `act()`).

### 4.2 Recovery

An optional step in set-up: "Make a recovery key" or "Skip". Skipping on a tied Locker shows one
plain line: the files then exist only on this phone. Inside the Locker: make, replace, remove.
Using it: the code, then a new password; the password slot is written again and tied again.

### 4.3 Fingerprint, findable

A tick at set-up; a row in the Locker's settings; "fingerprint" and "biometric" in the settings
search; turning it on while locked asks for the password in place.

### 4.4 Rooted phones

`rooted()` also counts a granted root shell. The words: sealed files stay sealed, and a copy is
useless off this phone; while the Locker is open, an app with root can read what is opened. At
set-up, and as one line on the Locker Home that can be dismissed.

### 4.5 Strength meter

Pure `Strength.estimate(password, tied)` gives words ("minutes on a PC", "safe on this phone").
Beside set-up, change password and backup export. It never blocks.

### 4.6 Thumbnails, and opening without a decrypted copy

`LockerFd`: a seekable descriptor from `StorageManager.openProxyFileDescriptor` (API 26, the
minimum) over a random-access chunk reader with a small chunk cache. It gives video frames and
audio art through `MediaMetadataRetriever`, PDF first pages, and lets a PDF open with no copy in
scratch. Thumbnails are kept sealed in `locker/thumbs/<blobId>` under `HKDF(MK, "thumb" ‖ id)`
and deleted with their file; `ThumbDisk` still refuses every Locker path. Pictures decode from one
open instead of three. Known limit: an APK keeps a plain icon, since reading its icon needs a path.

### 4.7 Locker Home

A pane kind declared through `SpecialPanes`, whose exhaustive `when`s force its route, icon,
search, refresh, switcher and rail rows. Sections: a header (state, count · size, the hold's
countdown, Lock, Keep unlocked, ⋮), the wrong-tries line, categories (Pictures, Videos, Audio,
Documents, Other), New in the Locker, Files, and a gallery through `CategoryGallery` fed by a pure
`LockerFeed` over the index. The ⋮ opens the same `LockerSettings` that Settings › Storage shows.

### 4.8 Card figures

Open: "214 files · 3.2 GB", from the running totals; folders are not counted. Locked: "Locked",
unless "Show count and size while locked" is on, when the figures come from the sealed files'
sizes (`LockerCrypto.plainSize`) without opening anything. App-folder and drive cards take their
figures from the folder-sizes engine (`filet-folder-sizes-plan.md`) once it keeps records; a card
with no honest figure shows none.

### 4.9 Extras

- **Block screenshots:** `FLAG_SECURE` while a Locker path or form is on screen, decided by a pure
  `SecureRule`; a switch, on by default.
- **Lock from anywhere:** a Quick Settings tile, the hold notification's Lock, and "Lock when the
  screen turns off".
- **Send into the Locker:** a share target named Locker for one or several files, and a camera
  button whose shot lands in scratch, is sealed, and is wiped.
- **Sealed backup file:** `.filetlocker` holds the slots re-wrapped without the tie, the sealed
  index and the sealed files exactly as they are, so nothing is re-encrypted and it is fast.
  Export and import run as Activity jobs. Export shows the meter and offers a separate password,
  since this file can be guessed offline.
- **Sharing:** pure `ShareRule.allowed(kind, path)` read by the hold menu, More and the viewer
  menus alike. A Locker file offers Fast share and Send; the nearby list and Portal are hidden.
  A lock ends any share of a Locker file.

## 5. Stages

Each ships alone: tests, a verify on a clean tree, install, a short device check.

| # | Stage | Screens | Waits on |
|---|---|---|---|
| L1 | Lock cuts readers, stream cache emptied, no silent sweep, honest index message, `.bak` copies | Words only | Nothing; first |
| L0 | Mock round 2 (`docs/design/mockups/filet-locker-mock.html`) and its translation spec | Mock | Approval gates L3, L4, L6, L7 |
| L2 | Key file v2 and slots, the tie (a `LockerBind` interface, faked in tests), recovery slot, `FLK2`, journal, parent map, random-access reader | None | Built during the mock's review |
| L3 | Wrong tries, recovery, health screens, meter, set-up additions | Yes | L0 |
| L4 | Keep unlocked | Yes | L0 |
| L5 | `LockerFd`, thumbnails, sealed thumbnail cache | Small | Nothing |
| L6 | Locker Home, card figures, settings, fingerprint findable, rooted words, block screenshots | Yes | L0 |
| L7 | Tile and screen-off lock, share target and camera, backup file, sharing rule | Yes | L0 |
| L8 | Figures on app-folder and drive cards | Small | The sizes engine's records |

Tests per stage: slots and the v1 → v2 migration, a torn journal, lock cutting a reader, the
stream cache after lock, `LockerHealth`, `LockerAttempts`, `HoldRule`, `WheelMath`, `Strength`,
`ShareRule`, `SecureRule`, `LockerFeed`. `tools/check-locker.mjs`, with its selftest: no Locker
path reaches `ThumbDisk` or any plain cache, and `SecureRule` has one caller.

L2 changes how the key is held. It gets a second review of its diff before it is committed: a
fault there can lock the files out for good, or weaken them without any visible sign.

## 6. Offered and not taken

Logged as ideas: a second safe inside the Locker with its own password; erase after N wrong tries;
a decoy password opening an empty Locker; Argon2id in place of PBKDF2; padding sealed files so
their sizes leak less.
