#!/usr/bin/env node
/**
 * Refresh reaches every tab, both ends of a list ask for it, and a new file waits at the bottom.
 *
 * Three mechanisms reported together, and the first one is the reason this file exists rather
 * than another assertion in `RefreshTest`. That test proves every pane kind has a *plan*, and
 * it passed the whole time three of the plan's targets were routed to a shared `-> Unit`
 * branch whose comment claimed all three were live. One of them was not: the Remotes pane
 * reads its own revision counter, nothing was bumping it, and the button toasted success while
 * re-reading nothing.
 *
 * So the table being complete is not the property worth checking - the table was complete. The
 * property is that **every target the table can name is executed**, and that a target excused
 * as live-by-construction says so on its own line where it can be disagreed with.
 *
 *   node tools/check-refresh.mjs              check
 *   node tools/check-refresh.mjs --selftest   prove it catches each way this broke
 *
 * Prints "REFRESH OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";

const SRC = {
  plan: "app/src/main/java/dev/niccc2007/filet/browser/Refresh.kt",
  vm: "app/src/main/java/dev/niccc2007/filet/browser/BrowserViewModel.kt",
  pull: "app/src/main/java/dev/niccc2007/filet/browser/EdgePull.kt",
  pullWire: "app/src/main/java/dev/niccc2007/filet/browser/EdgePullModifier.kt",
  pane: "app/src/main/java/dev/niccc2007/filet/browser/PaneView.kt",
  tail: "app/src/main/java/dev/niccc2007/filet/browser/FreshTail.kt",
  ctl: "app/src/main/java/dev/niccc2007/filet/browser/PaneController.kt",
};

/** Every `RefreshTarget` the enum declares. */
export function targetsDeclared(planSrc) {
  const body = planSrc.slice(
    planSrc.indexOf("enum class RefreshTarget"),
    planSrc.indexOf("data class RefreshPlan"),
  );
  return [...body.matchAll(/^\s{4}([A-Z_]+),/gm)].map((m) => m[1]);
}

/**
 * How the executor answers each target: the right-hand side of its branch.
 *
 * Returns a map of target -> the code it runs, with targets sharing one branch mapped to the
 * same string. A target absent from the map is one the executor never mentions.
 */
export function executorBranches(vmSrc) {
  const start = vmSrc.indexOf("fun refreshPane(");
  if (start < 0) return null;
  const body = vmSrc.slice(start, vmSrc.indexOf("\n    fun ", start + 40));
  const out = new Map();
  for (const m of body.matchAll(/RefreshTarget\.([A-Z_]+)((?:\s*,\s*RefreshTarget\.[A-Z_]+)*)\s*->\s*(.+)/g)) {
    const names = [m[1], ...[...m[2].matchAll(/RefreshTarget\.([A-Z_]+)/g)].map((x) => x[1])];
    for (const n of names) out.set(n, { action: m[3].trim(), shared: names.length > 1 });
  }
  return out;
}

