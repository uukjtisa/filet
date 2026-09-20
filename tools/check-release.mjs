#!/usr/bin/env node
/**
 * There is a published release, the app can find it, and the README agrees.
 *
 * v0.1.0 had to be published with the updater working the way Trawl's does -
 * the README still said there was no release. Round 5 verified only the negative case - the updater correctly reporting that nothing was published - which is
 * exactly the kind of green tick that means nothing.
 *
 * This checks the things that have to line up, because the failure modes are quiet ones:
 * a tag with no release, a release with no APK attached, or a release whose asset name the
 * updater's own selector skips. Each of those looks fine on the releases page and leaves
 * "Check for updates" doing nothing.
 *
 * ## The version rule, and the one case it must NOT fail on
 *
 * The build's `versionName` and the newest release have to agree, or an install is either
 * offered itself as an update forever or never sees one at all.
 *
 * But a release is made in two steps: the bump is committed and pushed, and the release is
 * published after the signed APK finishes building. In between, `main` legitimately declares a
 * version that has no release yet - and the first version of this check failed the push, which
 * is a red build for a reason that is not a defect. A gate that cries wolf on the normal path
 * is a gate people learn to ignore.
 *
 * So the ahead case is split by whether the TAG exists, which is the thing that actually says
 * "this release was started":
 *
 * | state | verdict |
 * |---|---|
 * | build == newest release | fine |
 * | build ahead, no tag pushed | **in flight** - reported, not failed |
 * | build ahead, tag pushed, no release | **fail** - a tag with no release behind it |
 * | build behind the newest release | **fail** - people are installing something newer than main |
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
const TAGS = `https://api.github.com/repos/${OWNER}/${REPO}/tags?per_page=50`;

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

/**
 * Compare two dotted versions numerically. -1, 0 or 1.
 *
 * String comparison is wrong here and quietly so: "0.1.10" sorts before "0.1.9", which would
 * call a real regression an in-flight bump on exactly the release where it matters.
 */
export function compareVersions(a, b) {
  const pa = String(a).replace(/^v/i, "").split(".").map((n) => parseInt(n, 10) || 0);
  const pb = String(b).replace(/^v/i, "").split(".").map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] || 0;
    const y = pb[i] || 0;
    if (x !== y) return x < y ? -1 : 1;
  }
  return 0;
}

