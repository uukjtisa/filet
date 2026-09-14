#!/usr/bin/env node
/**
 * G2 - the applicationId in the build file and the one recorded in PLAN.md must agree, and the
 * manifest must never write it out by hand.
 *
 * The id is frozen after first release (PLAN.md section 7), so drift between the decision and
 * the code is the expensive kind of drift.
 *
 * The second half was added after a real failure. `AndroidManifest.xml` declared the bridge
 * permission as the literal `dev.niccc2007.filet.permission.BRIDGE` instead of
 * `${applicationId}.permission.BRIDGE`, so the debug and the release build declared the *same*
 * permission. Android refuses to install an app that redefines another app's permission, which
 * meant a release APK could not be installed on any phone that had a debug build on it - every
 * development phone, and anyone who builds from source. It surfaced as "Installation failed"
 * with a reason that reads like a corrupt download, and nothing in the build catches it: both
 * APKs are perfectly valid on their own.
 *
 *   node tools/check-appid.mjs              check
 *   node tools/check-appid.mjs --selftest   prove it fails on each known violation
 */
import { readFileSync } from "node:fs";

/**
 * Attributes whose value has to be unique per installed app.
 *
 * Anything here written as a literal is the bug above: correct in one build, a collision in
 * the other. `android:name` is deliberately NOT in this list - it names classes and platform
 * permissions, which are supposed to be literal.
 */
const MUST_BE_TEMPLATED = [
  "android:authorities",
  "android:readPermission",
  "android:writePermission",
];

function checkManifest(manifest, appId) {
  const problems = [];

  for (const attr of MUST_BE_TEMPLATED) {
    const re = new RegExp(`${attr}\\s*=\\s*"([^"]*)"`, "g");
    for (const m of manifest.matchAll(re)) {
      const value = m[1];
      // A platform permission is not ours and cannot collide.
      if (value.startsWith("android.permission.")) continue;
      if (value.includes(appId) && !value.includes("${applicationId}")) {
        problems.push(
          `${attr}="${value}" writes the application id out by hand; ` +
            `use \${applicationId} or the debug and release builds collide`,
        );
      }
    }
  }

  // A <permission> this app defines. The name must be ours AND must be templated, because a
  // literal one is declared identically by every build.
  const permRe = /<permission\b[^>]*?android:name\s*=\s*"([^"]+)"/gs;
  for (const m of manifest.matchAll(permRe)) {
    const value = m[1];
    if (value.startsWith("android.permission.")) continue;
    if (value.includes(appId) && !value.includes("${applicationId}")) {
      problems.push(
        `<permission android:name="${value}"> is a literal; a debug build declares the same ` +
          `name and Android refuses to install the second one`,
      );
    }
  }

  // And the matching <uses-permission> has to follow it, or the app cannot use its own.
  const usesRe = /<uses-permission\b[^>]*?android:name\s*=\s*"([^"]+)"/gs;
  for (const m of manifest.matchAll(usesRe)) {
    const value = m[1];
    if (value.startsWith("android.permission.")) continue;
    if (value.includes(appId) && !value.includes("${applicationId}")) {
      problems.push(
        `<uses-permission android:name="${value}"> is a literal, so a debug build asks for ` +
          `the release build's permission and is refused`,
      );
    }
  }

  return problems;
}

function checkPlan(gradle, plan) {
  const problems = [];
  const m = gradle.match(/applicationId\s*=\s*"([^"]+)"/);
  if (!m) {
    problems.push("no applicationId in app/build.gradle.kts");
    return { problems, appId: null };
  }
  const appId = m[1];

  const ids = [...plan.matchAll(/`([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){2,})`/g)].map((x) => x[1]);
  const recorded = ids.filter((i) => i.endsWith(".filet") && !i.endsWith(".debug"));
  if (recorded.length === 0) {
    problems.push("PLAN.md records no applicationId");
    return { problems, appId };
  }
  const mismatched = [...new Set(recorded.filter((i) => i !== appId))];
  if (mismatched.length) {
    problems.push(`build file says "${appId}" but PLAN.md also records: ${mismatched.join(", ")}`);
  }
  return { problems, appId, recorded };
}

