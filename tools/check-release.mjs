#!/usr/bin/env node
/**
 * There is a published release, the app can find it, and the README agrees.
 *
 * Nic asked twice for v0.1.0 to be published and the updater to work the way Trawl's does -
 * the README still said there was no release. Round 5 verified only the negative case - the updater correctly reporting that nothing was published - which is
 * exactly the kind of green tick that means nothing.
 *
 * This checks the three things that have to line up, because the failure modes are quiet ones:
 * a tag with no release, a release with no APK attached, or a release whose asset name the
 * updater's own selector skips. Each of those looks fine on the releases page and leaves
 * "Check for updates" doing nothing.
 *
 *   node tools/check-release.mjs              check the live release
 *   node tools/check-release.mjs --selftest   prove it fails on each way this breaks
 *
 * Needs the network, and asks GitHub anonymously - the same request the app makes, so a
 * release that is visible here is visible to a phone. Prints "RELEASE OK" only after every
 * assertion passes.
 */
import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";

const OWNER = "uukjtisa";
const REPO = "filet";
const API = `https://api.github.com/repos/${OWNER}/${REPO}/releases?per_page=20`;

/**
 * The asset the updater would pick, using the same rule `Updater.toRelease` uses.
 *
 * Kept as its own function rather than inlined because the point of the check is that these
 * two agree. If the app's rule changes, this is the line that has to change with it.
 */
export function updaterWouldPick(assets) {
  return (
    assets.find((a) => /\.apk$/i.test(a.name || "") && !/fdroid/i.test(a.name || "")) || null
  );
}

/** The version the build declares. Parsed from the script, not from a built APK. */
function declaredVersion(gradle) {
  const m = gradle.match(/versionName\s*=\s*"([^"]+)"/);
  return m ? m[1] : null;
}

/** `v0.1.0` and `0.1.0` are the same version; the tag may write it either way. */
function sameVersion(tag, versionName) {
  return String(tag).replace(/^v/i, "") === String(versionName);
}

function check({ releases, gradle, readme }) {
  const problems = [];

  const published = releases.filter((r) => !r.draft);
  if (published.length === 0) {
    problems.push("no published release: the updater has nothing to find");
    // Everything below is about a release, so stop rather than pile on.
    return problems;
  }

  const stable = published.filter((r) => !r.prerelease);
  if (stable.length === 0) {
    problems.push("every release is marked pre-release, so the default channel finds nothing");
  }

  const newest = (stable.length ? stable : published)[0];

  const asset = updaterWouldPick(newest.assets || []);
  if (!asset) {
    const have = (newest.assets || []).map((a) => a.name).join(", ") || "nothing";
    problems.push(
      `release ${newest.tag_name} has no asset the updater would pick ` +
        `(needs one ending .apk and not named *fdroid*; has: ${have})`,
    );
  } else if (!(asset.size > 1000000)) {
    // An APK this project's size is tens of megabytes. Anything tiny is a failed upload
    // that still shows up on the page as an attached file.
    problems.push(`${asset.name} is only ${asset.size} bytes; that is not a built APK`);
  }

  const declared = declaredVersion(gradle);
  if (!declared) {
    problems.push("could not read versionName out of app/build.gradle.kts");
  } else if (!sameVersion(newest.tag_name, declared)) {
    problems.push(
      `the newest release is ${newest.tag_name} but the build declares ${declared}: ` +
        `an install from that release would immediately be offered itself as an update, ` +
        `or would never see one`,
    );
  }

  // The claim the release was supposed to retire.
  if (/no release yet/i.test(readme)) {
    problems.push('the README still says "no release yet"');
  }
  if (!/releases/i.test(readme)) {
    problems.push("the README does not link Releases, so there is no way to find the APK");
  }

  return problems;
}

