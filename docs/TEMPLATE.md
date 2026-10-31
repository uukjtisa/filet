# Signet — the standard app shell

> A signet ring stamps a seal into wax. This module is what stamps the identity
> onto every app he ships: the same onboarding, the same updater, the same motion,
> the same About screen carrying the signature mark — with a swappable palette so
> each app still looks like itself.

Built once for **Filet**, then reused. Trawl gets retrofitted onto it when
convenient, not urgently.

**Name is provisional.** Alternatives: `Hallmark` (the mark struck on precious
metal to certify the maker — very close in meaning, but a large greeting-card
trademark), `Stamp`, `Mark`. `Signet` wins on being short, distinctive in Android
search, and a direct callback to the signature seal.

---

## 1. Why this exists

Three reasons, in order of weight:

1. **Every app after this one starts at 60%.** Onboarding, updater, changelog,
   about, settings scaffolding, theme — solved once.
2. **A recognisable house style is a portfolio asset.** Two apps that obviously
   come from the same workshop read as a body of work. Two apps that look
   unrelated read as two hobby projects.
3. **It keeps R2 honest.** Aesthetics are a dependency that is already built, so
   they never become the reason a feature slips or ships hollow.

---

## 2. What is in it

| Component | What it does |
|---|---|
| `signet-theme` | Token-based Material 3 theme. Named palettes, dynamic-colour opt-in, light/dark/system. |
| `signet-onboarding` | Declarative first-run wizard — hand it a list of pages, it renders them in the house motion language. |
| `signet-updater` | GitHub Releases check, changelog render, download, `PackageInstaller`. **Build-flavour gated.** |
| `signet-changelog` | "What's new" page, also reachable from settings. |
| `signet-about` | App identity, the signature mark, licence list, third-party attribution. |
| `signet-motion` | The shared animation vocabulary — reveal, stagger, travel, sheen. |
| `signet-settings` | Settings scaffolding: sections, switches, pickers, search. |

---

## 3. Theme — "minimalistic but detailed, cozy but greyer"

The whole point of tokens: **Filet is not warm brown.** The portfolio palette is
the *personal* brand; each app gets its own skin on the same skeleton.

| Palette | For | Character |
|---|---|---|
| **Slate** *(Filet default)* | Filet | Warm-neutral grey. Not pure grey — a grey with a little warmth in it (`#1A1A18`, not `#1A1A1A`), so it reads cosy rather than clinical. One accent. Dense information, restrained chrome. |
| **Ember** | Trawl, personal-brand surfaces | The warm brown/amber from the portfolio. Available in Filet as an option, never the default. |
| **Paper** | light mode | |
| **Dynamic** | opt-in | Material You, from the user's wallpaper. * decoded, because it is easy to get backwards:

- *Minimal* applies to **chrome** — few borders, little decoration, restrained
  colour, no gradients-for-the-sake-of-it.
- *Detailed* applies to **information** — dense rows, real metadata visible
  without tapping, small type used well, generous use of the width.

The failure mode to avoid is the opposite pairing: heavy chrome around sparse
information. That is what most Android file managers do.

All colour must be defined as tokens on the light scheme first, then overridden
for dark. Never let a colour have its only definition inside a dark-mode block.

---

## 4. Motion — the rules, carried over from the portfolio

These were learned the hard way on the portfolio intro. They translate directly
to Compose and should not be rediscovered.

1. **The assembled state is the default.** Animations start from a keyframe, they
   do not leave the UI in a hidden state waiting to be revealed. If the animation
   never runs — reduced motion, a slow frame, a crash in the animator — the user
   sees a finished screen, not an empty one.
2. **Respect reduced motion.** Android exposes
   `Settings.Global.ANIMATOR_DURATION_SCALE`; when it is 0, run nothing. Accessibility
   is not the only reason — it also makes the app feel fast to people who turned
   animations off on purpose.
3. **Nothing may strand the user.** No overlay, curtain or splash may block
   interaction without a hard timeout that tears it down regardless of state. The
   portfolio's curtain needed two independent failsafes; assume this one will too.
4. **Measure, don't hardcode.** Travel animations derive their offsets from real
   measured positions so they land correctly at any screen size. Hardcoded
   translations are re-tuned at every breakpoint forever.
5. **One clock.** Every stage of a sequence gates on the same start signal. Two
   clocks means a slow font or a slow layout desynchronises half the sequence.

---

## 5. Consumption — open question

