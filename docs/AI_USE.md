# How this was built

Filet is written with heavy AI assistance. This page says exactly what that means here,
because the phrase covers everything from "autocomplete finished my variable name" to "I
typed a prompt and pushed whatever came out", and those are not the same project.

## Who decided what

I designed the system. That is the part that determines what this app is:

- **The layer model.** L0 virtual filesystem, L1 index, L2 routing, L3 handlers, L4 runtime,
  L5 surfaces. Every backend is a provider behind one interface, which is why a Lua script
  can process a file on an SMB share it has never seen, and why an `.apk` opens as a folder.
- **The rules that constrain it**, and the fact that they are enforced by scripts rather than
  by intention. R1: no control that does nothing. R3: nothing above L0 touches storage
  directly, checked by `tools/check-r3.mjs` on every build. R4: every claim is checkable.
- **What goes in and what does not.** Read `FIXES.md` and `GATES.md`: the rejections are
  mine, the scope calls are mine, and so is the decision to mark the root gate failed rather
  than quietly drop it.
- **The review loop.** I use this app every day on my own phone, on my own files. Seven
  rounds of that are written down in `FIXES.md`, item by item, with what was wrong and what
  the evidence is that it is fixed now.

AI wrote most of the code. Implementation, refactoring, test authoring, documentation
drafting, and the exploratory work of finding out whether an approach is viable at all.

## Why I am telling you

Two reasons, and neither is an apology.

The first is that you should know what you are reading before you judge it. If a design
decision in here is wrong, it is wrong because I decided it, and I would rather be argued
with about the decision than credited for the typing.

The second is that "AI-assisted" is doing a lot of work as an insult right now, usually
aimed at code nobody checked. So here is the check: every milestone in `GATES.md` is a
demonstrated outcome, not an assertion. The gates caught three features that were built,
looked finished, and were dead. A pinned shortcut whose id died the moment the file moved,
which was the entire point of the feature. Every rebuilt APK emitting a DEX version that
cannot run below API 35. A WebDAV client that had never worked, because Android's
`HttpURLConnection` refuses the PROPFIND verb outright. None of those would have surfaced
from reading the diff, and none of them would have surfaced from trusting a summary.

## What that means for you as a user or a contributor

- Read the code. It is GPL-3.0 and it is all here.
- The design documents are published with it on purpose: `PLAN.md`, `SEARCH.md`,
  `NEARBY.md`, `TEMPLATE.md`. They are the argument, and the code is the implementation of
  it. If the two disagree, that is a bug worth filing.
- The check scripts in `tools/` run in CI. If a change breaks the layering, the build says
  so before a human has to notice.
- Issues and pull requests are read by me.

— Niccc2007 ([@uukjtisa](https://github.com/uukjtisa))
