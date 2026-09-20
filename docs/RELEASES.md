# How a Filet release is written

A release body is not a changelog file. It is **a screen inside the app**.

`ReleaseNotes.parse` renders it on the update sheet — headings, bullets, tables, block quotes,
links and centred image strips all draw — so for anyone who never opens this repository, the
release body *is* the only documentation of what changed. It is also the last thing somebody
reads before deciding to install an APK signed with a key no store vouches for.

Write it like that.

---

## The rules

**Write it like a flagship app writes one.** Plain, short, list-shaped. The reader wants to
know whether to tap Update, and the notes answer that and stop.

A release body has no narrator. Do not open with a thesis, a mood, a count of how many things
were fixed, or a sentence about what kind of release this is. These are all the same mistake:

> ~~"Repairs, and a video player that behaves like one. No new screens are pictured here."~~
> ~~"Eighteen things, every one of them something that went wrong in use."~~

The opening paragraph is one or two sentences of fact - what this update does, and anything
about installing it that cannot wait. Then the list. Every bullet is **the symptom** in bold
and what happens now, one sentence each. No preamble to a section, no commentary between
bullets, no closing remark.

Specifically, and each of these has been written here before:

| Do not | Instead |
|---|---|
| "This release focuses on..." | say what changed |
| "Eight fixes, every one of them..." | the eight bullets are the count |
| "No new screens are pictured here." | the no-pictures line the shape below already has |
| a wry aside about the bug | the symptom, then the fix |
| "we", "I", "you'll notice" | the app and the file, named plainly |

## The order of operations, which is not optional

Releases 0.1.5 and 0.1.6 both turned the repository's checks red for a while, for the same
reason, and it was sequencing rather than anything wrong with either release.

`check-release.mjs` fails a build whose version carries **a tag with no release behind it**,
because that is exactly what "tagged it and forgot to publish" looks like and it is worth
catching. CI fires on the push to `main` and reaches that check a few minutes later. So pushing
the commit, then pushing the tag, then creating the release puts the check inside a window
where the tag exists and the release does not — and it fails, correctly, on a release that was
seconds from being fine.

**So the tag is never pushed by hand.** The release API creates it:

1. `git push` the commit. CI starts. At the check it sees a version ahead with **no tag**,
   which is reported as *in flight* and passes.
2. `POST /releases` with `tag_name: vX.Y.Z`. GitHub creates the tag itself.
3. Upload the APK asset.
4. `node tools/check-release.mjs` and `node tools/check-releasedoc.mjs` locally.

The workflow triggers on `push: branches: [main]` and not on tags, so step 2 starts no second
run. If a run is already red from an earlier ordering mistake, re-run it once the release
exists — the check is a fact about the repository, not about that moment, so it passes.

**Never `git push origin vX.Y.Z`.** It is the one command that opens the window.

**Pictures only when there is something new to show.** A release of pure repairs has nothing
to photograph, and filling the strip with shots of screens that did not change is how a
gallery goes stale. Such a release says *no new screens in this one* in the opening paragraph;
`check-releasedoc.mjs` accepts that in place of images, so the absence is a stated claim
rather than an oversight.

**Open with pictures when there ARE any.** A centred `<div>` of `<img>` tags becomes one screenshot strip in the
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

A release body is a formatted document with images in it, rendered on a screen - not a
changelog.

The rule comes from Trawl, which has the same renderer and the same reason for it. Both apps are
sideloaded, neither has a store listing, and in both the release body is doing a job that a
store description would otherwise do. It is written down in the repository rather than only in
the agent instructions so that it outlives any one session and applies to the next app too.
