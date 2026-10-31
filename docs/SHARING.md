# Sharing: permissions, quota, previews

What a hosted share offers, what it refuses, and what it tells the other side about itself.

`PLAN.md` owns the layer model and `NEARBY.md` owns browser sharing. This is the design for the
WebDAV hosting side: four features that arrived as one request, plus two faults found while
measuring it.

The short version of the whole document: **two of these are protocol work and two are policy
work, and mixing them is how a permission gets half-enforced.**

| | What it is | Where it lives |
|---|---|---|
| Free space and total size | a standard nobody implemented (RFC 4331) | protocol — `DavXml` |
| Previews | an endpoint only another Filet asks for | protocol — `WebDavServer` |
| Per-path blocks | a decision about a path | policy — one gate, L2 |
| Transfer cap | a decision about a running total | policy — same gate |

---

## 0. Two faults to fix first

Neither is a feature. Both undermine everything below, so they land before any of it.

### 0.1 Writes under 256 KB are destroyed — data loss

The connection loop reads the body of **any** request with a `Content-Length` in
`1..MAX_BODY_READ` (256 KB) into a `String`, because PROPFIND and LOCK need to parse XML:

```kotlin
val body = if (length in 1..MAX_BODY_READ) readExactly(input, length.toInt()) else ""
if (length > MAX_BODY_READ && method != "PUT") drain(input, length)
handle(method, target, headers, body, input, out, address, length)
```

`put()` is then handed that same, already-drained `input` and reads nothing from it — **after**
`openWrite(append = false)` has truncated the target. So:

- a file **under** 256 KB is emptied and the save fails
- a file **over** 256 KB writes correctly, because that body is deliberately left in the stream

`filet.txt` is 5,992 bytes. That is the whole of "it didn't persist", and the size threshold is
why it looked like a permissions problem rather than a broken write.

**Fix.** The loop stops guessing. Bodies are read by the handler that wants one:

- a body-consuming verb (`PROPFIND`, `PROPPATCH`, `LOCK`) asks for its body as a string
- `PUT` gets the raw stream and is the only thing that touches it
- anything else drains whatever is left before the next request line is read

and three framing cases the current loop does not handle at all:

- **`Transfer-Encoding: chunked`** — no `Content-Length`, so today `length` is 0, the loop reads
  nothing, and `put` truncates the file to zero and answers `201 Created`. Same data loss, no size
  threshold.
- **`Expect: 100-continue`** — the client waits for `HTTP/1.1 100 Continue` before sending a byte.
  We never send it, so the client waits out its own timeout.
- **truncate last.** `openWrite` must not destroy the existing file until the body has actually
  started arriving. A failed PUT should leave the old file intact, which today it does not.

Tested as a pure function over framing headers (`DavBody`), not by driving a socket: the decision
is "given these headers, who reads the body and how much", and that is the thing that was wrong.

### 0.2 The write toggle is a dead switch

`DavShares.decode` forces `writable = false` on load. Introduced in N129 with a defensible reason —
turning a phone into a writable drive should be a decision for the session in front of you — but
now that shares are stored and auto-start on launch, it means **the editor's toggle does nothing
that survives a restart.** Set it, restart, it is off, and only the card's own row can turn it on.
That is R1, and it is why writing "feels like it's still read".

The rule was written when a session was something a person started by hand thirty seconds earlier.
Auto-start broke that assumption, so the rule follows the assumption rather than the other way
round: **access level persists, and the cost of that is paid in visibility** — §1.

### 0.3 The naming

"Let the PC change files" is wrong twice: the client can be another phone, and after §1 it is one
setting among several. It becomes an **access level** on the share, named for what the share is
rather than for who is asking.

---

## 1. Access levels and per-path rules

### The model

Every share has one **base level**, and any number of **rules** that override it for a subtree.

| Level | PROPFIND / GET | PUT / MKCOL | DELETE / MOVE |
|---|---|---|---|
| `HIDDEN` | not listed, 404 | no | no |
| `READ` | yes | no | no |
| `WRITE` | yes | yes | no |
| `FULL` | yes | yes | yes |

Four levels rather than a set of flags, because they are genuinely ordered — there is no useful
"can delete but not read" — and an ordered level can be compared, inherited and reasoned about in
one function. Flags would need a truth table and would let nonsense be expressed.

