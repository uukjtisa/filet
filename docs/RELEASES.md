# How a Filet release is written

A release body is not a changelog file. It is **a screen inside the app**.

`ReleaseNotes.parse` renders it on the update sheet — headings, bullets, tables, block quotes,
links and centred image strips all draw — so for anyone who never opens this repository, the
release body *is* the only documentation of what changed. It is also the last thing somebody
reads before deciding to install an APK signed with a key no store vouches for.

Write it like that.

---

## The rules

**Open with pictures.** A centred `<div>` of `<img>` tags becomes one screenshot strip in the
app. Show what changed: a release that adds a screen should show that screen. Point the `src`
at `raw.githubusercontent.com/uukjtisa/filet/<tag>/docs/screenshots/…` — pinned at the tag, not
at `main`, so the body cannot quietly change meaning when the screenshots are next refreshed.

**Screenshots use a seeded demo folder, never real files.** Same rule as the README. A release
page is more public than the README, not less.

**Be detailed about what changed and what it means.** Every fix gets the symptom somebody would
have hit and what happens now instead. Every new feature gets what it does and when it matters.
If a release fixes six things, all six are listed.

**Do not be detailed about the code.** No diffs, no line numbers, no file paths, no function
names, no patch-by-patch narration.

> "The bookmark for a `.pptx` opened it as a folder, because the decision about what a bookmark
> points at was made from the icon rather than from the file. It opens in the viewer now."

is the right altitude. `Bookmarks.kt:212` is not.

**Say what it cannot do.** If a format is unsupported, a permission is needed, or something is
known broken, the release body is where somebody finds out — before they install, not after.

**The test:** somebody who has never opened this repository should finish the notes knowing
whether the update is worth taking, and never once need to look at the source.

---

## The shape

```markdown
<div align="center">
<img src="…/docs/screenshots/01-browse.png" width="31%" alt="Browsing with thumbnails">
<img src="…/docs/screenshots/03-search.png" width="31%" alt="Whole-device search">
</div>

One paragraph saying what this release is. This is also what a notification shows, so it has
to stand on its own.

### What is new
- **The thing.** What it does, and when it matters.

### Fixed
- **The symptom somebody hit.** What happens now instead.

### Installing
One APK, Android 8.0 or newer. …

### Known limits
…
```

`node tools/check-releasedoc.mjs` checks the published body against this. It is a blunt
instrument — it can see whether there are images and sections and whether the prose is talking
about source files, and it cannot see whether the writing is any good.

---

## Why this file exists

Nic, round 8:

> "use the rule of hte trawl too in its claude md or somewhre where when yo umake a new release
> it must be fomratted and has images and etc.. or whatever trawl saids it is.. liek a fancy
> format yknow? wiht images and markdown rendered"

The rule is Trawl's, which has the same renderer and the same reason for it. Both apps are
sideloaded, neither has a store listing, and in both the release body is doing a job that a
store description would otherwise do. It is written down in the repository rather than only in
the agent instructions so that it outlives any one session and applies to the next app too.
