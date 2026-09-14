# How this was built

Filet is written with heavy AI assistance. This page says exactly what that means here,
because the phrase covers everything from "autocomplete finished my variable name" to "I
typed a prompt and pushed whatever came out", and those are not the same project.

This page is written the same way the project is. **"Why I am telling you" below is mine** - my words and my
argument, with nothing done to them but the spelling. The rest of this page was drafted by AI
against a brief from me and edited by me, which would be a silly thing to hide on a page about
exactly that.

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
- **The review loop.** I use this app every day on my own phone, on my own files. Eight
  rounds of that are written down in `FIXES.md`, item by item, with what was wrong and what
  the evidence is that it is fixed now.

AI wrote most of the code. Implementation, refactoring, test authoring, documentation
drafting, and the exploratory work of finding out whether an approach is viable at all.

## Why I am telling you

There's a culture of people that likes to call stuff AI slop without considering the thought
and work put into designing the pipelines or the face of a project.

And I used AI here to boost my productivity. I like to create, and I don't wanna take all year
creating something, cause I have a lot of ideas popping in mind. It's the "scratching your own
itch" thing. I like to create stuff that will solve my smallest to biggest problems.
And AI use has helped me with that a lot.


## What that means for you as a user or a contributor

- Read the code. It is GPL-3.0 and it is all here.
- The design documents are published with it on purpose: `PLAN.md`, `SEARCH.md`,
  `NEARBY.md`, `TEMPLATE.md`. They are the argument, and the code is the implementation of
  it. If the two disagree, that is a bug worth filing.
- The check scripts in `tools/` run in CI. If a change breaks the layering, the build says
  so before a human has to notice.
- Issues and pull requests are read by me.

— Niccc2007 ([@uukjtisa](https://github.com/uukjtisa))
