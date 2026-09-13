# Filet — Nearby: cross-instance sharing

Opened 2026-09-13. Owns the `NearbyProvider` at L0 and the Share surface at L5.

The thesis, and the thing that makes this different from every other local-share app:

> **A peer is not a transfer target. A peer is a volume.**
>
> Browsing another phone's shared set should be identical to browsing an SD card —
> same pane, same rows, same drag and drop. Sending a file is then just *moving it*,
> and the whole feature falls out of the VFS that already exists.

Every share app on Android is a one-way pipe with its own UI. Filet already has dual panes
and drag-and-drop. Put a peer in the other pane and the interaction is already built.

---

## 1. The three questions, answered

### 1.1 Real folder, fake folder, or both? — **Both, and it is not a compromise**

A **Shared set** is the union of two things rendered as one listing:

| | What | For |
|---|---|---|
| **The real folder** | `/storage/emulated/0/Filet/Shared` | Drop things in. The obvious mental model, and it works with drag-and-drop from the other pane today. |
| **Proxy entries** | Rows in a `share` table pointing at files anywhere on the device | A 4 GB video you are not going to copy just to share it. |

```sql
CREATE TABLE share (
  token    TEXT PRIMARY KEY,       -- random, per-entry, revocable
  node_id  INTEGER NOT NULL REFERENCES node(id) ON DELETE CASCADE,
  alias    TEXT,                   -- optional display name for the peer
  added_at INTEGER,
  expires  INTEGER,                -- NULL = until revoked
  hits     INTEGER DEFAULT 0
);
```

Four rules that make the proxy half actually work:

1. **A proxy stores a `node_id`, never a path.** Same rule as pinned shortcuts (F22). Move the
   file and the share follows it; delete it and the share removes itself via `ON DELETE
   CASCADE`. There is no such thing as a broken share entry.
2. **Nothing is ever copied.** A proxied file is streamed from where it lies, with HTTP range
   requests. Sharing a 4 GB video costs zero bytes of storage and starts instantly.
3. **Proxies are visually distinct.** A small `↗ linked` chip on the row. The user must always
   know what is physically in the folder and what is a pointer — that is the same honesty rule
   as the `truncated` flag on archive indexing.
4. **An unavailable source fails cleanly.** SD card ejected, file deleted between listing and
   fetch ⇒ the peer gets `410 Gone`, not a hang, and the row shows *unavailable* on both sides.

This is one more `FileSystemProvider` composing a real directory with a set of node IDs. The
architecture already does this — it is what `ArchiveProvider` and `ApkProvider` do.

### 1.2 Discovery — "same WiFi or hotspot, automatic"

Automatic is right, and one mechanism is not enough. **Discovery is a strategy stack**, and the
device list merges all three, labelling how each peer was found:

| | Mechanism | Works when | Cost |
|---|---|---|---|
| **1** | **mDNS / NSD** — `NsdManager`, service `_filet._tcp` | Shared WiFi or hotspot | Instant, free |
| **2** | **BLE beacon** — advertise a short instance hash, then hand off to TCP | Multicast is blocked | Low |
| **3** | **Wi-Fi Direct** — `WifiP2pManager` | **No network at all** | Slower to establish |
| **4** | **Manual** — 6-digit code or QR | Everything else failed | Always works |

TXT record carries: instance UUID, display name, device model, Filet version, cert fingerprint,
and whether pairing is required.

**The trap that breaks this in the field:** many Android hotspot implementations and most
enterprise/hotel WiFi enable **AP client isolation**, which silently blocks peer-to-peer traffic
even though both devices are "on the same network". mDNS returns nothing and the user assumes
the app is broken. So:

- Detect it — if mDNS finds peers but TCP connect fails, that is isolation, and it is
  diagnosable. Say so: *"Your network blocks devices from talking to each other. Use Wi-Fi
  Direct instead."* with the button right there.
- **Wi-Fi Direct is the answer to it**, not a fallback nobody finds. It needs no router at all.

### 1.3 Transport

Plain **HTTP/1.1 over TCP**, TLS with a self-signed per-instance certificate.

Why not a bespoke protocol: range requests give resumable transfers for free, and — the part
worth designing for —

> **A laptop with no Filet installed can open the URL in a browser and download the file.**

That single property is what makes "send it to my PC" work with nothing installed on the PC. It
costs nothing because the server already exists.

- Parallel chunked transfer with `Range`, resumable across a dropped connection
- Progress lands in the **job ledger** (PLAN §5.2), the same surface as downloads and indexing
- The server binds to the local interface only, and **auto-stops after idle**

---

## 2. Security — where local-share apps are usually terrible

1. **Nothing is exposed by default.** Sharing is a mode you enter, not a background state. The
   server does not run until you turn it on, and it stops itself when you stop.