function check({ releases, gradle, readme, tags = [] }, inFlight = []) {
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
    const order = compareVersions(declared, newest.tag_name);
    const tagged = tags.some((t) => sameVersion(t, declared));
    // Does the version this tree declares have a release of its OWN?
    //
    // This is the case the rule originally missed, and it is the ordinary one: a commit from
    // before the last bump declares a version that shipped, and is only "behind" because
    // something newer shipped afterwards. That is not a fault in that commit - it is what
    // every commit in the history looks like the moment a release goes out - and treating it
    // as one means re-running any older run can never come back green.
    const ownRelease = published.some((r) => sameVersion(r.tag_name, declared));
    if (ownRelease) {
      // Nothing to say. This tree built a version that was published; whether something newer
      // exists is a fact about the repository, not about this commit.
    } else if (order < 0) {
      // The dangerous direction. Whatever people are installing is newer than what this
      // source tree builds, so nothing here describes what they are running.
      problems.push(
        `the newest release is ${newest.tag_name} but the build declares ${declared}, which is ` +
          `OLDER: the published APK is ahead of main, so an install would be offered a ` +
          `downgrade or nothing at all`,
      );
    } else if (tagged) {
      // The half-finished release. The tag says somebody started; the missing release says
      // they stopped, and the updater has no idea the version exists.
      problems.push(
        `v${declared} is tagged but has no published release: a tag with nothing behind it is ` +
          `invisible to the updater, and the newest thing a phone can find is still ` +
          `${newest.tag_name}`,
      );
    } else {
      // In flight. The bump is pushed, the signed APK is still building, the release is not
      // cut yet. Reported so it cannot be mistaken for a clean state, but not a failure -
      // failing here makes every bump commit red on the normal path.
      inFlight.push(
        `the build declares ${declared} and the newest release is ${newest.tag_name}, with no ` +
          `v${declared} tag yet — a release in flight, not a fault`,
      );
    }
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

function fetchJson(url) {
  // curl rather than fetch(): this has to run under whatever Node the box has, and sending the
  // same headers the app sends is the point of the exercise.
  const out = execFileSync(
    "curl",
    ["-sS", "-H", "User-Agent: filet-check-release", "-H", "Accept: application/vnd.github+json", url],
    { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 },
  );
  const parsed = JSON.parse(out);
  if (!Array.isArray(parsed)) {
    throw new Error(`GitHub answered with ${parsed.message || "something unexpected"}`);
  }
  return parsed;
}

function fetchTags() {
  return fetchJson(TAGS).map((t) => t.name);
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

  // A second positive control: an older commit, whose own version shipped, while something
  // newer has shipped since. This is what every commit in the history looks like the moment a
  // release goes out, and treating it as a fault made re-running any older run permanently
  // impossible - the check would report the commit as behind for ever.
  const older = {
    ...good,
    releases: [
      good.releases[0],
      {
        tag_name: "v0.2.0",
        draft: false,
        prerelease: false,
        assets: [{ name: "Filet-0.2.0.apk", size: 27401734 }],
      },
    ],
    tags: ["v0.1.0", "v0.2.0"],
  };
  const olderProblems = check(older);
  if (olderProblems.length) {
    console.error(
      "SELFTEST FAIL: a commit whose own version shipped was reported as a fault: " +
        olderProblems.join("; "),
    );
    process.exit(1);
  }

  const cases = [
    ["nothing published", { ...good, releases: [] }, /no published release/],
    // Not a negative control - it asserts the OPPOSITE, and it is here because the rule it
    // guards was added after older runs turned out to be impossible to re-run green.
    // Handled just below as a dedicated positive case.
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
      "a tag pushed with no release behind it",
      { ...good, gradle: 'versionName = "0.2.0"', tags: ["v0.2.0", "v0.1.0"] },
      /tagged but has no published release/,
    ],
    [
      "the published release is NEWER than the source tree",
      {
        ...good,
        releases: [{ ...good.releases[0], tag_name: "v0.3.0" }],
        gradle: 'versionName = "0.2.0"',
      },
      /which is\s+OLDER|is\s+OLDER/,
    ],
    [
      "a two-digit patch is compared as a number, not as text",
      {
        ...good,
        releases: [{ ...good.releases[0], tag_name: "v0.1.10" }],
        gradle: 'versionName = "0.1.9"',
      },
      // 0.1.9 IS older than 0.1.10, however the two strings sort.
      /is\s+OLDER/,
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

  // The second positive control, and the reason this checker was changed: a bump that is pushed
  // but not yet tagged is the normal middle of a release, and failing it makes every release
  // commit red for no defect. It must pass AND be reported, because a silent pass here would
  // hide a bump that was never finished.
  {
    const noted = [];
    const problems = check({ ...good, gradle: 'versionName = "0.2.0"', tags: ["v0.1.0"] }, noted);
    if (problems.length) {
      failures++;
      console.error("SELFTEST FAIL: an untagged bump was treated as a fault: " + problems.join("; "));
    }
    if (!noted.some((n) => /in flight/.test(n))) {
      failures++;
      console.error("SELFTEST FAIL: an untagged bump passed silently instead of being reported");
    }
  }
  if (failures) {
    console.error(`RELEASE SELFTEST: ${failures} case(s) not caught`);
    process.exit(1);
  }
  console.log(
    `RELEASE SELFTEST OK  (2 positive controls, ${cases.length} negative controls all caught)`,
  );
} else {
  let releases;
  let tags;
  try {
    releases = fetchReleases();
    tags = fetchTags();
  } catch (e) {
    console.error("RELEASE CHECK COULD NOT RUN: " + e.message);
    process.exit(2);
  }
  const inFlight = [];
  const problems = check(
    {
      releases,
      tags,
      gradle: readFileSync(GRADLE, "utf8"),
      readme: readFileSync(README, "utf8"),
    },
    inFlight,
  );
  // Printed before the verdict, and on stdout, so a release that is half-made is visible in a
  // green run rather than only in a red one.
  for (const note of inFlight) console.log("in flight: " + note);
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
