# Contributing to Filet

Filet is a file manager built by one person for one phone, published because it turned out to
be worth publishing. Contributions are welcome and so is the decision not to merge one —
this document exists so that a no comes with a reason you could have read in advance, rather
than after you have spent an evening on a branch.

If you only want to report something, the [issue templates](.github/ISSUE_TEMPLATE) ask for
what a fix needs. Nothing below applies to filing an issue.

---

## The quickest useful thing

A bug report with a session log attached. Settings ▸ Logs ▸ **Verbose logging**, reproduce the
problem, attach the newest file from `Filet/Logs`. Several of the hardest bugs in this app were
found that way and not by reading the code — a cold boot taking twenty seconds turned out to be
two unrelated causes, and the log is what separated them.

---

## Before you write code

**Open an issue first for anything that adds a screen, a setting, a permission or a
dependency.** Not for process, and not to ask permission — to find out in ten minutes rather
than at review whether the thing fits. Filet has a shape, most of it written down, and the
most common reason a change does not land is that it is right in isolation and wrong against
the shape.

A bug fix needs no issue. Send it.

---

## The shape, and the four rules a change is measured against

[`PLAN.md`](PLAN.md) is the architecture. The short version is a layer model — L0 pure logic,
L1 the virtual filesystem, L2 providers, L3 services, L4 view models, L5 Compose — and four
rules that are not style preferences. Every one of them exists because breaking it has already
cost this app a round of work.

### R1 — No dead switches

A control that is visible is a control that does something. No greyed-out menu rows, no button
that reports "not supported", no setting that is read nowhere. If an action does not apply to
what is selected, it is **absent**, and if a whole feature cannot work yet, the surface waits.

The metadata screen shipped without its cover-art block for exactly this reason: the buttons
would have been drawn, enabled, and dead. They are there now because the engine behind them
exists.

### R2 — Nothing blocks the main thread

Every filesystem call goes through a dispatcher. There is a checker for it
(`node tools/check-mainthread.mjs`) and it walks the call sites rather than trusting a
convention.

### R3 — Everything above L0 talks only to the VFS

No `java.io.File`, no `ContentResolver`, no path strings, in anything above the provider
layer. The reason is not purity: a `File` works on internal storage and fails silently on a
WebDAV share, an archive and a root path, so a single `File` in a view model is a feature that
works on your phone and breaks on somebody's network drive. There is an allow-list for the
handful of places that genuinely cannot go through the VFS, and each entry carries its reason.

`node tools/check-r3.mjs` enforces it.

### R4 — Every claim is checkable

If the app or the README says it does something, something has to be able to check that it
does. This is the rule that catches the most embarrassing class of bug: the format table once
advertised MP3 cover art, nine EXIF fields and WebP comments with **no code behind any of the
three**. Every test passed throughout, because they all asked whether the table was consistent
with itself and none asked whether it was connected to anything.

So: a decision that can be wrong becomes a pure function with a test, and a claim in a document
gets a checker.

---

## Running it

```bash
./gw.sh :app:assembleGithubDebug          # the app
./gw.sh :app:testGithubDebugUnitTest      # its unit tests
./gw.sh :core-vfs:testDebugUnitTest       # the filesystem layer's
node tools/check-all.mjs                  # every checker, each with its own selftest
```

The flavour in the task name is not optional. `gw.sh` is a thin wrapper that picks the right
JDK; plain `./gradlew` works if your `JAVA_HOME` is already a 21.

**`check-all.mjs` is the gate.** It runs around forty checkers — architecture, dialogue
consistency, dead switches, the release state, the README's own claims — and each one has a
`--selftest` that deliberately breaks the thing it checks and fails if the check does not
notice. A checker that cannot catch its own mutation is not a check, and the selftests run in
CI alongside the real pass.

If a checker fails on your change and the checker is wrong, **say so in the pull request and
change the checker**, with the reasoning in its docstring. Several of them have been rewritten
that way. What is not fine is loosening one so a change slides past; the one that held six
audio and document formats at read-only did its job by blocking a change, and it was rewritten
to a tighter rule rather than deleted.

---

## What a change should look like

**One line of commit message**, in the form `type(scope): thing, thing, thing`. Present tense,
no body, no trailer, no attribution lines. `fix(vfs): …`, `feat(metadata): …`, `docs: …`.

**Comments explain the why.** The mechanism of the bug, the theory that turned out to be wrong,
the cheaper fix that fails and the reason it fails. A comment restating the line below it is
noise; a comment saying "reading this big-endian gives a vendor string several gigabytes long,
which is the first thing to check when this parser appears to be reading garbage" saves the next
person an hour.

**The repository speaks in one voice** — a single developer documenting their own work. Code
comments and commit messages do not name people, quote anyone, or frame a change as satisfying
a request. Describe the defect and the reasoning, not who found it. `node tools/check-voice.mjs`
enforces it, and it applies to your comments too — it is nothing personal about contributors,
it is that attribution ages into noise and a public codebase reads better without it. Credit
for a contribution lives in the pull request and the git history, which is where it belongs and
where it is permanent.

**A test for anything that can be wrong.** Not coverage for its own sake — a test on the
decision. If your change picks between two behaviours, that choice is a function and the
function has a test naming the failure it prevents. Test names here are sentences:
`a picture larger than a plain frame size still reads back`, `offsets in front of moov are left
exactly alone`.

**Write the fixture by hand where the format is the point.** A test whose fixture computes its
offsets the same way the parser does will agree with a parser that is wrong.

---

## Things that will not be merged

Not judgements about you — these are just settled:

- **Anything that helps with piracy.** Filet reads and re-signs APKs because that is a real
  thing a developer does with their own files. A licence patcher, a bundled cracked-app source,
  anything aimed at paid apps: no, and it is not a conversation.
- **Telemetry, analytics, crash reporting to a server, ads, a paid tier, an account.** The
  README promises none of those and the promise is the point.
- **A change to the application ID.** It is frozen after the first release; changing it orphans
  every existing install's data.
- **A new dependency to do something small.** This app bundles very little on purpose. If a
  library is genuinely the right answer, say why in the issue — several are, and they are pinned
  to exact versions because a build has to be reproducible.
- **A `File` or a `ContentResolver` above the provider layer** without an allow-list entry and a
  reason. See R3.
- **A reformat, a rename sweep, or a style pass** mixed into a functional change. Either on its
  own is fine to propose.

---

## Reviewing is reading the reasoning

Expect questions about *why*, not about brace placement. The things most likely to come back:

- what happens on a network share, inside an archive, and on a path that is read-only
- what happens when the thing fails halfway
- whether a visible control can ever do nothing
- whether the claim the change makes is checkable

If a review asks for something and you disagree, push back with the reason. Being talked out of
a review comment is a normal outcome here.

---

## Licence

Filet is **GPL-3.0**. A contribution is under the same licence — there is no CLA and no
copyright assignment. You keep the copyright on what you write; it is licensed to everyone on
the same terms as the rest.

The desktop companion tool in this repository ships under its own release tags
(`filet-desktop-mount-tool-vX.Y.Z`) and the same licence.