2. **Serve by token, never by path.** The HTTP surface is `/f/<token>` where the token resolves
   to a `node_id` in the `share` table. **Paths never enter a URL, so path traversal is not
   mitigated — it is unrepresentable.** Nothing outside the Shared set is reachable, ever.
3. **Pairing uses a short authentication string.** TOFU on the self-signed cert is
   MITM-able on a hostile network, so the first connection shows a **4-digit code on both
   screens** that must match. After that the peer is remembered by UUID + cert fingerprint and
   future transfers are one tap.
4. **Incoming files require acceptance** unless the peer is explicitly marked trusted, and an
   accepted file lands in a quarantine folder, never overwriting silently.
5. **Write access to your Shared folder is off by default** and granted per peer.
6. A **legitimate foreground service** — user-initiated, visible, bounded, with a Stop action.
   This is the case `SEARCH.md` §7.2 rules *in*, not out: the user asked for it and is watching.

---

## 3. The device list, and the interaction

### 3.1 Two surfaces, because there are two intents

| Intent | Surface |
|---|---|
| *"Send these files"* | **Share sheet** — multi-select → Share → peers slide up → tap one → sending. Three taps, never leaves the pane. |
| *"What has that phone got?"* | **Nearby, in the rail** — a place, like a volume. Opens a peer in a pane. |

The second is the one nobody else does. Open a peer in the right pane, your storage in the
left, and **drag between them**. Copy both directions, multi-select, rename in place. Sending
stops being a modal flow and becomes file management.

### 3.2 A peer row

```
●  Phone on the desk        paired · WiFi        12 shared
   Galaxy A54 · 2 m ago                          →
○  Pixel in the bag        new · Wi-Fi Direct    tap to pair
   Pixel 7a · just now                           →
◍  Living room laptop      browser · no app      —
```

- Status dot: paired / new / busy / unreachable
- **How it was found is shown**, because when something does not work that is the first
  diagnostic — and it teaches the user that Wi-Fi Direct exists.
- Last-seen time, so a stale entry is obviously stale rather than silently wrong

### 3.3 Deliberately not built: a merged two-way folder

A folder both devices write into, kept in sync, is **a sync product** — conflicts, tombstones,
deletion propagation, clock skew. That is Syncthing, and it is a different app.

Here: you browse theirs, they browse yours, writes are explicit and permissioned. Ruling this
out is what keeps Nearby a weekend-sized feature instead of a year-sized one.

---

## 4. Browser access — Filet as a tiny personal file server

The HTTP server exists for peers. Pointing a **browser** at it costs nothing extra and is the
single highest-leverage thing in this document:

> **Anyone on the network can get your files with nothing installed.**
> A laptop, a Windows PC, someone else's iPhone, a smart TV. No app, no account, no cable.

This is a first-class feature with its own screen, not a URL buried in settings.

### 4.1 The thing that would ruin it, and the fix

A self-signed certificate makes every browser throw a full-page
**"Your connection is not private"** interstitial. Nobody clicking that trusts what follows, and
half of them stop. So the peer transport and the browser transport have **different threat
models and must not share an endpoint**:

| Endpoint | Transport | Why |
|---|---|---|
| **Filet ↔ Filet** | HTTPS, self-signed, **cert pinned** to the instance UUID | Filet does its own pinning, so there is no warning and no CA needed |
| **Browser** | **Plain HTTP** by default | No interstitial. LAN-scoped, PIN-gated, session-bounded |

Plain HTTP means the transfer is unencrypted on the local network. That is stated plainly in
the share screen — *"Anyone on this network could read what you transfer"* — with a
**Force HTTPS** toggle for someone on untrusted wifi who would rather click through the browser
warning. Default is the one that works; the honest label is what makes it defensible.

Port: fixed default **8321**, falling back if occupied, and the screen always shows the real one.

### 4.2 A PIN, on by default

An open server on a coffee-shop network serving your Shared folder is a genuinely bad outcome.

- The page lists **nothing** until a **6-digit PIN** is entered. The PIN is shown in the app,
  rotates each session, and is rate-limited (5 attempts, then the session dies).
- It can be turned off for a trusted home network. It is **on by default**, because the
  dangerous configuration must never be the one you get by accident.
- The web UI can only ever see the **Shared set**, by token — the same rule as §2.2. There is no
  path in any URL, so nothing outside it is expressible.

### 4.3 Uploads — the sleeper feature

A drop zone on the web page, writing into the phone's Shared folder.

**That is "send a file from my laptop to my phone" with nothing installed on the laptop** — no
cable, no cloud, no account, no Bluetooth pairing. For most people it is the reason they keep
the app.

Off by default, one toggle, and incoming files land in a quarantine subfolder with a
notification rather than dropping straight into the Shared set.