export function problems(src) {
  const found = [];

  // ── 1. the refresh button reaches every tab ──────────────────────────────────────────
  const declared = targetsDeclared(src.plan);
  if (declared.length === 0) found.push("no RefreshTarget values found - has the enum moved?");

  const branches = executorBranches(src.vm);
  if (branches === null) {
    found.push("refreshPane() not found in the view model");
  } else {
    for (const t of declared) {
      const b = branches.get(t);
      if (!b) {
        found.push(`RefreshTarget.${t} is declared but the executor never handles it`);
        continue;
      }
      // A target that does nothing is allowed ONLY alone, so the reason it is allowed is
      // attached to it and not to a group. This is exactly how REMOTES hid.
      if (/^Unit\b/.test(b.action) && b.shared) {
        found.push(
          `RefreshTarget.${t} shares a do-nothing branch with other targets - ` +
            `give it its own line and its own reason, or it hides a dead one`,
        );
      }
    }
    const remotes = branches.get("REMOTES");
    if (remotes && /^Unit\b/.test(remotes.action)) {
      found.push("RefreshTarget.REMOTES does nothing: the pane reads its own revision counter");
    }
  }
  if (!/fun bumpRemotes\(\)/.test(src.vm)) {
    found.push("nothing bumps the remotes revision, so that pane cannot be refreshed");
  }

  // ── 2. both ends of the list ask for a refresh ───────────────────────────────────────
  if (!existsSync("app/src/main/java/dev/niccc2007/filet/browser/EdgePull.kt")) {
    found.push("EdgePull is missing entirely");
  } else {
    for (const [what, re] of [
      ["a TOP edge", /PullEdge\.TOP/],
      ["a BOTTOM edge", /PullEdge\.BOTTOM/],
      ["a threshold", /THRESHOLD_DP/],
    ]) {
      if (!re.test(src.pull)) found.push(`EdgePull declares no ${what}`);
    }
    // The bottom is the half a conventional implementation leaves out, and it is the half he
    // asked for by name. Check the wiring asks about it, not only that the enum has the word.
    if (!/atBottom\s*=/.test(src.pane)) {
      found.push("the file list never asks whether it is at the bottom, so only the top pulls");
    }
    if (!/atTop\s*=/.test(src.pane)) {
      found.push("the file list never asks whether it is at the top");
    }
    if (!/NestedScrollSource\.UserInput/.test(src.pullWire)) {
      found.push(
        "the pull does not filter for real input, so a programmatic scroll to an end refreshes",
      );
    }
  }

  // ── 3. a new file waits at the bottom ────────────────────────────────────────────────
  if (!/class FreshTail/.test(src.tail)) {
    found.push("FreshTail is missing entirely");
  }
  if (!/freshTail\.applyTo\(/.test(src.ctl)) {
    found.push("the tail is never applied to the listing, so a new file sorts away immediately");
  }
  if (!/fun noteArrived\(/.test(src.ctl)) {
    found.push("nothing can tell a pane that a file arrived");
  }
  // The distinction the whole thing turns on: the re-list after a write must NOT settle.
  if (!/fun relist\(\)/.test(src.ctl)) {
    found.push("there is no relist() separate from refresh(), so writing a file settles the tail");
  }
  const rp = src.vm.slice(src.vm.indexOf("fun refreshPanes()"));
  const rpBody = rp.slice(0, rp.indexOf("\n    }"));
  if (/\.refresh\(\)/.test(rpBody)) {
    found.push(
      "refreshPanes() calls refresh(), which settles the tail - " +
        "a pasted file would sort away in the frame it appeared",
    );
  }
  if (!/noteArrived\(/.test(src.vm)) {
    found.push("no operation reports what it created, so the tail is never populated");
  }

  return found;
}

function read() {
  const out = {};
  for (const [k, p] of Object.entries(SRC)) out[k] = existsSync(p) ? readFileSync(p, "utf8") : "";
  return out;
}

if (process.argv.includes("--selftest")) {
  const good = read();
  if (problems(good).length) {
    console.error("SELFTEST cannot run: the tree does not pass its own check");
    for (const p of problems(good)) console.error("  " + p);
    process.exit(1);
  }
  const swap = (key, from, to) => {
    const copy = { ...good };
    copy[key] = copy[key].split(from).join(to);
    if (copy[key] === good[key]) throw new Error(`mutation did not apply: ${from}`);
    return copy;
  };

  const CASES = [
    // The exact shape this shipped in: three targets, one branch, one comment.
    ["remotes back in a shared do-nothing branch", () => {
      const c = { ...good };
      c.vm = c.vm
        .replace("RefreshTarget.REMOTES -> bumpRemotes()", "")
        .replace(
          "RefreshTarget.INDEX_STATUS -> Unit",
          "RefreshTarget.INDEX_STATUS, RefreshTarget.REMOTES -> Unit",
        );
      return c;
    }],
    ["a target the executor forgets", () =>
      swap("vm", "RefreshTarget.SCRIPTS -> graph.scripts.reload()", "")],
    ["nothing bumps the remotes revision", () => swap("vm", "fun bumpRemotes()", "fun unusedName()")],
    ["the bottom edge dropped from the gesture", () => swap("pane", "atBottom =", "atBottomUnused =")],
    ["the top edge dropped from the gesture", () => swap("pane", "atTop =", "atTopUnused =")],
    ["a programmatic scroll allowed to refresh", () =>
      swap("pullWire", "NestedScrollSource.UserInput", "NestedScrollSource.Drag")],
    ["the tail never reaching the listing", () => swap("ctl", "freshTail.applyTo(", "identity(")],
    ["relist collapsed back into refresh", () => swap("ctl", "fun relist()", "fun relistUnused()")],
    ["refreshPanes settling the tail it just filled", () =>
      swap("vm", "?.relist()", "?.refresh()")],
    ["no operation reporting what it made", () => swap("vm", "noteArrived(", "ignored(")],
  ];

  let bad = 0;
  for (const [name, mutate] of CASES) {
    if (problems(mutate()).length === 0) {
      console.error(`SELFTEST FAILED: ${name} — not caught`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(`REFRESH SELFTEST OK  (${CASES.length} negative controls, 1 positive)`);
  process.exit(0);
}

const found = problems(read());
if (found.length) {
  console.error("Refresh does not reach everything it claims to:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const declared = targetsDeclared(read().plan);
console.log(
  `REFRESH OK  (${declared.length} targets, every one executed; both list ends pull; ` +
    `a new file waits at the bottom until it settles)`,
);
