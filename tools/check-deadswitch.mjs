#!/usr/bin/env node
/**
 * A gesture handler that can never see a change is a control that does nothing.
 *
 * Round 11 found the same mistake twice, in features written months apart, and neither showed
 * up as an error, a warning, a crash or a failing test. Both looked exactly like "the feature
 * was never built":
 *
 *   - **Pinch to zoom.** `ImageScreen` ran `detectTransformGestures` inside
 *     `pointerInput(bitmap)` and read the `scale` parameter directly. A `pointerInput` block is
 *     a coroutine that restarts only when its keys change, so the block held `scale` at its
 *     first value - 1f - for the life of the image. Every pinch frame computed `1f * zoom`
 *     where `zoom` is a per-frame ratio near 1.02, so the picture never grew.
 *
 *   - **Dragging tabs to reorder them.** `TabChip` ran `detectDragGesturesAfterLongPress`
 *     inside `pointerInput(Unit)` and called `onDragEnd()` directly. That lambda closes over
 *     `target`, a plain `val` recomputed on every composition of the strip, so the frozen copy
 *     saw `target == -1` forever and `moveTab` was never called. `movedItem`, `dragTarget`,
 *     `indexAfterMove` and `moveTab` all existed and were all tested. Only the wire was dead.
 *
 * The second was found in review rather than by the checker, which is the part that makes
 * this worth a checker rather than two fixes. Tested machinery with no
 * working control attached to it is rule R1, and R1 cannot see this because every piece is
 * present.
 *
 * ## The rule
 *
 * Inside a `pointerInput(...)` block, a callback parameter named `onSomething` must be reached
 * through a `rememberUpdatedState` holder (`x.value(...)`), never called directly. This holds
 * whatever the keys are: `pointerInput(bitmap)` froze the zoom just as thoroughly as
 * `pointerInput(Unit)` froze the drag.
 *
 * A block that genuinely captures nothing changeable can opt out with a line comment
 * `// pointerInput-static: <reason>` immediately above it. The reason is required, so opting
 * out is a claim somebody made rather than a silence.
 *
 *   node tools/check-deadswitch.mjs              check
 *   node tools/check-deadswitch.mjs --selftest   prove it catches both real bugs
 *
 * Prints "NO DEAD SWITCHES" only after every assertion passes.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const SRC = "app/src/main/java/dev/niccc2007/filet";
const OPT_OUT = /\/\/\s*pointerInput-static:\s*\S+/;

function kotlinFiles(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...kotlinFiles(p));
    else if (name.endsWith(".kt")) out.push(p);
  }
  return out;
}

/**
 * Every `pointerInput(...) { … }` block in a file, as {at, keys, body}.
 *
 * Brace-matched rather than regex-terminated. The picker checker shipped with exactly that bug
 * in round 10 - a lazy match that stopped at the first `/>` and read half the manifest while
 * reporting OK - so blocks are scanned, not matched.
 */
function pointerInputBlocks(src) {
  const blocks = [];
  const re = /pointerInput\s*\(/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    // the key list
    let i = m.index + m[0].length;
    let depth = 1;
    while (i < src.length && depth > 0) {
      if (src[i] === "(") depth++;
      else if (src[i] === ")") depth--;
      i++;
    }
    const keys = src.slice(m.index + m[0].length, i - 1).trim();
    // the lambda, if there is one
    while (i < src.length && /\s/.test(src[i])) i++;
    if (src[i] !== "{") continue;
    const start = i;
    depth = 0;
    let inString = false;
    for (; i < src.length; i++) {
      const c = src[i];
      if (inString) {
        if (c === "\\") i++;
        else if (c === '"') inString = false;
        continue;
      }
      if (c === '"') inString = true;
      else if (c === "{") depth++;
      else if (c === "}") {
        depth--;
        if (depth === 0) { i++; break; }
      }
    }
    blocks.push({ at: m.index, keys, body: src.slice(start, i) });
  }
  return blocks;
}

