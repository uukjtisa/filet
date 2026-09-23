#!/usr/bin/env node
/**
 * The long-press menu on the app icon, and the pinned shortcuts it sits beside.
 *
 * Two different things share the word "shortcut" here and conflating them is the mistake worth
 * guarding against: **pinned** shortcuts are icons the user makes and places, and the app must
 * never create or remove one on its own; **dynamic** shortcuts are the app's own suggestions
 * in the long-press menu, which the app owns entirely and rewrites as state changes. Using the
 * wrong API for either silently destroys the other - `setDynamicShortcuts` on a list built for
 * pinning wipes the menu, and pinning from the menu path puts an icon on the home screen the
 * user never asked for.
 *
 * What is checked:
 *
 *  - every action in the menu is one the dispatcher can actually perform, so no entry does
 *    nothing when pressed;
 *  - every action can be parsed back from its stored name, because a launcher icon outlives
 *    the version that made it;
 *  - the menu is published from one place, so it cannot go stale;
 *  - pinning and the menu use different APIs.
 *
 *   node tools/check-shortcuts.mjs              check
 *   node tools/check-shortcuts.mjs --selftest   prove it catches each way this breaks
 *
 * Prints "SHORTCUTS OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";

const SRC = {
  store: "app/src/main/java/dev/niccc2007/filet/shortcuts/ShortcutStore.kt",
  menu: "app/src/main/java/dev/niccc2007/filet/shortcuts/LauncherShortcuts.kt",
  pin: "app/src/main/java/dev/niccc2007/filet/shortcuts/Shortcuts.kt",
  vm: "app/src/main/java/dev/niccc2007/filet/browser/BrowserViewModel.kt",
  graph: "app/src/main/java/dev/niccc2007/filet/FiletApp.kt",
  router: "app/src/main/java/dev/niccc2007/filet/shortcuts/ShortcutRouterActivity.kt",
};

/** Every AppAction the enum declares, with its label. */
export function actionsDeclared(storeSrc) {
  const body = storeSrc.slice(storeSrc.indexOf("enum class AppAction"));
  const end = body.indexOf("\n    ;");
  return [...body.slice(0, end < 0 ? body.length : end)
    .matchAll(/^\s{4}([A-Z_]+)\("([^"]*)",/gm)]
    .map((m) => ({ name: m[1], label: m[2] }));
}

/** Which actions the menu can put in front of the user. */
export function actionsInMenu(menuSrc) {
  return [...menuSrc.matchAll(/AppAction\.([A-Z_]+)/g)].map((m) => m[1]);
}

/** Which actions the dispatcher has a branch for. */
export function actionsDispatched(vmSrc) {
  const start = vmSrc.indexOf("AppAction.parse(raw)");
  if (start < 0) return [];
  const body = vmSrc.slice(start, vmSrc.indexOf("null ->", start));
  return [...body.matchAll(/AppAction\.([A-Z_]+)\s*->/g)].map((m) => m[1]);
}

export function problems(src) {
  const found = [];
  if (!src.store) return ["AppAction is missing entirely"];
  if (!src.menu) return ["the launcher menu is missing entirely"];

  const declared = actionsDeclared(src.store);
  if (declared.length === 0) found.push("no AppAction values could be read");

  const names = declared.map((a) => a.name);
  const menu = [...new Set(actionsInMenu(src.menu))].filter((a) => names.includes(a));
  const dispatched = actionsDispatched(src.vm);

  // The failure that matters most: a menu entry with nothing behind it presses and does
  // nothing at all, which reads as the app being broken rather than the entry being missing.
  for (const a of menu) {
    if (!dispatched.includes(a)) {
      found.push(`${a} is offered in the launcher menu but the dispatcher never performs it`);
    }
  }
  // And the other direction, for the pinnable catalogue: every declared action is pinnable
  // from the Shortcuts tab, so one with no branch is a pinnable icon that does nothing.
  for (const a of names) {
    if (!dispatched.includes(a)) {
      found.push(`${a} can be pinned but the dispatcher never performs it`);
    }
  }

  // Start and Stop must never both be offered - one of the two would always be wrong.
  if (!/if \(sharing\)/.test(src.menu)) {
    found.push("the menu does not vary with whether sharing is running, so one entry is always wrong");
  }
  if (!/MAX/.test(src.menu)) {
    found.push("the menu declares no cap, so the platform silently drops entries");
  }

  // Parsing back, because a launcher icon outlives the version that made it.
  if (!/fun parse\(/.test(src.store)) {
    found.push("AppAction cannot be parsed from a stored name");
  }
  if (!/entries\.firstOrNull/.test(src.store) || /valueOf\(raw/.test(src.store)) {
    found.push("AppAction.parse throws on an unknown name instead of returning null");
  }

  // Published from one place. Seventeen state writes, one collector.
  if (!/LauncherShortcuts\.publish/.test(src.graph)) {
    found.push("nothing publishes the launcher menu, so it never reflects the share");
  }
  if (!/distinctUntilChanged/.test(src.graph)) {
    found.push(
      "the menu republishes on every state change, which hits the platform rate limit " +
        "and then stops updating at all",
    );
  }

  // The two APIs must stay apart.
  if (!/dynamicShortcuts\s*=/.test(src.menu)) {
    found.push("the menu does not use dynamic shortcuts");
  }
  if (/requestPinShortcut/.test(src.menu)) {
    found.push("the menu pins an icon to the home screen, which the user never asked for");
  }
  if (/dynamicShortcuts\s*=/.test(src.pin)) {
    found.push("the pinning path writes dynamic shortcuts, which wipes the long-press menu");
  }
  if (!/requestPinShortcut/.test(src.pin)) {
    found.push("pinning no longer asks the launcher, so the Shortcuts tab cannot pin anything");
  }

  // Failure here must never take the app down: these APIs throw on rate limiting and on
  // launchers that implement them badly.
  if (!/runCatching \{ sm\.dynamicShortcuts = infos \}/.test(src.menu)) {
    found.push("publishing the menu is unguarded, so a launcher that refuses crashes the app");
  }

  for (const a of declared) {
    if (a.label.length > 16) {
      found.push(`"${a.label}" is too long for a launcher menu and will be truncated`);
    }
  }

  if (!/EXTRA_ACTION/.test(src.router)) {
    found.push("the router does not read an action, so no menu entry can reach the app");
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
  const base = problems(good);
  if (base.length) {
    console.error("SELFTEST cannot run: the tree does not pass its own check");
    for (const p of base) console.error("  " + p);
    process.exit(1);
  }
  const swap = (key, from, to) => {
    const c = { ...good };
    c[key] = c[key].split(from).join(to);
    if (c[key] === good[key]) throw new Error(`mutation did not apply: ${from}`);
    return c;
  };

  const CASES = [
    ["a menu entry with no dispatcher branch", () =>
      swap("vm", "AppAction.BOOKMARKS ->", "AppAction.NOTHING ->")],
    ["a pinnable action with no branch", () =>
      swap("vm", "AppAction.RECENT ->", "AppAction.GONE ->")],
    ["the menu no longer varying with the share", () => swap("menu", "if (sharing)", "if (false)")],
    ["the cap removed", () => swap("menu", "MAX", "NOCAP")],
    ["parse made to throw", () =>
      swap("store", "entries.firstOrNull { it.name == raw }", "valueOf(raw!!)")],
    ["nothing publishing the menu", () => swap("graph", "LauncherShortcuts.publish", "nothing")],
    ["republishing on every change", () => swap("graph", "distinctUntilChanged", "map { it }")],
    ["the menu pinning icons instead", () =>
      swap("menu", "sm.dynamicShortcuts = infos", "sm.requestPinShortcut(infos[0], null)")],
    ["the pin path wiping the menu", () =>
      swap("pin", "sm.requestPinShortcut(info, null)", "sm.dynamicShortcuts = listOf(info)")],
    ["publishing left unguarded", () =>
      swap("menu", "runCatching { sm.dynamicShortcuts = infos }", "sm.dynamicShortcuts = infos")],
    ["a label too long for a launcher", () =>
      swap("store", '"Index now"', '"Index the whole device now"')],
    ["the router forgetting how to read an action", () => swap("router", "EXTRA_ACTION", "GONE")],
  ];

  let bad = 0;
  for (const [name, mutate] of CASES) {
    if (problems(mutate()).length === 0) {
      console.error(`SELFTEST FAILED: ${name} — not caught`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(`SHORTCUTS SELFTEST OK  (${CASES.length} negative controls, 1 positive)`);
  process.exit(0);
}

const src = read();
const found = problems(src);
if (found.length) {
  console.error("The shortcuts do not hold up:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const declared = actionsDeclared(src.store);
const menu = [...new Set(actionsInMenu(src.menu))].filter((a) =>
  declared.some((d) => d.name === a));
console.log(
  `SHORTCUTS OK  (${declared.length} actions, all dispatched; ` +
    `${menu.length} reachable from the long-press menu, published once and never pinned)`,
);
