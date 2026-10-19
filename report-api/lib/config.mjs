/**
 * Every limit in one place, so the README, the tests and the handler cannot disagree.
 *
 * The numbers are sized for one app with a small user base, filing onto a repository one person
 * reads. They are deliberately tight: a report that is refused can be sent tomorrow, while a
 * flood of issues costs the maintainer an evening of closing them.
 */

/** Where reports are filed. Fixed in code, not read from the environment: a misconfigured
 *  variable must not be able to point a token with write access at a different repository. */
export const REPO = "uukjtisa/filet";

/** Every report carries this label and this title prefix, so app reports can be filtered from
 *  hand-filed issues in one click and cannot be mistaken for them in a list. */
export const LABEL = "app-report";
export const TITLE_PREFIX = "[app] ";

export const TYPES = Object.freeze({
  bug: "Bug",
  feature: "Feature request",
  question: "Question",
  other: "Other",
});

/**
 * The whole request, in bytes, as it arrives.
 *
 * Under Vercel's own 4.5 MB body ceiling on purpose. Above that the platform answers with its
 * own unstructured 413 before this code runs, and the app cannot tell a too-big report from an
 * outage. Below it, the refusal is ours and carries a reason the form can show.
 */
export const MAX_REQUEST_BYTES = 4_300_000;

/** Three screenshots of at most 1 MB decoded each: 4 MB of base64, which fits the cap above. */
export const MAX_IMAGES = 3;
export const MAX_IMAGE_BYTES = 1_000_000;

export const TITLE_MIN = 3;
export const TITLE_MAX = 120;
export const BODY_MIN = 10;
export const BODY_MAX = 10_000;

/** A log longer than this is trimmed from the FRONT: the end of a log is where the failure is. */
export const LOG_MAX = 30_000;
export const DEVICE_MAX = 2_000;
export const DEVICE_MAX_FIELDS = 16;

/** GitHub refuses an issue body over 65,536 characters. Kept clear of it with room to spare. */
export const ISSUE_BODY_MAX = 60_000;

/**
 * The rate limits.
 *
 * Two client keys, because neither is a limit alone. The install id is sent by the app and can
 * be forged by anyone who reads the APK, so it only meters honest clients. The IP is what a
 * client cannot choose, but a carrier NAT puts many phones behind one address, so it must be
 * looser than the install limit or one busy mobile network blocks itself.
 *
 *  - **install**: 3 an hour, 10 a day. Nobody files more than a handful of genuine reports in a
 *    sitting; past that it is a stuck retry loop or a script.
 *  - **ip**: 10 an hour, 30 a day. Room for several real reporters behind one NAT, still small
 *    enough that one address cannot flood the tracker.
 *  - **global**: 100 a day, counted only for reports that passed validation. The ceiling on what
 *    a distributed attacker can achieve with many IPs and forged ids: at most a hundred issues,
 *    which can be bulk-closed. Counting invalid requests here would let garbage lock everyone out.
 *
 * Fixed windows, so a client can land up to twice a limit across a window boundary. Accepted:
 * a sliding window costs a sorted set per key and the doubling is bounded and short.
 */
export const RULES = Object.freeze({
  install: [
    { name: "install-hour", windowMs: 3_600_000, limit: 3 },
    { name: "install-day", windowMs: 86_400_000, limit: 10 },
  ],
  ip: [
    { name: "ip-hour", windowMs: 3_600_000, limit: 10 },
    { name: "ip-day", windowMs: 86_400_000, limit: 30 },
  ],
  global: [{ name: "global-day", windowMs: 86_400_000, limit: 100 }],
});

/** The same title and text from anywhere within this window is refused as a duplicate. */
export const DUPLICATE_WINDOW_MS = 86_400_000;