### 4.4 The web page itself

Served from app assets, ~30 KB, no CDN, no framework:

- File list with name, size, type icon, download link
- Multi-select → **download as a streamed zip** (streamed, never buffered to disk — a 4 GB
  selection must not need 4 GB of free space on the phone)
- Upload drop zone when enabled
- **Filet's own Slate palette**, so it is visibly the same product rather than a default
  directory listing
- **Works with JavaScript disabled** for the plain list and single downloads. This is the
  fallback path; it should not itself need a fallback.

### 4.5 The in-app screen

Reached from **Nearby → Share over network**:

```
  ┌──────────────────────┐     http://192.168.1.42:8321
  │                      │     PIN  418 207
  │     [ QR code ]      │
  │                      │     ▸ Allow uploads        [ off ]
  └──────────────────────┘     ▸ Force HTTPS          [ off ]
                               ▸ Stop after idle      [ 30 min ]

  Connected
   • 192.168.1.77  Chrome / Windows   downloaded 3 files    [kick]
   • 192.168.1.31  Safari / iPhone    browsing              [kick]

                                            [  Stop sharing  ]
```

- **The QR is not decoration.** Typing `192.168.1.42:8321` on a phone keypad is awful; scanning
  is the whole difference between "share this" and "never mind".
- The URL is large, monospaced, and tappable to copy.
- **A live client list with a kick button.** If you can see who is connected, an open server
  stops being a thing you forget you left running — which is the actual risk.
- Idle timeout is visible and counts down. The server stopping itself is the default, not a
  thing you must remember.

### 4.6 One honest limitation

The URL is a **private LAN address**. It works for anyone on the same WiFi and for nobody
outside it. There is no tunnel, no relay, no public URL — that would need a server Nic does not
have and a trust model this app should not take on.

The app should say so in one line rather than letting someone try to send the link to a friend
across town: *"Only works for devices on this network."*

---

## 5. Wear OS — a companion, not a port

**Galaxy Watch 4 Classic: Wear OS 3+, API 30+, ~1.4".**

Straight answer: **a file manager on a watch is a bad product.** Browsing a filesystem on a
1.4" round screen is worse than not having it, and building it would consume the time that
makes the phone app good.

What is genuinely useful on the wrist — and it is a real feature, not a consolation:

| Tile / surface | Why it earns the screen |
|---|---|
| **Incoming transfer** — accept / reject | A file arrives from a peer and you approve it from your wrist without unlocking the phone. This is the one that justifies the whole companion. |
| **Activity glance** | The job ledger, small: indexing 30 %, download 62 %, transfer to the laptop. *What is my phone doing.* |
| **Run a recipe** | A tile bound to a saved Lua recipe (`SEARCH.md` L4) — one tap runs it on the phone. |
| **Recent files** | Names and status only. Read-only, no browsing. |

**Mechanism:** the **Wearable Data Layer API** — `MessageClient` for actions, `DataClient` for
the ledger snapshot. It rides the Bluetooth bridge, so it needs no network, no pairing of its
own, and no server. A standalone Wear app with its own networking would be a second product.

Scope is roughly four screens and a tile. Small, useful, honest — and it keeps `PLAN.md` R4:
the README will say *"Wear OS companion: approve transfers and watch progress"*, not
*"Wear OS support"*, because the latter would imply a file browser that does not exist.

---

## 6. Milestone fit

This shares its machinery with `NetProvider` (SMB/SFTP) — both are "a remote thing behind the
VFS" — so the remote-provider seam is built once.

| M | Scope |
|---|---|
| **M8** | Remote-provider seam, shared with SMB/SFTP |
| **M9a** | Shared set (real folder + proxy table) · HTTP+TLS server · token serving · browser-download URL |
| **M9b** | mDNS discovery · pairing with SAS · device list · share sheet |
| **M9c** | `NearbyProvider` — a peer as a pane, drag-and-drop both directions |
| **M9d** | Wi-Fi Direct fallback · AP-isolation detection and the honest error |
| **M10** | Wear OS companion — transfer approval, activity glance, recipe tile |

**M9a alone is already shippable and useful**: expose a folder, open the URL on a laptop, done.
Nothing after it is required for that to be worth having, which is the right shape for a
feature this size.

---

## 7. Open questions

- [ ] Instance identity: derive the UUID from the signing key, or generate per-install? Per-install
      is more private; key-derived means a reinstall keeps existing pairings.
- [ ] Does the Shared folder live at `/storage/emulated/0/Filet/Shared` or inside app-specific
      storage? The former is visible to other apps and easier to drop into; the latter is cleaner
      on uninstall.
- [ ] Watch 4 Classic specifically — confirm the installed Wear OS version on his unit before
      writing any of M10 against an API level.
