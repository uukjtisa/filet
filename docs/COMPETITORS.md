# 2026 Competitor Landscape — Tools Overlapping the All-in-One Android Power App

> **Compiled:** 2026-10-19. Every price, rating, and feature claim below is **as-observed on that date** and will drift — re-verify before citing in any external document. This is **competitive research for Filet**, not a document about Filet itself.
>
> **Target bundle under comparison:** archives (zip/tar.gz/7z/rar read, AES zip, splits) · smali/APK read + re-sign · hex editor · image editor (crop/rotate/draw/text) · audio/video/PDF/Office tag editing · text editor (TextMate + tree-sitter) · sandboxed Lua over a file abstraction layer · LAN file server with browser access.

---

## A. Archive tools on Android

| Product | Platform | Price (verified) | License | Mobile/Desktop | Strengths | Known complaints | URL |
|---|---|---|---|---|---|---|---|
| **ZArchiver** (ZDevs) | Android only | Free; "Donate" tier exists but **Google payments for Russian devs are suspended — currently unpurchaseable** | Proprietary | Mobile | Broadest format list on Android: creates 7z/zip/tar/gz/xz/lz4/zst, extracts ~40 formats incl. RAR/RAR5; password archives; multi-part create (7z) and extract (7z/zip/rar splits); partial extraction; multithreaded; APK/OBB install from archive; no internet permission | Dated UI; tap-vs-long-press quirks; archive *editing* and password storage are Donate-only features (and Donate can't be bought); one 2021 review reports file corruption on overwrite-with-smaller-file (old) | [Play](https://play.google.com/store/apps/details?id=ru.zdevs.zarchiver) · [Donate](https://play.google.com/store/apps/details?id=ru.zdevs.zarchiver.pro) · [Android Authority](https://www.androidauthority.com/the-best-zip-and-unzip-apps-for-android-258692/) |
| **RAR** (RARLAB / win.rar GmbH) | Android | Free, ad-supported; ad-removal IAP exists (amount not published on vendor pages; Android Authority listed "$0.99 yearly" — treat as **unverified**) | Proprietary (free app) | Mobile | **Only Android app that creates RAR/RAR5** incl. multi-volume splits; only one that repairs damaged ZIP/RAR with recovery records; AES-256 password encryption; creates ZIP too; benchmark tool | Ads incl. fullscreen; 2026 review complains about new "subscription/commercials" direction; UI less polished than ZArchiver | [Play](https://play.google.com/store/apps/details?id=com.rarlab.rar) · [rarlab.com](https://www.rarlab.com/download.htm) |
| **"7-Zip for Android"** | — | — | — | — | **There is no official 7-Zip Android app.** No Play listing by "Mark McA" could be verified — **could not verify this product exists**. Closest real things: [a7zip](https://github.com/seven332/a7zip) (extract-only p7zip wrapper, a library not an app), [Zee Archiver](https://play.google.com/store/apps/details?id=com.mg.zeearchiver) (FOSS, p7zip-based, small userbase), and "7Z: Zip 7Zip Rar File Manager" (Sociosoftware, 3.2★, freemium) | — | [7-Zip-on-Android explainer](https://legado.redclade.org/redclade-news/7-zip-on-android-everything-you-need-to-know-1764797459) |
| **MiXplorer / MiXplorer Silver** (Adel-Dubai) | Android | MiXplorer free off-Play (XDA); Silver is a paid Play bundle (price not shown in sources fetched — **unverified**) | Proprietary, freemium | Mobile | File manager with 7z/zip/tar/gz/xz/zstd create + huge extract list; **APK signer v1/v2/v3**; SMB/FTP/SFTP/WebDAV clients + built-in FTP/HTTP server; Silver bundles Archiver, **Image, Tagger (audio), PDF** add-ons | Not on Play (main app); add-on system means capabilities gated behind purchases; no smali/hex-depth | [mixplorer.com](https://mixplorer.com/) · [Silver on Play](https://play.google.com/store/apps/details?id=com.mixplorer.silver) |
| Honorable mentions | Android | — | — | Mobile | **Easy UnRAR** (RbigSoft): ZIP with AES + multi-volume split creation, 7z encrypted-header creation, SAF-only permissions ([Play](https://play.google.com/store/apps/details?id=com.rbigsoft.easyunrar.lite)); **7Zipper** (PolarBear): archive+FM with **built-in hex viewer**, FTP/HTTP server ([Play](https://play.google.com/store/apps/details?hl=en&id=org.joa.zipperplus7)) | Both ad-supported, low polish | — |

---

## B. APK / smali editors on Android (re-signing focus)

| Product | Platform | Price | License | Mobile/Desktop | Strengths | Known complaints | URL |
|---|---|---|---|---|---|---|---|
| **MT Manager** (bin.mt.plus) | Android | Free + VIP (VIP price **not verified**) | Proprietary | Mobile | The closest thing to the target app that exists: dual-pane FM + ZIP editing in-place + DEX/smali editing on **dexlib2** (JesusFreke/smali), ARSC++ editor, AXML decompile/recompile, **APK signing (v1/v2/v3, custom keystore on VIP)**, signature-check removal, apk optimization/clone, hex editor, script executor, image/music/font viewers. As of 2.26.9 ships a local **APK MCP server** so AI agents can drive its full read/edit/repack/sign pipeline | China-centric docs/UI; many features VIP-gated; gray-area tool (signature-check killer); not on Play | [mt2.cn](https://mt2.cn/) · [VIP features](https://mt2.cn/vip/) · [MCP docs](https://mt2.cn/guide/reverse/apk-mcp.html) · [manual](https://www.bookstack.cn/read/mt-manual/d1effbafd7ecaa80.md) |
| **NP Manager** ("吹牛儿") | Android | Free (downloaded from author's site, not Play) | Proprietary | Mobile | Even more aggressive than MT: dex/jar/smali conversion both ways, smali flowcharts/IR, **APK signing + coexistence + signature-check removal + random signing**, control-flow obfuscation, Dex2C, PDF merge/encrypt/split, video transcode, hex editor, terminal, SMB/FTP/SFTP/WebDAV, its own APK MCP service | Documented crash-prone on rooted/Xposed devices; requires network at launch; distribution only via personal server/Telegram — malware-adjacent risk | [GitHub readme](https://github.com/githubXiaowangzi/NP-Manager) |
| **APK Explorer & Editor (AEE)** | Android | Free | **FOSS** | Mobile | Open-source APK explorer: edit text/binary XML, delete/replace resources, **assemble modified smali into new .dex**, decode resources.arsc, **create signed APKs/bundles and re-sign with a custom key** | Small project; editor "buggy with huge APKs" (own README); far less polish than MT | [GitHub](https://github.com/apk-editor/APK-Explorer-Editor) |
| **Decompile App: Apktool & Jadx** (and the "Apktool M" lineage) | Android | Free w/ ads (one listing) | Proprietary wrapper around FOSS engines | Mobile | On-device decompile/recompile via Apktool + jadx; edit smali; export signed APK; no PC needed | 2.5★; review complaints about decompile output landing in inaccessible `Android/data` paths; "Apktool-Mt" as a separately named product **could not be verified** beyond these listings | [Play](https://play.google.com/store/apps/details?id=com.apktools.app.decompile) |
| Desktop references (ambition baseline) | Desktop | — | FOSS/proprietary mixed | Desktop | jadx (FOSS, dex→Java, no signing), apktool (FOSS), [APKToolGUI](https://github.com/AndnixSH/APKToolGUI) (FOSS, decompile+sign+zipalign), [REAndroid APKEditor](https://github.com/REAndroid/APKEditor) (FOSS jar), [APK Studio](https://github.com/vaibhavpandeyvpz/apkstudio) (unmaintained; had smali editor + hex + sign) | None of these is an app | — |

**Re-signing verdict:** on-device edit→rebuild→sign is done by exactly **MT Manager, NP Manager, AEE, and a few low-rated Apktool wrappers**. Nobody mainstream, nobody FOSS at MT's depth, and no Western-Play-featured product.

---

## C. Hex editors on Android

| Product | Platform | Price | License | Mobile/Desktop | Viewer or editor? | Strengths | Complaints | URL |
|---|---|---|---|---|---|---|---|---|
| **Hex Editor — Binary Viewer** (PAYTANG, `com.nax.hexeditor`) | Android | Free viewing; **Pro one-time IAP unlocks editing** (amount not shown in fetched listing) | Proprietary | Mobile | Viewer free, **editor paid** | Best-engineered of the bunch: GB-scale paged scrolling, hex+ASCII, data inspector (int/float LE/BE), hashes, strings extraction, file compare with shift detection, PNG/ZIP/PE/ELF structure detection, entropy/histogram, xxd/Intel-HEX export, patches | Editing paywalled | [Play](https://play.google.com/store/apps/details?hl=en&id=com.nax.hexeditor) |
| **BinEd** (ExBin Project) | Android (+desktop) | Free | **FOSS** | Mobile | Editor | Real insert/overwrite editor, charsets, undo/redo, partial-file mode for huge files | UI dated; 3.8★; reviews report search misbehaving, no dual-file compare | [Play](https://play.google.com/store/apps/details?id=org.exbin.bined.editor.android) · [site](https://bined.exbin.org/android/) |
| **HEX Editor** (HARDCODEDJOY) | Android | Free + IAP | Proprietary | Mobile | Editor | Large-file handling (disk-read + RAM edits), insert/overwrite, rich inspector (INT24…float64, image), find/replace | New/low install base; telemetry declared | [Play](https://play.google.com/store/apps/details?hl=en_US&id=com.hardcodedjoy.hexeditor) |
| **Hex Editor** (Yunus.TK) | Android | Free, ads | Proprietary | Mobile | Editor | Long-lived classic; multi-file | 2.8★; **Jan 2026 review: no longer able to edit files** (likely scoped-storage breakage); mature-17+ rating due to ads | [Play](https://play.google.com/store/apps/details?id=tk.yunus.hexeditor) |
| **HEX Editor – WindHex Mobile** | Android | Free + ads | Proprietary | Mobile | Editor | Niche: game translators, table files, compare | Very niche | [Play](https://play.google.com/store/apps/details?id=com.GenesysEast.windhex) |

**Could not verify (2026 searches):** "Hex Editor by Cookiejar" — CookieJarApps' verified product is the SmartCookieWeb browser, no hex editor found ([GitHub](https://github.com/cookiejarapps/smartcookieweb)); "Neo Editor" as an Android app (searches collapse into HHD's desktop Hex Editor Neo); "Hex Editor by Petra Leukert" (only a German pastor of that name surfaced); "iHex" as an Android product (iHex appears to be macOS/iOS). The modern Android hex scene is actually: PAYTANG, BinEd, HARDCODEDJOY, Yunus, WindHex — plus hex *viewers* inside MiXplorer, MT Manager, NP Manager, and 7Zipper.

---

## D. Audio tag editors on Android

| Product | Platform | Price | License | Mobile/Desktop | Strengths | Complaints | URL |
|---|---|---|---|---|---|---|---|
| **Automatic Tag Editor ("AutomaTag")** (fillobotto) | Android | Free + IAPs (batch, fingerprinting — historically ~€1.79/€2.79; current amounts **unverified**) | Proprietary | Mobile | The default answer for auto-tagging: online match + hi-res covers, writes tags into files, MP3/M4A/FLAC/Ogg/Opus/WMA/WAV/AIFF/APE, SD card support | **2026 reviews: app effectively broken** — endless storage-permission loop, "devs are long gone"; last listing update Jan 2024 for a while (though listing now shows Oct 2026 tweak) | [Play](https://play.google.com/store/apps/details?id=com.fillobotto.mp3tagger) · [automatag.com](https://automatag.com/) |
| **Metadator** (BobbyESP) — *likely what "Metadeta" means* | Android | Free | **FOSS (AGPL-3.0)** | Mobile | TagLib-based (everything TagLib reads), writes only changed tags + backup before save, MusicBrainz+Deezer lookup, LRCLIB lyrics, batch, Material 3, FOSS build with no telemetry | Tiny install base (1K+ on Play); Play build ≠ FOSS build | [GitHub](https://github.com/BobbyESP/Metadator) · [Play](https://play.google.com/store/apps/details?id=com.bobbyesp.metadator) |
| **Kid3** | Android + Linux/Windows/macOS | Free | **FOSS (GPL-2+)** | Mobile + desktop | The power-user tagger: all ID3v1/v2.3/v2.4 frames, Ogg/Opus/FLAC/MP4/WMA/WAV/AIFF/tracker modules, batch, import from MusicBrainz/Discogs/gnudb, filename↔tag generation | UI dated; uses MANAGE_EXTERNAL_STORAGE | [F-Droid](https://f-droid.org/en/packages/net.sourceforge.kid3/) |
| **TagClear** (devlab) | Android (+other) | Free, ads | Proprietary | Mobile | **The only verified app that edits audio tags *and* PDF metadata *and* Office (docProps) *and* image EXIF/IXM/IPTC in one place** — directly overlaps the target's PDF/Office tag claim | Ad-supported; image write relies partly on platform capabilities | [Play](https://play.google.com/store/apps/details?id=com.devlab.metadata) |
| Others | Android | — | mixed | Mobile | TagWeaver (offline ID3/FLAC + LRC, one-time Pro — [apppage](https://apppage.net/preview/com.onnellab.tagweaver2)); Flac-R (FOSS GPL, [F-Droid](https://f-droid.org/packages/com.resurrect.flac_r/)); Vanilla Tag Editor (FOSS plugin, stale since 2020); TK Music Tag Editor, HitroLab Audio Tag Editor, Smart MP3 Tag Editor (all freemium) | — | — |

**Could not verify:** Tag Editor by hicell, and Pumora (no current Play/F-Droid listing surfaced; Pumora appears defunct). **Mp3tag** (desktop/Flatpak benchmark) was not re-verified in this pass — do not treat any price claim about it here as sourced.

---

## E. Image editors: bundled vs standalone

| Product | Platform | Price | License | Mobile/Desktop | Strengths | Complaints | URL |
|---|---|---|---|---|---|---|---|
| **Snapseed 4** (Google) | Android + iOS | **Free, no ads, no IAP** (verified: Wirecutter Jun 2026, Android Police May 2026) | Proprietary | Mobile | 2026 rewrite: new UI, camera in-app, non-destructive edit stack, **batch editing, masking**, RAW DNG, 29 tools incl. crop/rotate/curves/selective | No Magic-eraser-class AI cleanup; historically slow updates | [Android Police](https://www.androidpolice.com/snapseed-4-for-android-hands-on/) · [Wirecutter](https://www.nytimes.com/wirecutter/reviews/best-photo-editing-apps-for-android-ios/) |
| **Pixlr** (Autodesk lineage) | Android + iOS + web | Freemium; Android Authority: "$0.99–$11.99 per item"; AndroidPad 2026: "Pixlr Plus $4.99/month" — **conflicting, unverified** | Proprietary | Mobile | Layers-ish, draw brushes, text, stickers, double exposure, one-tap effects with strength sliders | Ads require pay-to-remove; effects not previewed live while sliding (Lifewire) | [Lifewire](https://www.lifewire.com/best-photo-editor-apps-4165738) · [Android Authority](https://www.androidauthority.com/PHOTO-EDITOR-APPS-FOR-ANDROID-337494/) |
| **Photopea** (web/PWA) | Any browser (usable on phones) | Free w/ ads; one-time options for ad-free/AI credits (amounts not fetched — **unverified**) | Proprietary freemium | Desktop-first, mobile-capable | Full Photoshop-class: layers, PSD, RAW, vectors, masks — runs fully local in-browser | **Mobile experience cramped** (vendor recommends mouse/stylus/keyboard); **warning: a fake "Photopea" app on Play with the name is adware/spyware per 2026 reviews** — only photopea.com is real | [photopea.com](https://www.photopea.com/) · [fake Play app](https://play.google.com/store/apps/details?id=com.photoeditorssx.app) |
| Bundled-in-file-managers | Android | — | — | Mobile | MiXplorer Silver's **Image add-on** (viewers/decoders incl. RAW/TIFF — verified); ZArchiver has basic image preview (Donate) | **"Image Toolkit" could not be verified** as a distinct product; nothing in the FM class ships a real draw/text/crop editor at Snapseed level | [MiXplorer Silver](https://play.google.com/store/apps/details?id=com.mixplorer.silver) |

**Verdict:** crop/rotate/draw/text is table stakes as standalone apps; *bundled* inside a file manager at any quality is rare — the FM class ships viewers, not editors.

---

## F. Scripting / automation on Android

| Product | Platform | Price | License | Mobile/Desktop | File-manager-style scripted file ops? | Complaints | URL |
|---|---|---|---|---|---|---|---|
| **Tasker** (joaomgcd) | Android | **$4.49 one-time** on Google Play (US); **$4/device one-time** direct from developer; 7-day trial either way. Note: $3.49 figures in some 2026 blog posts are **stale** — price rose to $4.49 on 2025-12-12 | Proprietary | Mobile | **Yes, closest to "scripted file ops":** file manipulation actions, auto-backups, JavaScript execution, HTTP actions, and **Termux:Tasker** for real shell; largest plugin ecosystem on Android (AutoApps etc. — the "JoaoBissotto" ecosystem is joaomgcd) | Brutal UI, no free tier, plugins cost $1–3 each | [tasker.joaoapps.com](https://tasker.joaoapps.com/) · [Play](https://play.google.com/store/apps/details?id=net.dinglisch.android.taskerm) · [itechguides](https://www.itechguides.com/products/tasker/) · [Glass It price history](https://glassit.co/price-history/google-play/tasker/1662414428) |
| **MacroDroid** (ArloSoft) | Android | Free (5 macros, ads); **Pro = one-time in-app purchase, Play-listed range $1.49–$10.00** — the store listing gives no single figure; commonly cited as ~$5; **no point figure independently verified**. No subscription, ever | Proprietary | Mobile | Basic file actions yes; **no Termux/shell**, weak variables, thin plugins | Ceiling arrives fast | [Play](https://play.google.com/store/apps/details?id=com.arlosoft.macrodroid) · [macrodroid.com](https://macrodroid.com/) · [comparison](https://www.droidrooter.com/blog/macrodroid-vs-tasker-vs-automate-2026) · [ToolGPA](https://toolgpa.com/tools/macrodroid/pricing) |
| **Automate** (LlamaLab) | Android | Free (30 running blocks, ads); **Premium = single in-app purchase — vendor does not publish the price** (llamalab.com states purchase mechanics only); third-party "$7.99" is **unverified** | Proprietary | Mobile | Flowchart file-op blocks incl. zip extract/compress; good visual debugging | 30-block free cap; cluttered past ~50 blocks | [llamalab.com](https://llamalab.com/automate/) · [premium doc](https://llamalab.com/automate/doc/premium.html) · [itechguides](https://www.itechguides.com/products/automate/pricing/) |
| **Termux** | Android | Free | **FOSS (GPL-3.0)** | Mobile | **The real answer:** full Linux userland (python, perl, ruby, node, openssh, rsync…) over the app's own storage; 1000+ packages | **Play Store build is deprecated/divergent** (maintainers explicitly warn against it); real updates come from F-Droid/GitHub only; W^X/Android exec restrictions shaped its whole history | [GitHub](https://github.com/termux/termux-app) · [F-Droid](https://f-droid.org/en/packages/com.termux) · [Wikipedia](https://en.wikipedia.org/wiki/Termux) |

**Does any file manager ship a script sandbox?** Almost none. **MT Manager** has a built-in "script executor" with system + extension-pack environments and optional root ([feature list](https://mt-manager.dialapk.com/android/features/)) — the only FM-grade script execution found. Total Commander Android can send shell commands from its button bar ([Play listing](https://play.google.com/store/apps/details?id=com.ghisler.android.TotalCommander)) — not a sandbox. MiXplorer: none found. **No Android file manager was found shipping a sandboxed Lua (or other) scripting layer over a documented file abstraction API** — that cell in your target bundle is effectively empty on mobile. LuaDroid exists as a Lua IDE *with* a file manager, which is the inverse product.

---

## G. LAN sharing to a browser — how it's actually done in 2026

| Product | Platform | Price | License | What it actually is | Complaints | URL |
|---|---|---|---|---|---|---|
| **HTTP FS** | Android (TV too) | Free; Pro for multi-user/HTTPS extras (amount **unverified**) | Proprietary | Phone → **LAN web file manager + WebDAV + optional FTP/HTTPS**, QR connect, 100K+ installs — the category leader for "browser opens my phone" | Freemium gates | [httpfs.pages.dev](https://httpfs.pages.dev/) |
| **Flikky** | Android ↔ any browser | Free | Source-available | Short-lived PIN-paired **two-way** text+file session with a bundled offline browser client | **Plaintext HTTP** (stated by project); Android 13+ | [GitHub](https://github.com/Lifky/Flikky) |
| **Swap** | Android ↔ any browser | Free | **FOSS (Kotlin)** | Local HTTP server with full web file manager: mDNS `swap.local`, ZIP downloads, trash+undo, expiring share links, Basic/per-file passwords | New/small | [GitHub](https://github.com/mobinshahidi/swap) |
| **LocalSend** | All platforms | Free | **FOSS** | The 2026 default for ad-hoc device↔device LAN transfer (HTTPS, multicast discovery) | App-to-app only — **not browser-based**; multicast dies on segmented/guest Wi-Fi | [localsend.org](https://localsend.org/) · [LAN mechanics overview](https://maxshare.org/methods/local-network/) |
| **KDE Connect** | Android + Linux/Win/Mac | Free | **FOSS** | Persistent phone↔desktop pairing: files, clipboard, notifications, remote input | Not a browser; pairing friction; Windows support weaker | [kde.org/connect](https://kde.org/products/kdeconnect/) |
| **croc** (schollz) | CLI all platforms + browser receive | Free | **FOSS** | `croc send` → 3-word code/QR → browser at getcroc.com receives; PAKE E2E; now with Tailscale-ish direct-path transport | Needs internet/relay unless LAN mode; not an Android app (community Flutter client exists) | [getcroc.com](https://getcroc.com/) · [GitHub](https://github.com/schollz/croc) |
| **Tailscale Taildrop** | All | Free tier exists | Proprietary | P2P file send between **your own devices** on a tailnet, resume support | **Alpha**; own devices only, not "anyone on the LAN" | [docs](https://tailscale.com/docs/features/taildrop) |
| **Quick Share web LAN** / **SMB mounts** | Browser↔browser; Windows/Mac | — | Proprietary / built-in | WebRTC browser-to-browser LAN transfer (May 2026); and the boring workhorse: **SMB shares mounted from phone file managers** (MiXplorer, Total Commander LAN plugin; Android has no built-in SMB client) | Guest Wi-Fi client-isolation breaks all of the above | [Quick Share blog](https://quick-share.app/blog/introducing-web-lan-transfer) · [maxshare](https://maxshare.org/methods/local-network/) |

**Could not verify:** "Gnota", "LocalShare", "Small Share", "Shared Folder" as distinct current products; Solid Explorer / CX File Explorer server-side features (their SMB role as *clients* is well-established; I did not verify a built-in shareable browser server for either).

**How people actually share on a LAN in 2026:** (1) **LocalSend** for one-off sends; (2) **SMB** for a folder you keep using; (3) **phone-as-HTTP/WebDAV-server apps** (HTTP FS et al.) when the other side is just a browser; (4) **croc/Tailscale** when "LAN" isn't actually a LAN. Discovery is multicast/mDNS and fails on mesh/guest networks — every tool has a manual-IP path.

---

## H. Desktop power file managers (ambition baseline)

| Product | Platform | Price (verified) | License | Dual pane | Scripting engine | Built-in viewers/editors | Complaints | URL |
|---|---|---|---|---|---|---|---|---|
| **Directory Opus 13** (GPSoftware) | Windows | Perpetual licences: **Single A$89 · Dual A$129 · Five A$249** (incl. 10% GST), one year of free updates, then optional upgrade subscription **A$25 / A$40 / A$75 per year**. Never auto-renews; expired sub = keep your version, no new features. Free 30-day trial (60 on email registration) | Proprietary | Yes (+dual trees, tabs) | **Full scripting interface** (JScript/VBScript/PowerShell, scriptable dialogs, script columns — actively developed per Aug 2026 release notes) | Integrated viewer pane (images, video/audio via MediaInfo, PDF/Office via handlers, **hex viewer** — viewing/copying, not a full hex editor), metadata editor, sync, dup-finder | Price; Windows-only; upgrade-subscription shift annoyed long-time users | [gpsoft.com.au](https://www.gpsoft.com.au/) · [viewer docs](https://docs.dopus.com/doku.php?id=release_history%3Aopus13_detailed%3Aviewer) · [Reddit r/dopus on pricing model](https://www.reddit.com/r/dopus/comments/1ah0cil/directory_opus_13_its_now_a_onetimepayment_from/) · [PriceTrack](https://pricetrack.dev/products/sh-directory-opus) |
| **XYplorer** | Windows | **$34.95 Standard / $69.95 Lifetime** per user, all your computers (verified on vendor page) | Proprietary | Optional dual pane | **Yes — built-in scripting, no plugins needed** ("power user's dream", vendor) | Rich preview (image/audio/video/web/office/font), custom columns, batch rename, hash values; **no embedded terminal** (per 2026 cwdio survey) | No terminal; optional (not default) dual pane; one-man shop feel | [xyplorer.com](https://www.xyplorer.com/) · [purchase](https://www.xyplorer.com/purchase.php/features.php) |
| **Total Commander** (Ghisler) | Windows + Android | Shareware; German full version listed at **€49.98** (PC-WELT, 2026); free updates forever for registered users; old EULA cited CHF 40 (dated) | Proprietary shareware | Yes (staple) | **Command-line + WCX/WLX/WDX/WFX plugin ecosystem** (the classic "scripting" is plugins, incl. Lua via community plugins) | Internal lister (hex included), built-in zip/unzip, split/join, sync tool, FTP(S) | Dated UI; capabilities depend on finding plugins; Android version is a different, simpler app | [ghisler.com](https://www.ghisler.com/) · [FAQ](https://totalcommander.dev/faq.html) |
| **Midnight Commander** | Unix/WSL | Free | **FOSS (GPL-3.0)** | Yes (orthodox TUI) | Shell/subshell integration; no rich plugin API like TC | mcedit internal editor (with hex mode), built-in diff | Text-mode; not a viewer/metadata powerhouse | [midnight-commander.org](https://midnight-commander.org/) |
| **Double Commander** | Win/mac/Linux/BSD | Free | **FOSS (GPL-2.0)** | Yes | **Lua scripting built in** (plus TC plugin support) | **Built-in hex viewer of ANY file size (F3)**, syntax-highlighted editor (F4), archives-as-directories | Slower development cadence; large open-issue count | [doublecmd.github.io](https://doublecmd.github.io/doc/en/about.html) · [GitHub](https://github.com/doublecmd/doublecmd) |
| **FAR Manager** | Windows (+far2l on Linux/mac) | Free | **FOSS (BSD-3-Clause)**, v3.0.6666 active Mar 2026 | Yes (console) | **Plugin-driven incl. Lua** | Console viewer/editor | Console-mode aesthetic; niche outside RU/UA power users | [farmanager.com](https://farmanager.com/) · [comparison table](https://en.wikipedia.org/wiki/Comparison_of_file_managers) |

---

# So what

## Table stakes (individually easy, expected in 2026)

- **Zip/7z/tar.gz create+extract with password** — ZArchiver, RAR, MiXplorer, Easy UnRAR all ship it. AES-256 in 7z and RAR is universal; ZIP/AES creation is claimed by at least Easy UnRAR and ZArchiver's password mode.
- **Split-archive extraction** (7z.001/zip.001/partN.rar) — standard in every serious tool; creation of splits is only slightly less common (7z multi-part, RAR volumes).
- **Basic text editing, image viewing, crop/rotate-class image work** — either built into FMs (viewers) or one free download away (Snapseed).
- **Audio ID3/Vorbis/MP4 tagging** — crowded field, FOSS options included (Kid3, Metadator).
- **LAN sharing** — solved as a category: LocalSend for ad-hoc, HTTP FS–style phone-servers for browser access, SMB mounts for permanence. Shipping an HTTP server with a web UI is an afternoon's work with modern libs; it is not a moat.
- **Hex viewing** — even 7Zipper and MiXplorer include one.

## Genuinely rare when combined in ONE mobile app

1. **Smali/DEX editing + rebuild + re-sign on device.** Verified in MT Manager, NP Manager, AEE, and thin Apktool wrappers — and essentially nowhere else. Not in any FOSS FM at MT's depth, not in any Western mainstream app. This is the single rarest capability in your bundle, and it's the one your competitors guard hardest (MT VIP, NP distribution obscurity).
2. **A real hex *editor* (insert/overwrite, undo) integrated in a file manager** — MT/NP have it; standalone Android hex editors that still work in 2026 are a handful (PAYTANG paywalls editing; Yunus.TK's editor appears broken on modern Android per a 2026 review). Rare in combination, trivial in isolation.
3. **A sandboxed scripting layer (Lua or anything) over a first-class file abstraction API inside a mobile FM.** *Nobody verified ships this.* MT's script executor is the nearest neighbor and it isn't advertised as a sandboxed, documented VFS. On desktop it's normal (Double Commander's Lua, Directory Opus's and XYplorer's script objects). On Android it is an open cell.
4. **TextMate grammars + tree-sitter in a mobile file manager.** Not found in any product in this research. Android code editors exist (e.g. Acode-class apps), but inside a file manager — unverified anywhere.
5. **Unified metadata editing across audio + video + PDF + Office in one app.** TagClear does audio+PDF+Office+images (not a FM); MiXplorer Silver does audio tags via add-on (not PDF/Office); NP Manager does PDF *structural* ops, not tag editing. The cross-domain single-app version is unclaimed.
6. **The full bundle.** The two nearest existing products each cover roughly half and miss the interesting parts:
   - **MT Manager** = A + B + C + F-lite + text editor with highlighting. **Missing:** browser-reachable LAN server, audio/PDF/Office tags, image *editing*, tree-sitter/TextMate, a real sandbox story.
   - **MiXplorer Silver** = A + G + D-lite (audio tagger/image/PDF add-ons) + APK signing (v1/v2/v3!) + SMB/FTP. **Missing:** smali/dex editing, hex editing, any scripting, image draw/text editing.

---

# Explicit could-not-verify list

- **"7-Zip for Android by Mark McA"** — no such Play listing found; no official 7-Zip Android app exists at all.
- **"Hex Editor by Cookiejar", "Neo Editor" (Android), "Hex Editor by Petra Leukert", "iHex" (Android)** — none surfaced; see Area C for what the market actually contains.
- **"Tag Editor by hicell", "Pumora"** — no current listings found (Pumora likely defunct).
- *Metadeta* is taken to mean **Metadator** (BobbyESP). The names are close enough that the
  mapping is a guess rather than a finding, so treat it as unconfirmed.
- **"Image Toolkit", "Gnota", "LocalShare", "Small Share", "Shared Folder", "Apktool-Mt"** as distinct 2026 products — not independently verified.
- **Prices left blank on purpose:** ZArchiver Donate (unpurchasable), MT/NP VIP tiers, RAR ad-free IAP amount, MiXplorer Silver, Pixlr (conflicting sources), Photopea one-time tiers, Automate Premium (vendor does not publish), Mp3tag license terms.
- **MacroDroid Pro** — Play-listed range only ($1.49–$10.00); ~$5 commonly cited but **no point figure independently verified**.
- **Tasker price conflict resolved:** Play = **$4.49** one-time; direct = **$4/device** one-time. The $3.49 figure circulating in some 2026 guides is **stale** (pre-2025-12-12 price). Do not cite $3.49.
- **Solid Explorer / CX** as LAN *servers* (their SMB *client* role is established; server features unverified here).

**Bottom line for the single-app ambition:** the market proves each capability is buildable on Android — but nobody has stacked B (re-sign) + C (hex edit) + sandboxed F (scripting) + cross-domain D (metadata) + G (browser server) into one product outside of two closed, half-covering tools (MT, MiXplorer Silver). The genuinely defensible, non-table-stakes cores are the **re-sign pipeline**, the **script sandbox over a file API**, and the **integrated hex/smali editing** — the rest is expected plumbing you must ship well, not moats.