| Option | Pro | Con |
|---|---|---|
| Gradle `includeBuild` (composite) | Edit template and app together, no publishing step | Both repos must be checked out side by side |
| Maven artifact (JitPack / GH Packages) | Clean versioning, apps pin a version | Publishing friction on every change |
| Git submodule | Simple, no infra | Submodules are a recurring source of pain |

**Leaning:** `includeBuild` while Filet is the only consumer, move to a published
artifact once a second app depends on it. Decide before M0 ends.

---

## 6. Updater — the constraint that bites

`REQUEST_INSTALL_PACKAGES` + a self-updater is fine on GitHub Releases and
**not** fine on F-Droid, which builds from source, ships its own update channel,
and flags apps that update themselves.

So the updater is a **build flavour**, not a runtime setting:

- `github` flavour — updater compiled in, permission in the manifest
- `fdroid` flavour — updater absent, permission **not in the manifest at all**

Not a settings toggle. A settings toggle still ships the permission, and the
permission is the thing F-Droid objects to.

Update check: `GET /repos/uukjtisa/<app>/releases/latest`, compare `versionCode`,
render the release body as the changelog, download the asset, hand to
`PackageInstaller`. Never auto-install without consent.

---

## 7. Onboarding — the intro for a fresh install

Modelled on Trawl's `OnboardingScreen.kt`: a `HorizontalPager`, a dot indicator, a Next button
that becomes Finish, and the house motion language on each page — the title rising out of its
own mask, a rule drawing itself, supporting text staggering in behind it.

Signet owns the shell. An app supplies its pages.

### 7.1 Filet's four pages

| # | Page | Carries |
|---|---|---|
| 1 | **Identity** | The mark, the name, the thesis in one sentence. Branded. |
| 2 | **How it works** | The three things that are not obvious: dual pane, tabs with a Home overview, and *an APK is a folder you can walk into*. |
| 3 | **Permissions** | See §7.2 — the page that decides whether the app is usable. |
| 4 | **Ready** | Theme pick (Slate / Ember / Paper / Dynamic), and "Trawl detected — link them?" if the bridge finds it. |

Page 2 matters more than it looks. Filet's dual pane and APK-as-folder are its two genuinely
unusual ideas, and a user who never discovers them is using a worse file manager than the one
they installed. One screen is the cheapest possible fix.

### 7.2 The permissions page

Trawl's own source has the right instinct, and it is worth copying verbatim:

> *Material glyphs here, not Trawl's marks. The hero pages carry the branding; a permissions
> page should look like a system surface.*

A list of rows, each `icon · title · why · [Grant]`, where the button disappears once held:

| Row | Why line | Required |
|---|---|---|
| **All files access** | "Browse, move and edit any file — this is what makes it a file manager." | Strongly recommended |
| **Notifications** | "Progress for long jobs: indexing, copying, signing." | Optional |
| **Storage indexing** | "Keeps search instant. Runs while charging." | Optional, links to §7.11 of `SEARCH.md` |
| **SD card** | "Granted per card, only when you open one." | Deferred — not asked here |

Rules, from `SEARCH.md` §8.2:

- **Explain before the dialog.** `MANAGE_EXTERNAL_STORAGE` throws the user out to a Settings
  screen and back; an unexplained trip out of the app reads as a crash.
- **Every row is skippable and the page can be passed with nothing granted.** The named degraded
  mode from `SEARCH.md` §8.1 is shown inline once a row is declined, so the user sees what they
  chose rather than discovering it later.
- **Re-check on resume.** The user may grant in Settings and come back; the row must update
  without a restart.
- The page never blocks Next.

### 7.3 Rules for the shell

- Skippable at every step, and **re-runnable from Settings** — first-run flows are where the
  interesting copy lives, and people miss it.
- **Never blocks on a permission the app can start without.**
- Completion is one flag in `DataStore`. A crash mid-onboarding must not trap a user in it
  forever, so the flag is written on *entering* the last page, not on finishing it.
- Under `prefers-reduced-motion` / animator scale 0, pages are assembled with no animation —
  per §4 rule 1, the finished state is the default and the animation is the decoration.

---

## 8. About screen

Carries the **signature mark** — the maker's stamp, not the app icon. The
workshop has a name; the craftsman stamps the own mark on what leaves it.

Also: version + build, licence (GPL-3.0) with full text, generated third-party
licence list (AboutLibraries), a link to the source repo, and the attribution
block for anything lifted from MP-Manager.
