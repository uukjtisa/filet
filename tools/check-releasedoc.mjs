#!/usr/bin/env node
/**
 * The published release body is written the way `docs/RELEASES.md` says it has to be.
 *
 * The design calls for the rule Trawl keeps in its agent instructions - open with pictures, say what
 * changed and what it means, never talk about the code - and asked for it to be the standard
 * for the next app too. A rule that lives only in a prompt lasts until the next session, so it
 * lives in `docs/RELEASES.md` and this checks the release against it.
 *
 * This is deliberately a blunt instrument and the limit is worth stating plainly: it can see
 * whether there are images, whether there are sections, and whether the prose has slipped into
 * naming source files. It cannot see whether the writing is any good. A green run means the
 * shape is right, not that the notes are.
 *
 *   node tools/check-releasedoc.mjs              check the published release
 *   node tools/check-releasedoc.mjs --selftest   prove it fails on a bare changelog
 *
 * Prints "RELEASEDOC OK" only after every assertion passes.
 */
import { existsSync, readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";

const OWNER = "uukjtisa";
const REPO = "filet";
const API = `https://api.github.com/repos/${OWNER}/${REPO}/releases?per_page=20`;
const DOC = "docs/RELEASES.md";

/**
 * Things that mean the notes are describing the patch rather than the product.
 *
 * A bare `.kt` is the giveaway. Prose almost never contains one; a paste from a commit message
 * almost always does.
 */
const CODE_TALK = [
  [/\b[A-Za-z0-9_]+\.kt\b/, "names a source file"],
  [/\b[A-Za-z0-9_]+\.kt:\d+/, "cites a line number"],
  [/\bapp\/src\/main\//, "quotes a repository path"],
  [/^\s*(diff --git|\+\+\+ |--- )/m, "contains a diff"],
  [/\bcommit [0-9a-f]{7,40}\b/i, "cites a commit hash"],
  [/\brefactor(ed|ing)?\b/i, "describes the refactor rather than the effect"],
];

/**
 * Whether the opener rule covers this release.
 *
 * Versions are compared numerically rather than as strings, because "0.1.9" sorts after
 * "0.1.13" as text and that is exactly the comparison a release-numbering check must not get
 * wrong. An untagged or unparseable tag is treated as covered: a new release is the common
 * case, and the failure direction to prefer is flagging something to look at.
 */
const OPENER_RULE_FROM = [0, 1, 13];

export function openerRuleApplies(tag) {
  const m = /(\d+)\.(\d+)(?:\.(\d+))?/.exec(tag || "");
  if (!m) return true;
  const v = [Number(m[1]), Number(m[2]), Number(m[3] || 0)];
  for (let i = 0; i < 3; i++) {
    if (v[i] !== OPENER_RULE_FROM[i]) return v[i] > OPENER_RULE_FROM[i];
  }
  return true;
}

/** Rough word count, for "is there any prose at all". */
function words(text) {
  return text.split(/\s+/).filter(Boolean).length;
}

export function checkBody(body, doc, tag = "") {
  const problems = [];

  if (!doc || !/names the best thing in the release/i.test(doc)) {
    problems.push(`${DOC} is missing or no longer states the rule`);
  }

  if (!body || body.trim().length === 0) {
    problems.push("the release has no body at all");
    return problems;
  }

  // Pictures. Either HTML <img> (what a centred div uses) or markdown's own.
  const images = [...body.matchAll(/<img\s[^>]*src\s*=\s*"([^"]+)"/gi)].map((m) => m[1]);
  const mdImages = [...body.matchAll(/!\[[^\]]*]\(([^)\s]+)/g)].map((m) => m[1]);
  const all = images.concat(mdImages);
  // Pictures are optional, and a release with none says nothing about it.
  //
  // This rule has now been wrong in both directions and the second mistake was mine. It began
  // as "always open with a screenshot", which padded releases with shots of screens that had
  // not changed - the stale gallery the README already had. The amendment made pictures
  // optional but demanded the absence be DECLARED up front, "no new screens", on the grounds
  // that a stated claim beats a silence.
  //
  // That reasoning does not survive asking who reads the notes. Somebody opening them has
  // already decided to care about the update, and the first thing they read being a disclaimer
  // about what the release does not have frames it as thin before they reach a single real
  // change. The absence of screenshots is a fact about the pictures; it is not news, and the
  // honest treatment is no sentence at all.
  //
  // So nothing is required here, and the opener is checked below instead - for what it must NOT
  // be.
  // Applied from 0.1.13 onward: that is the release the rule changed at, and the ones already
  // published carry the opener the OLD rule demanded. Failing the check on history would mean
  // either a permanently red check or editing release bodies people have already read.
  const OPENERS = [
    /no new screens/i,
    /nothing visual/i,
    /no (?:new )?(?:pictures|screenshots|images)/i,
    /^\s*(?:this one is|this release is) (?:mostly|just|only|all)\b/i,
    /^\s*(?:just|only|mostly) (?:fixes|bug ?fixes|internal)/i,
  ];
  // The first paragraph, which is the only place an opener can do this damage.
  const opener = body.trim().split(/\n\s*\n/)[0] ?? "";
  for (const pattern of (openerRuleApplies(tag) ? OPENERS : [])) {
    if (pattern.test(opener)) {
      problems.push(
        `the body opens by naming what the release LACKS (matched ${pattern}): ` +
          "open on the best thing in it instead",
      );
    }
  }
  for (const src of all) {
    if (!/^https?:\/\//i.test(src)) {
      problems.push(`image "${src}" is a relative path; a release body is rendered outside the repo`);
    } else if (/raw\.githubusercontent\.com/.test(src) && /\/(main|master)\//.test(src)) {
      // A body pinned to a branch silently changes meaning the next time the screenshots are
      // refreshed, and the app re-renders old releases.
      problems.push(`image "${src}" is pinned to a branch rather than to the tag`);
    }
  }

  // The support line, at the foot of every release from 0.1.13 on.
  //
  // A release page is where somebody has already decided to install the thing, which makes it
  // the one place the question is fair - and unlike the foot of a long README it is a page
  // people actually land on. Checked for the address rather than for wording, so the sentence
  // can be rewritten without the check going stale.
  if (openerRuleApplies(tag) && !/ko-fi\.com\//i.test(body)) {
    problems.push("the body has no support link: every release ends with one, see docs/RELEASES.md");
  }

  // Sections, so it is not one undifferentiated block.
  const headings = [...body.matchAll(/^#{1,6}\s+\S/gm)];
  if (headings.length < 2) {
    problems.push(`only ${headings.length} heading(s): the body should be sectioned`);
  }

  // Enough prose to have said what anything means.
  if (words(body) < 120) {
    problems.push(`only ${words(body)} words: too short to say what changed and what it means`);
  }

  // Some list of specifics rather than a single sentence.
  const bullets = [...body.matchAll(/^\s*[-*+]\s+\S/gm)];
  if (bullets.length < 3) {
    problems.push(`only ${bullets.length} bullet(s): if it fixes six things, all six are listed`);
  }

  // And it must not be talking about the code.
  for (const [pattern, why] of CODE_TALK) {
    const m = body.match(pattern);
    if (m) {
      problems.push(`the body ${why} ("${String(m[0]).slice(0, 40)}"): notes describe the product`);
    }
  }

  // Somebody has to be told how to install a sideloaded APK.
  if (!/install/i.test(body)) {
    problems.push("the body never mentions installing; a sideloaded APK needs that said");
  }

  return problems;
}

function fetchNewest() {
  const out = execFileSync(
    "curl",
    ["-sS", "-H", "User-Agent: filet-check-releasedoc", "-H", "Accept: application/vnd.github+json", API],
    { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 },
  );
  const parsed = JSON.parse(out);
  if (!Array.isArray(parsed)) {
    throw new Error(`GitHub answered with ${parsed.message || "something unexpected"}`);
  }
  const published = parsed.filter((r) => !r.draft);
  if (published.length === 0) throw new Error("nothing published to check");
  return (published.filter((r) => !r.prerelease)[0] || published[0]);
}

if (process.argv.includes("--selftest")) {
  const doc = "The first line names the best thing in the release. Do not be detailed about the code.";
  const good = [
    '<div align="center">',
    '<img src="https://raw.githubusercontent.com/uukjtisa/filet/v0.1.0/docs/screenshots/01.png" alt="Browsing">',
    "</div>",
    "",
    "This release is about archives and the things around them, which is most of what a file",
    "manager on a phone is actually for when the files did not come from the phone.",
    "",
    "### What is new",
    "",
    "- **Archives.** Reads zip, tar and 7z, and creates them. Long jobs show progress and can",
    "  be cancelled without leaving half an archive behind.",
    "- **Search inside archives.** A name inside a zip is found the same way a name on disk is.",
    "- **Tab reordering.** Drag a tab to move it, which was not possible before.",
    "",
    "### Fixed",
    "",
    "- **A bookmarked presentation opened as a folder.** It opens in the viewer now.",
    "- **The video scrubber jumped to the nearest keyframe**, so dragging to 12.5 seconds landed",
    "  at 10. It lands where you put it.",
    "",
    "### Installing",
    "",
    "One APK, Android 8.0 or newer. You will need to allow installing from unknown sources.",
    "",
    "Free and open source always. https://ko-fi.com/thethirdy2007 if it is useful to you.",
  ].join("\n");

  // The positive control. Without it, a checker whose regexes never match "passes" everything.
  const COVERED = "v0.1.13";
  const clean = checkBody(good, doc, COVERED);
  if (clean.length) {
    console.error("SELFTEST FAIL: the good body did not pass: " + clean.join("; "));
    process.exit(1);
  }

  const cases = [
    ["no body at all", "", doc, /no body at all/],
    // Pictures are no longer required, so a body without them is not a finding. What IS a
    // finding is an opener that leads on the absence.
    [
      "an opener announcing there are no new screens",
      ["No new screens for this one, but plenty underneath.", "", good].join("\n"),
      doc,
      /naming what the release LACKS/,
    ],
    [
      "an opener apologising for being internal",
      ["This one is mostly internal plumbing.", "", good].join("\n"),
      doc,
      /naming what the release LACKS/,
    ],
    [
      "an opener calling itself just fixes",
      ["Just fixes this time.", "", good].join("\n"),
      doc,
      /naming what the release LACKS/,
    ],
    [
      "images pinned to a branch instead of the tag",
      good.replace("/v0.1.0/", "/main/"),
      doc,
      /pinned to a branch/,
    ],
    [
      "a relative image path",
      good.replace(/https:\/\/raw\.githubusercontent\.com\/uukjtisa\/filet\/v0\.1\.0\//, ""),
      doc,
      /relative path/,
    ],
    ["one undifferentiated block", good.replace(/^### .*$/gm, ""), doc, /heading/],
    ["a one-liner", "Fixed some bugs.", doc, /too short|bullet|heading/],
    [
      "notes that talk about the code",
      good + "\n\nRefactored Bookmarks.kt to fix this.",
      doc,
      /names a source file|describes the refactor/,
    ],
    [
      "a diff pasted in",
      good + "\n\ndiff --git a/x b/x\n",
      doc,
      /contains a diff/,
    ],
    [
      "no word about installing",
      good.replace(/### Installing[\s\S]*$/, "### Notes\n\n- **Something.** And a second line so\n  the word count holds up here.\n"),
      doc,
      /never mentions installing/,
    ],
    [
      "a release that forgets the support line",
      good.replace("Free and open source always. https://ko-fi.com/thethirdy2007 if it is useful to you.", ""),
      doc,
      /no support link/,
    ],
    ["the rule document itself going missing", good, "", /RELEASES\.md/],
  ];

  // The floor, asserted on its own because "this produces NO finding" is the opposite shape
  // from every other case here. A release published before the rule changed keeps the opener
  // the old rule demanded, and flagging it would mean a permanently red check or editing
  // bodies people have already read.
  const historic = checkBody(
    ["No new screens for this one, but plenty underneath.", "", good].join("\n"),
    doc,
    "v0.1.12",
  );
  if (historic.some((p) => /LACKS/.test(p))) {
    console.error("SELFTEST FAIL: the opener rule was applied to a release that predates it");
    process.exit(1);
  }
  if (!openerRuleApplies("v0.1.13") || !openerRuleApplies("v0.2.0") ||
      openerRuleApplies("v0.1.12") || openerRuleApplies("v0.1.9")) {
    console.error("SELFTEST FAIL: the version floor compares versions as text, not as numbers");
    process.exit(1);
  }

  let failures = 0;
  for (const [name, body, d, expected, tag] of cases) {
    const problems = checkBody(body, d, tag ?? COVERED);
    if (!problems.some((p) => expected.test(p))) {
      failures++;
      console.error(`SELFTEST FAIL: "${name}" not caught. Got: ${problems.join("; ") || "(nothing)"}`);
    }
  }
  if (failures) {
    console.error(`RELEASEDOC SELFTEST: ${failures} case(s) not caught`);
    process.exit(1);
  }
  console.log(
    `RELEASEDOC SELFTEST OK  (1 positive control, ${cases.length} negative controls all caught)`,
  );
} else {
  const doc = existsSync(DOC) ? readFileSync(DOC, "utf8") : "";
  let release;
  try {
    release = fetchNewest();
  } catch (e) {
    console.error("RELEASEDOC CHECK COULD NOT RUN: " + e.message);
    process.exit(2);
  }
  const problems = checkBody(release.body || "", doc, release.tag_name);
  if (problems.length) {
    console.error(`RELEASEDOC PROBLEMS in ${release.tag_name}: ` + problems.length);
    for (const p of problems) console.error("  " + p);
    process.exit(1);
  }
  const imageCount = (release.body.match(/<img\s/gi) || []).length;
  console.log(
    `RELEASEDOC OK  (${release.tag_name}, ${imageCount} image(s), ` +
      `${words(release.body)} words, and it never names a source file)`,
  );
}
