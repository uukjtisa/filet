// ---------------------------------------------------------------------------
//  check-launch — the first screen does not wait for the network, a failed
//  read never blanks the storage cards, and every run leaves a log.
//
//  Usage:
//    node tools/check-launch.mjs              check the tree
//    node tools/check-launch.mjs --selftest   prove the rules catch what they claim
//
//  Three reported faults, measured on a phone before any of this was written,
//  and all three were one shape: work that nothing needed yet, done before the
//  thing somebody is waiting for.
//
//  RULE 1 — THE TABS DO NOT WAIT FOR THE DRIVES. Measuring a volume asks it for
//  its free space, and for a mounted share that is a network round trip: one
//  that is asleep costs a connect timeout per saved address, twice over. That
//  ran BEFORE `restoreTabs`, so two shares that were away held the first screen
//  for twenty seconds and nothing on it needed them. Measured after the fix:
//  first screen ready at 2.3s on a debug build.
//
//  RULE 2 — A FAILED READ NEVER BLANKS A CARD. `roots()` returning nothing is
//  not the same fact as there being no volumes; it is also what a throw looks
//  like after `getOrElse`. Home hides a card with no figures, so one failed
//  measurement took the card off the screen - which is the reported fault of
//  the drive information vanishing "as if filet got disconnected from my
//  device". The last known figure is kept instead.
//
//  RULE 3 — EVERY RUN LEAVES A LOG, AND ONE PER RUN. Opened at process start,
//  before the graph, because the graph build is itself on the path being
//  timed. Idempotent, because Android keeps a process alive in the background
//  and a second file per return to the app scatters one session across many.
// ---------------------------------------------------------------------------
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const APP = join(ROOT, "app/src/main/java/dev/niccc2007/filet");
const VM = join(APP, "browser/BrowserViewModel.kt");
const HOME = join(APP, "home/HomeOverview.kt");
const LOG = join(APP, "log/FiletLog.kt");
const APPLICATION = join(APP, "FiletApp.kt");

// Comments are blanked rather than removed so every offset stays put, and so a
// file's own prose about a rule cannot satisfy the rule it describes.
const strip = (src) =>
  src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/^([ \t]*)\/\/.*$/gm, (m, i) => i + " ".repeat(m.length - i.length));

/**
 * Does the tab restore happen before the volumes are measured?
 *
 * Order within the one block that runs on start, by position. Crude on purpose:
 * the fault was literally a line being above another line, and a rule that can
 * only be satisfied by putting it back is the rule worth having.
 */
export function tabsBeforeVolumes(src) {
  const block = src.split(/fun start\(\)/)[1];
  if (!block) return { ok: false, why: "no start()" };
  const body = block.slice(0, 1800);
  const tabs = body.indexOf("restoreTabs(");
  const volumes = Math.max(body.indexOf("loadVolumes("), body.indexOf("volumeInfos("));
  if (tabs < 0) return { ok: false, why: "start() never restores the tabs" };
  if (volumes < 0) return { ok: true, why: "start() does not measure volumes at all" };
  return {
    ok: tabs < volumes,
    why:
      tabs < volumes
        ? "tabs restore first"
        : "the volumes are measured before the tabs are restored, so one sleeping share " +
          "decides when the first screen appears",
  };
}

const fails = [];

if (!process.argv.includes("--selftest")) {
  const vm = strip(readFileSync(VM, "utf8"));

  // Rule 1.
  const order = tabsBeforeVolumes(vm);
  if (!order.ok) fails.push(`BrowserViewModel.start — ${order.why}`);
  if (!/MEASURE_BUDGET_MS/.test(vm)) {
    fails.push(
      "BrowserViewModel — a volume's measurement is unbounded, so a share that never answers " +
        "has no ceiling on what it costs",
    );
  }
  if (!/VolumePolling\.shouldProbe\(/.test(vm)) {
    fails.push(
      "BrowserViewModel — a volume that is not answering is re-probed on every poll; the " +
        "backoff is a tested decision and is not being used",
    );
  }

  // Rule 2.
  const loadVolumes = vm.split(/suspend fun loadVolumes\(\)/)[1]?.slice(0, 900) ?? "";
  if (/getOrElse \{ emptyList\(\) \}/.test(loadVolumes)) {
    fails.push(
      "BrowserViewModel.loadVolumes — a failed roots() becomes an empty list and blanks every " +
        "card, which is the reported fault of the drive information disappearing",
    );
  }
  if (!/keeping/.test(loadVolumes) && !/return$/m.test(loadVolumes)) {
    fails.push("BrowserViewModel.loadVolumes — nothing keeps the previous cards when a read fails");
  }
  const home = strip(readFileSync(HOME, "utf8"));
  if (/scheme == "local" \|\| \(it\.free != null/.test(home)) {
    fails.push(
      'HomeOverview — hides any card whose scheme is not "local" and has no figures, which takes ' +
        "root: and saf: volumes off Home for one slow measurement",
    );
  }
  if (!/!it\.remote \|\|/.test(home)) {
    fails.push("HomeOverview — the card filter no longer asks whether the volume is remote");
  }

  // Rule 3.
  const log = strip(readFileSync(LOG, "utf8"));
  if (!/compareAndSet\(false, true\)/.test(log)) {
    fails.push(
      "FiletLog.open — not idempotent: a process kept alive in the background would start a " +
        "second session file on the next launch and split one run across two",
    );
  }
  if (!/fun prune\(/.test(log) || !/KEEP/.test(log)) {
    fails.push("FiletLog — nothing prunes old sessions, so the folder grows without limit");
  }
  const application = strip(readFileSync(APPLICATION, "utf8"));
  const onCreate = application.split(/override fun onCreate\(\)/)[1]?.slice(0, 1200) ?? "";
  const opens = onCreate.indexOf("FiletLog.open(");
  if (opens < 0) {
    fails.push("FiletApp.onCreate — no session is opened, so a run leaves no log at all");
  }
  if (!/val graph: FiletGraph by lazy/.test(application)) {
    fails.push("FiletApp — the graph is no longer lazy, so it is built before the log is open");
  }
}

if (process.argv.includes("--selftest")) {
  const good = `
    fun start() {
        viewModelScope.launch {
            val roots = vfs.roots()
            restoreTabs(roots.firstOrNull()?.path)
            loadVolumes()
        }
    }
  `;
  const bad = `
    fun start() {
        viewModelScope.launch {
            val roots = vfs.roots()
            _state.update { it.copy(volumes = volumeInfos(roots)) }
            restoreTabs(roots.firstOrNull()?.path)
        }
    }
  `;
  const cases = [
    ["tabs restored before the drives are measured", good, true],
    ["drives measured before the tabs", bad, false],
    ["no start() at all", "fun other() {}", false],
  ];
  let broken = 0;
  for (const [name, src, want] of cases) {
    const got = tabsBeforeVolumes(src).ok;
    const ok = got === want;
    if (!ok) broken++;
    console.log(`  ${ok ? "ok  " : "FAIL"}  ${name} (expected ${want}, got ${got})`);
  }
  if (broken) {
    console.error("LAUNCH SELFTEST FAILED");
    process.exit(1);
  }
  console.log("LAUNCH SELFTEST OK");
  process.exit(0);
}

if (fails.length) {
  console.error("LAUNCH FAILED:");
  for (const f of fails) console.error("  - " + f);
  process.exit(1);
}
console.log(
  "LAUNCH OK  (the tabs do not wait for the drives, a failed read keeps the cards it had, and " +
    "every run opens one session log at process start)",
);