if (process.argv.includes("--selftest")) {
  const appId = "dev.niccc2007.filet";
  const goodManifest = `
    <provider android:authorities="\${applicationId}.bridge"
              android:readPermission="\${applicationId}.permission.BRIDGE"
              android:writePermission="\${applicationId}.permission.BRIDGE" />
    <service android:permission="android.permission.BIND_JOB_SERVICE" />
    <permission android:name="\${applicationId}.permission.BRIDGE" />
    <uses-permission android:name="\${applicationId}.permission.BRIDGE" />
    <uses-permission android:name="android.permission.INTERNET" />
  `;
  // The positive control. Without it a regex that never matches would "pass" everything.
  const clean = checkManifest(goodManifest, appId);
  if (clean.length) {
    console.error("SELFTEST FAIL: the good manifest did not pass: " + clean.join("; "));
    process.exit(1);
  }

  const cases = [
    [
      "the bug as it actually shipped: a literal permission definition",
      goodManifest.replace(
        '<permission android:name="${applicationId}.permission.BRIDGE" />',
        '<permission android:name="dev.niccc2007.filet.permission.BRIDGE" />',
      ),
      /<permission .* is a literal/,
    ],
    [
      "a literal uses-permission",
      goodManifest.replace(
        '<uses-permission android:name="${applicationId}.permission.BRIDGE" />',
        '<uses-permission android:name="dev.niccc2007.filet.permission.BRIDGE" />',
      ),
      /<uses-permission .* is a literal/,
    ],
    [
      "a literal provider authority",
      goodManifest.replace(
        'android:authorities="${applicationId}.bridge"',
        'android:authorities="dev.niccc2007.filet.bridge"',
      ),
      /android:authorities=.* by hand/,
    ],
    [
      "a literal readPermission on the provider",
      goodManifest.replace(
        'android:readPermission="${applicationId}.permission.BRIDGE"',
        'android:readPermission="dev.niccc2007.filet.permission.BRIDGE"',
      ),
      /android:readPermission=.* by hand/,
    ],
  ];

  let failures = 0;
  for (const [name, manifest, expected] of cases) {
    const problems = checkManifest(manifest, appId);
    if (!problems.some((p) => expected.test(p))) {
      failures++;
      console.error(`SELFTEST FAIL: "${name}" not caught. Got: ${problems.join("; ") || "(nothing)"}`);
    }
  }

  // The PLAN half, both ways.
  const planProblems = checkPlan('applicationId = "dev.niccc2007.filet"', "`dev.someoneelse.filet`").problems;
  if (!planProblems.some((p) => /PLAN\.md also records/.test(p))) {
    failures++;
    console.error("SELFTEST FAIL: a PLAN.md/build mismatch was not caught");
  }

  if (failures) {
    console.error(`APPID SELFTEST: ${failures} case(s) not caught`);
    process.exit(1);
  }
  console.log(
    `APPID SELFTEST OK  (1 positive control, ${cases.length + 1} negative controls all caught)`,
  );
} else {
  const gradle = readFileSync("app/build.gradle.kts", "utf8");
  const plan = readFileSync("PLAN.md", "utf8");
  const manifest = readFileSync("app/src/main/AndroidManifest.xml", "utf8");

  const { problems, appId, recorded } = checkPlan(gradle, plan);
  if (appId) problems.push(...checkManifest(manifest, appId));

  if (problems.length) {
    console.error("APPID PROBLEMS: " + problems.length);
    for (const p of problems) console.error("  " + p);
    process.exit(1);
  }
  console.log(
    `APPID CONSISTENT  (${appId}, ${recorded.length} reference(s) in PLAN.md, ` +
      `manifest uses \${applicationId} everywhere it has to)`,
  );
}