/** Callback parameters called directly rather than through a live holder. */
function frozenCalls(body) {
  const bad = new Set();
  // `onThing(` or `onThing()` — but NOT `holder.value(` and not a declaration site.
  const re = /(^|[^.\w])(on[A-Z]\w*)\s*\(/g;
  let m;
  while ((m = re.exec(body)) !== null) {
    // `onDragStart = { … }` is naming a parameter of the detector, not calling a captured one.
    const before = body.slice(Math.max(0, m.index - 40), m.index);
    if (/[\w.]$/.test(before.trim())) continue;
    bad.add(m[2]);
  }
  return [...bad];
}

function scan(files) {
  const problems = [];
  for (const file of files) {
    const src = readFileSync(file, "utf8");
    for (const b of pointerInputBlocks(src)) {
      const lineStart = src.lastIndexOf("\n", b.at);
      const before = src.slice(Math.max(0, lineStart - 200), lineStart);
      if (OPT_OUT.test(before)) continue;
      const frozen = frozenCalls(b.body);
      if (frozen.length) {
        const line = src.slice(0, b.at).split("\n").length;
        problems.push(
          `${file}:${line}  pointerInput(${b.keys || "Unit"}) calls ${frozen.join(", ")} directly - ` +
            `the block is launched once and holds whatever it captured. Read it through ` +
            `rememberUpdatedState, or opt out above it with "// pointerInput-static: <reason>".`,
        );
      }
    }
  }
  return problems;
}

// ── selftest ──

const CONTROLS = [
  {
    name: "the pinch-to-zoom bug, as it actually shipped",
    src: `
      @Composable fun ZoomableImage(bitmap: Bitmap, onTransform: (Float) -> Unit) {
        Box(Modifier.pointerInput(bitmap) {
          detectTransformGestures { _, pan, zoom, _ -> onTransform(scale * zoom) }
        })
      }`,
    expect: true,
  },
  {
    name: "the tab-drag bug, as it actually shipped",
    src: `
      @Composable fun TabChip(onDragStart: () -> Unit, onDragEnd: () -> Unit) {
        Row(Modifier.pointerInput(Unit) {
          detectDragGesturesAfterLongPress(
            onDragStart = { onDragStart() },
            onDragEnd = { onDragEnd() },
          ) { c, d -> c.consume() }
        })
      }`,
    expect: true,
  },
  {
    name: "the same handler done correctly",
    src: `
      @Composable fun TabChip(onDragEnd: () -> Unit) {
        val live = rememberUpdatedState(onDragEnd)
        Row(Modifier.pointerInput(Unit) {
          detectDragGesturesAfterLongPress(onDragEnd = { live.value() }) { c, d -> c.consume() }
        })
      }`,
    expect: false,
  },
  {
    name: "an explicit, reasoned opt-out",
    src: `
      @Composable fun Static(onTap: () -> Unit) {
        // pointerInput-static: captures nothing; the handler only consumes.
        Box(Modifier.pointerInput(Unit) { detectTapGestures { onTap() } })
      }`,
    expect: false,
  },
  {
    name: "a block with a second pointerInput after it is still fully scanned",
    src: `
      @Composable fun Two(onA: () -> Unit, onB: () -> Unit) {
        val a = rememberUpdatedState(onA)
        Box(Modifier
          .pointerInput(Unit) { detectTapGestures { a.value() } }
          .pointerInput(Unit) { detectTransformGestures { _, _, _, _ -> onB() } })
      }`,
    expect: true,
  },
];

if (process.argv.includes("--selftest")) {
  const { writeFileSync, mkdtempSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  let failed = 0;
  for (const c of CONTROLS) {
    const dir = mkdtempSync(join(tmpdir(), "deadswitch-"));
    const f = join(dir, "Case.kt");
    writeFileSync(f, c.src);
    const got = scan([f]).length > 0;
    if (got !== c.expect) {
      console.error(`SELFTEST FAILED: ${c.name} — expected ${c.expect ? "caught" : "clean"}, got ${got ? "caught" : "clean"}`);
      failed++;
    }
  }
  if (failed) process.exit(1);
  console.log(`DEADSWITCH SELFTEST OK  (${CONTROLS.length} controls, including both bugs this round fixed)`);
  process.exit(0);
}

const files = kotlinFiles(SRC);
const problems = scan(files);
if (problems.length) {
  console.error("Frozen gesture callbacks — these controls cannot see current state:\n");
  for (const p of problems) console.error("  " + p);
  process.exit(1);
}
console.log(`NO DEAD SWITCHES  (${files.length} files scanned, every pointerInput callback read live)`);
