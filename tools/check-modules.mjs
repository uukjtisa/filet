#!/usr/bin/env node
/**
 * G4 — the module boundaries are real, and the shared ones are genuinely platform-free.
 *
 * Two things are checked, and the second is the one that earns its keep.
 *
 * **The VFS lives behind its own module and `:app` depends on it.** That is the original G4 and
 * it is a structural claim: nothing above L0 constructs a platform path, which is only
 * enforceable if the provider is somewhere `:app` cannot reach around.
 *
 * **`:core-vfs-jvm` and `:core-dav` import nothing from Android.** These two were split out so
 * the desktop tool runs the same protocol code the phone does rather than a second copy of it,
 * and that only works while they stay platform-free. One `import android.os.Build` in there -
 * easy to add, since every file in them came out of an Android module and the IDE will offer
 * it - breaks the desktop build and nothing else. The phone keeps compiling, every test keeps
 * passing, and the damage shows up only when somebody builds the other half.
 *
 * It has already happened once, before the split was finished: `LocalProvider.toNodeByField`
 * reached `android.system.Os.lstat` for an inode, inside a `runCatching` that made it look
 * harmless. It compiled on Android forever and could never have compiled anywhere else.
 *
 *   node tools/check-modules.mjs              check
 *   node tools/check-modules.mjs --selftest   prove it catches each claim being broken
 *
 * Prints "MODULES OK" only after every assertion passes.
 */
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

/** Modules that must not name a platform, and why each one is in the list. */
const PLATFORM_FREE = {
  "core-vfs-jvm": "the VFS router, the path type and the local-disk provider, shared with the desktop tool",
  "core-dav": "the WebDAV protocol and server, shared with the desktop tool",
};

/** What a platform import looks like. `androidx` too: it is no more present off a device. */
const ANDROID = /^\s*import\s+(android|androidx)\./m;

/**
 * The one coroutines artifact these modules may not take.
 *
 * `kotlinx-coroutines-android` installs a `Main` dispatcher backed by a `Looper`. Depending on
 * it compiles fine and then throws at runtime on a desktop, which is the same shape of fault as
 * an Android import and invisible in exactly the same way.
 */
const ANDROID_COROUTINES = /kotlinx\.coroutines\.android/;

function kotlinFiles(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) kotlinFiles(p, out);
    else if (name.endsWith(".kt")) out.push(p);
  }
  return out;
}

export function problems(tree) {
  const out = [];
  const { settings, app, vfsBuild, modules, sources } = tree;

  if (!/include\(":core-vfs"\)/.test(settings)) out.push("settings.gradle.kts does not include :core-vfs");
  if (!/project\(":core-vfs"\)/.test(app)) out.push(":app does not depend on :core-vfs");

  for (const name of Object.keys(PLATFORM_FREE)) {
    if (!settings.includes(`include(":${name}")`)) out.push(`settings.gradle.kts does not include :${name}`);
    if (!modules[name]) out.push(`${name}/build.gradle.kts missing`);
    else if (ANDROID_COROUTINES.test(modules[name])) {
      out.push(`:${name} depends on kotlinx-coroutines-android, which needs a Looper`);
    }
  }

  // The re-export. Without `api`, every consumer of :core-vfs would have to name :core-vfs-jvm
  // as well, and the split would stop being invisible from above L0.
  if (modules["core-vfs"] && !/api\(project\(":core-vfs-jvm"\)\)/.test(vfsBuild)) {
    out.push(":core-vfs does not re-export :core-vfs-jvm with api(), so the split leaks upward");
  }
  if (!/project\(":core-dav"\)/.test(app)) out.push(":app does not depend on :core-dav");

  // The boundary only means something if the provider actually lives behind it.
  for (const f of [
    "core-vfs-jvm/src/main/java/dev/niccc2007/filet/vfs/FileSystemProvider.kt",
    "core-vfs-jvm/src/main/java/dev/niccc2007/filet/vfs/Vfs.kt",
    "core-vfs-jvm/src/main/java/dev/niccc2007/filet/vfs/provider/LocalProvider.kt",
    "core-dav/src/main/java/dev/niccc2007/filet/webdav/WebDavServer.kt",
    "core-dav/src/main/java/dev/niccc2007/filet/webdav/DavDiscovery.kt",
  ]) if (!sources[f]) out.push(`missing ${f}`);

  for (const [file, text] of Object.entries(sources)) {
    const owner = Object.keys(PLATFORM_FREE).find((m) => file.startsWith(m + "/"));
    if (!owner) continue;
    const hit = text.match(ANDROID);
    if (hit) out.push(`${file} imports ${hit[0].trim()} — :${owner} must stay platform-free`);
  }
  return out;
}