function fetchReleases() {
  // curl rather than fetch(): this has to run under whatever Node the box has, and sending the
  // same headers the app sends is the point of the exercise.
  const out = execFileSync(
    "curl",
    ["-sS", "-H", "User-Agent: filet-check-release", "-H", "Accept: application/vnd.github+json", API],
    { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 },
  );
  const parsed = JSON.parse(out);
  if (!Array.isArray(parsed)) {
    throw new Error(`GitHub answered with ${parsed.message || "something unexpected"}`);
  }
  return parsed;
}

const GRADLE = "app/build.gradle.kts";
const README = "README.md";

if (process.argv.includes("--selftest")) {
  const good = {
    releases: [
      {
        tag_name: "v0.1.0",
        draft: false,
        prerelease: false,
        assets: [{ name: "Filet-0.1.0.apk", size: 27401734 }],
      },
    ],
    gradle: 'versionName = "0.1.0"',
    readme: "Grab the APK from [Releases](https://github.com/uukjtisa/filet/releases/latest).",
  };
  // The positive control. A negative check is worthless without one.
  const clean = check(good);
  if (clean.length) {
    console.error("SELFTEST FAIL: the good case did not pass: " + clean.join("; "));
    process.exit(1);
  }

  const cases = [
    ["nothing published", { ...good, releases: [] }, /no published release/],
    [
      "a draft release, which is invisible to an anonymous request",
      { ...good, releases: [{ ...good.releases[0], draft: true }] },
      /no published release/,
    ],
    [
      "everything marked pre-release",
      { ...good, releases: [{ ...good.releases[0], prerelease: true }] },
      /pre-release/,
    ],
    [
      "the tag is there but the APK never uploaded",
      { ...good, releases: [{ ...good.releases[0], assets: [] }] },
      /no asset the updater would pick/,
    ],
    [
      "only the fdroid APK is attached, which the updater skips on purpose",
      {
        ...good,
        releases: [
          { ...good.releases[0], assets: [{ name: "Filet-0.1.0-fdroid.apk", size: 27000000 }] },
        ],
      },
      /no asset the updater would pick/,
    ],
    [
      "the upload failed and left a stub",
      {
        ...good,
        releases: [{ ...good.releases[0], assets: [{ name: "Filet-0.1.0.apk", size: 512 }] }],
      },
      /not a built APK/,
    ],
    [
      "the build moved on and the release did not",
      { ...good, gradle: 'versionName = "0.2.0"' },
      /but the build declares/,
    ],
    [
      "the README still says there is nothing to install",
      { ...good, readme: good.readme + "\nThere's no store listing and no release yet." },
      /no release yet/,
    ],
    [
      "the README stops pointing at Releases",
      { ...good, readme: "Build it yourself." },
      /does not link Releases/,
    ],
  ];

  let failures = 0;
  for (const [name, input, expected] of cases) {
    const problems = check(input);
    if (!problems.some((p) => expected.test(p))) {
      failures++;
      console.error(`SELFTEST FAIL: "${name}" not caught. Got: ${problems.join("; ") || "(nothing)"}`);
    }
  }
  if (failures) {
    console.error(`RELEASE SELFTEST: ${failures} case(s) not caught`);
    process.exit(1);
  }
  console.log(
    `RELEASE SELFTEST OK  (1 positive control, ${cases.length} negative controls all caught)`,
  );
} else {
  let releases;
  try {
    releases = fetchReleases();
  } catch (e) {
    console.error("RELEASE CHECK COULD NOT RUN: " + e.message);
    process.exit(2);
  }
  const problems = check({
    releases,
    gradle: readFileSync(GRADLE, "utf8"),
    readme: readFileSync(README, "utf8"),
  });
  if (problems.length) {
    console.error("RELEASE PROBLEMS: " + problems.length);
    for (const p of problems) console.error("  " + p);
    process.exit(1);
  }
  const newest = releases.filter((r) => !r.draft && !r.prerelease)[0];
  const asset = updaterWouldPick(newest.assets || []);
  console.log(
    `RELEASE OK  (${newest.tag_name}, ${asset.name}, ` +
      `${(asset.size / 1048576).toFixed(1)} MB, the updater's own selector finds it)`,
  );
}
