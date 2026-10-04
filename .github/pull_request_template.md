<!--
  Delete whatever does not apply. A one-line fix does not need every heading filled in.
  CONTRIBUTING.md explains what gets asked at review and why.
-->

## What this changes

<!-- The defect or the gap, then the change. Not a restatement of the diff. -->

## Why this fix rather than another

<!--
  The part worth writing. What the cause turned out to be, what the cheaper fix would have
  been and why it fails. If a theory looked right and was not, say so - it saves the next
  person the same detour.
-->

## How it was checked

```
./gw.sh :app:testGithubDebugUnitTest
node tools/check-all.mjs
```

<!-- Paste the result. If a checker had to change, say which and why it was wrong. -->

- [ ] Unit tests pass
- [ ] `node tools/check-all.mjs` reports ALL CHECKS OK
- [ ] Anything a finger and a pair of eyes had to confirm was confirmed on a device, and I
      have said which device below

## The four rules

<!-- Tick what applies; cross out what does not. See CONTRIBUTING.md. -->

- [ ] **R1** — no control this adds can ever be visible and do nothing
- [ ] **R2** — no filesystem call runs on the main thread
- [ ] **R3** — nothing above the provider layer touches `File` or a `ContentResolver`, or it
      is allow-listed with its reason
- [ ] **R4** — every claim this adds is checked by a test or a checker

## Where it was exercised

<!-- A bug that only shows up on one backend is the normal case here. -->

- [ ] Internal storage
- [ ] An SD card or USB drive
- [ ] Inside an archive
- [ ] A network share
- [ ] Another Filet over the network
- [ ] Not applicable

## Anything left undone

<!-- Known gaps, a follow-up worth filing, a case deliberately not handled and why. -->