`HIDDEN` returns **404, not 403**. A 403 confirms the path exists, and the case this is
actually for is a folder nobody else is meant to know about — so the point is that it does not
appear at all.

### Inheritance, and the thing it is really for

A rule on a path applies to that path **and everything under it**. The most specific rule wins:

```
base                    = FULL
/Pictures               = READ          ← the rule
/Pictures/Screenshots   inherits READ
/Pictures/Private       = HIDDEN        ← deeper, more specific, wins
```

The requested behaviour — *"it will make the parent folder also inherit it so they cant mass
delete a folder"* — is the **upward** half, and it is a different rule:

> **A folder may only be deleted when every path beneath it permits deletion.**

So a `READ` rule anywhere inside `/Pictures` makes `DELETE /Pictures` fail, even though the
folder's own level is `FULL`. The protected file is not silently skipped and it is not silently
destroyed; the recursive delete is refused, and deleting has to be done deliberately, deeper down.

That is a real cost and it is the point: mass deletion is exactly the accident worth making
impossible. It also means `DELETE` on a directory has to walk it before acting, which is why the
walk is bounded and a folder too large to verify is refused rather than assumed safe.

### Where it is enforced

**One gate, in front of the VFS, and nothing else consults it.**

```
request → DavPath.resolve → ShareGuard.check(share, rel, verb) → VFS
```

`ShareGuard` is pure: a share's rules, a relative path, a verb, and an answer. No Android, no
sockets, no coroutines — so every case is a unit test. This is the same discipline `DavPath` is
built on, and for the same reason: a permission check scattered across nine handlers is a
permission check with a hole in it.

The three ways this leaks if the gate is not the only path:

- **listings** must filter `HIDDEN` children, or PROPFIND advertises what GET then refuses
- **the flat `~Find` views** reach real paths from the index, bypassing the tree walk entirely —
  they must be filtered through the same gate or they are a way around every rule
- **`MOVE`/`COPY`** name two paths and must satisfy the gate at **both**, or a file is laundered
  out of a restricted folder into a permitted one

The third is the one that would actually be forgotten, and `check-webdav` gets an assertion for
each.

### Setting a rule

From the browser, not from a settings screen: long-press a file or folder → **Sharing** → the four
levels, with the share it applies to. A rule set on a path that no share covers is stored anyway
and takes effect if one later does — the alternative is a rule that silently evaporates.

The hosting card grows a **Restrictions** line per share: `3 rules · 1 hidden`, opening a list
that can revoke them. Rules are per share, not global: `HIDDEN` on the whole-phone share and
visible on a folder share is a coherent thing to want.

---

## 2. Free space and total size

RFC 4331. Two properties on a PROPFIND of a collection:

```xml
<D:quota-used-bytes>...</D:quota-used-bytes>
<D:quota-available-bytes>...</D:quota-available-bytes>
```

This is what draws the used/free bar on a mapped drive, and its absence is part of why a mounted
phone does not read as a real volume. `LocalProvider` already has `usableSpace` and `totalSpace`,
so the values exist; nothing consumes them.

Two decisions:

- **`available` is the smaller of the real free space and what the transfer cap still allows**
  (§3). If a share may write 2 GB more and the phone has 40 GB free, the honest answer is 2 GB —
  otherwise the desktop starts a 5 GB copy that is guaranteed to fail halfway.
- **`used` is the volume's used bytes, not the share's.** Summing a subtree means walking it on
  every PROPFIND of every folder, which is the `~Find` mistake in a new place. A share rooted at a
  folder reports the volume it lives on, which is what free space means to the thing asking.

Cached briefly, for the same reason the view snapshot is: Explorer asks per folder.

---

## 3. The transfer cap

A ceiling on how much a share moves in total, tracked from what clients actually read, wrote
and deleted.

### What is counted

| Verb | Effect on the ledger |
|---|---|
| `GET` / `HEAD` | bytes **sent**, counted on completion |
| `PUT` | bytes **received** |
| `DELETE` | bytes **freed** — the size before it was removed |
| `MOVE` within a share | nothing; no bytes crossed the wire |
| `COPY` | bytes written; a copy consumes space |

**Two separate numbers, not one.** Conflating them makes a share that only ever sends look like it
is filling the phone:

- **`egress`** — bytes sent to clients. The cap that answers how much this share may send.
- **`net written`** — `written − freed`, which can go negative. The number that informs
  `quota-available-bytes`.

