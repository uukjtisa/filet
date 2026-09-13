#!/usr/bin/env node
/** G4 — :core-vfs exists as its own module and :app depends on it. */
import { readFileSync, existsSync } from "node:fs";

const fail = (m) => { console.error("FAIL: " + m); process.exit(1); };

const settings = readFileSync("settings.gradle.kts", "utf8");
if (!/include\(":core-vfs"\)/.test(settings)) fail("settings.gradle.kts does not include :core-vfs");
if (!existsSync("core-vfs/build.gradle.kts")) fail("core-vfs/build.gradle.kts missing");

const app = readFileSync("app/build.gradle.kts", "utf8");
if (!/project\(":core-vfs"\)/.test(app)) fail(":app does not depend on :core-vfs");

// the boundary only means something if the provider actually lives behind it
for (const f of [
  "core-vfs/src/main/java/dev/niccc2007/filet/vfs/FileSystemProvider.kt",
  "core-vfs/src/main/java/dev/niccc2007/filet/vfs/Vfs.kt",
  "core-vfs/src/main/java/dev/niccc2007/filet/vfs/provider/LocalProvider.kt",
]) if (!existsSync(f)) fail(`missing ${f}`);

console.log("MODULES OK  (:core-vfs included, :app depends on it, provider core present)");