function read(p) {
  return existsSync(p) ? readFileSync(p, "utf8") : "";
}

function collect() {
  const sources = {};
  for (const m of [...Object.keys(PLATFORM_FREE)]) {
    for (const f of kotlinFiles(m)) sources[f.replace(/\\/g, "/")] = readFileSync(f, "utf8");
  }
  return {
    settings: read("settings.gradle.kts"),
    app: read("app/build.gradle.kts"),
    vfsBuild: read("core-vfs/build.gradle.kts"),
    modules: Object.fromEntries(
      ["core-vfs", ...Object.keys(PLATFORM_FREE)].map((m) => [m, read(`${m}/build.gradle.kts`)]),
    ),
    sources,
  };
}

const SELFTEST = process.argv.includes("--selftest");

if (SELFTEST) {
  const good = collect();
  if (problems(good).length) {
    console.error("SELFTEST cannot run: the tree does not pass its own check");
    for (const p of problems(good)) console.error("  " + p);
    process.exit(1);
  }
  const anyDavFile = Object.keys(good.sources).find((f) => f.startsWith("core-dav/"));
  const cases = [
    ["no :core-dav include", (t) => { t.settings = t.settings.replace('include(":core-dav")', ""); }],
    ["no :app -> :core-dav", (t) => { t.app = t.app.replace('project(":core-dav")', 'project(":nope")'); }],
    ["re-export downgraded", (t) => { t.vfsBuild = t.vfsBuild.replace('api(project(":core-vfs-jvm"))', 'implementation(project(":core-vfs-jvm"))'); }],
    ["android import in a shared module", (t) => { t.sources[anyDavFile] = "import android.os.Build\n" + t.sources[anyDavFile]; }],
    ["androidx import in a shared module", (t) => { t.sources[anyDavFile] = "import androidx.core.net.UriCompat\n" + t.sources[anyDavFile]; }],
    ["coroutines-android in a shared module", (t) => { t.modules["core-dav"] += "\ndependencies { api(libs.kotlinx.coroutines.android) }\n"; }],
    ["the server moved away", (t) => { delete t.sources["core-dav/src/main/java/dev/niccc2007/filet/webdav/WebDavServer.kt"]; }],
  ];
  let caught = 0;
  for (const [what, poison] of cases) {
    const t = JSON.parse(JSON.stringify(collect()));
    poison(t);
    if (problems(t).length > problems(good).length) caught++;
    else console.error(`  NOT CAUGHT: ${what}`);
  }
  if (caught !== cases.length) {
    console.error(`MODULES SELFTEST FAILED: ${cases.length - caught} of ${cases.length} not caught`);
    process.exit(1);
  }
  console.log(`MODULES SELFTEST OK  (${cases.length} negative controls, 1 positive)`);
} else {
  const found = problems(collect());
  if (found.length) {
    for (const p of found) console.error("FAIL: " + p);
    process.exit(1);
  }
  const counted = Object.keys(collect().sources).length;
  console.log(
    `MODULES OK  (:core-vfs behind its own module, :app depends on it, ` +
    `${counted} file(s) across :core-vfs-jvm and :core-dav with no platform imports)`,
  );
}