`DELETE` crediting back is what *"computed through computing the history of deletes and writes by
others"* asks for, and it is the part that makes the cap usable rather than a one-way ratchet: a
client that uploads a file, deletes it, and uploads a different one has not consumed twice.

### Checked before, committed after

A `PUT` announces its size in `Content-Length`. The gate refuses **before** a byte is written:

```
if (ledger.egressOrWriteWouldExceed(share, incoming)) → 507 Insufficient Storage
```

`507`, not `403`. Explorer shows "not enough space", which is true and actionable; a 403 reads as
a permission fault and sends someone hunting the wrong setting.

A `PUT` with no length (chunked) is metered **as it streams** and aborted at the ceiling — the
partial file is removed, because a truncated file left behind is worse than a refused one.

### Where the ledger lives

In memory, per share, reset when the share stops. **Not persisted**, and that is deliberate:

- the cap's job is to bound *a session*, not to be an accounting system
- persisting it means a cap that can be exhausted permanently by a bug, with no way back except
  clearing app data
- the numbers it is built from — free space — are themselves live

If a lasting budget is ever wanted, it is a different feature with a different name, and it should
say so on the card rather than quietly reusing this.

The card shows it as it runs: `1.4 GB sent of 5 GB · 240 MB written`.

---

## 4. Previews, and the honest split

**Windows, macOS and Linux cannot be sent thumbnails.** WebDAV has no thumbnail request; those
clients build a preview by downloading the file and rendering it locally. Nothing can be pushed to
them, and a switch claiming otherwise would be a dead switch under R1. What actually makes their
previews work is byte ranges and correct content types — `DavRange`, already shipped and verified
working (`206`, correct `Content-Range`).

**Filet to Filet is different**, because both ends are ours.

### The endpoint

```
GET /a/<code>/~thumb/<url-encoded-rel>      → image/jpeg, or 404
```

Under `~thumb`, a sibling of the existing `~Find` reserved name, so it cannot collide with a real
folder. The generator already exists: `Thumbnails.forWeb(context, vfs, path)`, which Nearby's web
UI uses, covering **images, video frames and APK icons** — which is his list.

Advertised so a client knows not to ask blindly, via a custom property on each PROPFIND entry:

```xml
<F:thumb xmlns:F="urn:filet">1</F:thumb>
```

A custom namespace, which other clients ignore by specification. A desktop sees a property it does
not know and carries on; `WebDavProvider` sees it and knows a preview exists without a round trip
per file to find out.

### Rules

- **Through the same gate.** A preview of a `HIDDEN` file is that file's contents in miniature.
- **Counted as egress**, at its own small size.
- **Per share, configurable**, and off for a `HIDDEN`-heavy share by choice — generating previews
  is decode work on a phone, and a desktop scrolling a 2,000-file folder would ask for all of them.
- **Bounded concurrency.** A grid scroll asks for hundreds at once; this is the one place where
  the new unbounded-ish connection pool could actually hurt, so previews get their own small
  semaphore rather than a thread each.

---

## 5. Order of work

Each step is shippable and verifiable alone.

| Step | Why here |
|---|---|
| **1** Body framing (§0.1) | data loss; everything else writes through it |
| **2** Access level persists + renamed (§0.2, §0.3) | the dead switch, and the vocabulary §1 needs |
| **3** `ShareGuard` + rules + listing filter (§1) | the gate every later step routes through |
| **4** Quota properties (§2) | small, standalone, needs §3's number to be honest |
| **5** Transfer ledger + 507 (§3) | needs the gate |
| **6** Previews (§4) | needs the gate; the generator already exists |

Steps 1 and 2 are bug fixes and should not wait for the rest.

## 6. What gets a test, and what gets a checker

Pure, so unit-tested exhaustively:

- `DavBody` — who reads the body, given the framing headers
- `ShareGuard` — level resolution, most-specific-wins, the recursive-delete veto, MOVE/COPY needing
  both ends
- `TransferLedger` — credit on delete, the negative case, the boundary at the cap
- `DavQuota` — the smaller of free space and remaining budget

Checker assertions, because these are the ways the gate gets bypassed rather than broken:

- every VFS-touching handler routes through `ShareGuard`
- listings filter `HIDDEN`
- the `~Find` views filter through the same gate
- `MOVE`/`COPY` check both paths
- `PUT` does not truncate before the body arrives
- the connection loop does not read a `PUT` body
