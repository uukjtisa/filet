/**
 * Making a stranger's text safe to land on a real GitHub repository.
 *
 * The destination is GitHub-flavoured Markdown, where text is not inert: `@name` notifies a
 * person, `#12` and `owner/repo#12` and a pasted issue URL create cross-references that appear on
 * other issues' timelines, and fences and HTML forge structure that reads as the maintainer's.
 *
 * Decision: **everything a reporter wrote goes inside a fenced code block**, and the only
 * reporter text outside one is the title. GitHub does not process mentions, references, closing
 * keywords, autolinks or HTML inside code blocks, so fencing neutralises all of them at once
 * instead of escaping each construct and missing the next one. Escaping alone is a list that is
 * never complete.
 *
 * The fence cannot be escaped from: it is one backtick longer than the longest run of backticks
 * anywhere in the text, and CommonMark only closes a fence on a run at least as long as the one
 * that opened it. A tilde fence inside cannot close a backtick fence at all.
 *
 * The title cannot be fenced, so it is defused instead: a zero-width space after every `@` and `#`
 * and inside `GH-` and `://`, which leaves it readable and matches no reference pattern.
 */

const BIDI = /[‪-‮⁦-⁩‎‏؜]/g;
// C0 and C1 controls except tab and newline. NUL and friends have no business in an issue, and a
// raw escape sequence would reach whoever views the issue in a terminal client.
const CTRL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/g;
const ZWSP = "​";

/**
 * Normalised text: line endings folded, controls and bidirectional overrides removed.
 *
 * The overrides are removed because they make text render in a different order from how it is
 * stored - a report can display one thing and say another.
 */
export function cleanText(s) {
  return String(s ?? "").replace(/\r\n?/g, "\n").replace(BIDI, "").replace(CTRL, "").normalize("NFC");
}

/** [cleanText] on one line: whitespace runs, including newlines, collapsed to one space. */
export function cleanLine(s) {
  return cleanText(s).replace(/\s+/g, " ").trim();
}

/** A title that references, mentions and links nothing. */
export function defuseInline(s) {
  return cleanLine(s)
    .replace(/@/g, "@" + ZWSP)
    .replace(/#/g, "#" + ZWSP)
    .replace(/\bGH-/gi, (m) => m.slice(0, 2) + ZWSP + "-")
    .replace(/:\/\//g, ":" + ZWSP + "//")
    // A title is rendered with inline Markdown; backticks and angle brackets are the two that can
    // change what it looks like, and neither is needed in a report title.
    .replace(/[`<>]/g, "'");
}

/** [text] inside a fenced block that nothing in [text] can close. */
export function fence(text, info = "text") {
  const body = String(text);
  const longest = (body.match(/`+/g) || []).reduce((n, r) => Math.max(n, r.length), 0);
  const f = "`".repeat(Math.max(3, longest + 1));
  return `${f}${info}\n${body}${body.endsWith("\n") ? "" : "\n"}${f}`;
}

/**
 * Secrets and personal details redacted from diagnostic text, where they can be recognised.
 *
 * "Where they can be recognised" is the honest limit: this catches the shapes that have a shape -
 * tokens, keys, bearer headers, key=value secrets, email addresses, IP addresses, and Filet's own
 * WebDAV access codes in `/a/<code>` URLs, which are passwords to a phone. A secret with no
 * recognisable form passes through, which is why the app tells the reporter the log is public.
 *
 * Order matters: tokens before the generic key=value rule, so a token is never half-redacted.
 */
const REDACTIONS = [
  [/\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\b/g, "<redacted-token>"],
  [/\bgithub_pat_[A-Za-z0-9_]{20,}\b/g, "<redacted-token>"],
  [/\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g, "<redacted-key>"],
  [/\bAIza[0-9A-Za-z_-]{35}\b/g, "<redacted-key>"],
  [/\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{8,}/gi, "$1 <redacted>"],
  [/\b(pass(?:word)?|passwd|pwd|secret|token|api[_-]?key|access[_-]?code|auth)(\s*[:=]\s*)("[^"]*"|'[^']*'|\S+)/gi, "$1$2<redacted>"],
  [/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, "<email>"],
  [/\/a\/[A-Za-z0-9]{4,}(?=[/\s"'?#]|$)/gm, "/a/<code>"],
  [/\b(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}\b/g, "<ip>"],
  // Three or more colons: a full IPv6 or a MAC address. Two would also match a clock time.
  [/\b(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}\b/g, "<ip>"],
];

export function redact(s) {
  let out = String(s ?? "");
  for (const [re, rep] of REDACTIONS) out = out.replace(re, rep);
  return out;
}

/** [s] cut to [max] characters keeping the END, with a marker saying how much went. */
export function keepTail(s, max) {
  if (s.length <= max) return s;
  const cut = s.length - max;
  return `[... ${cut} earlier characters trimmed ...]\n` + s.slice(cut);
}

/** [s] cut to [max] characters keeping the START. */
export function keepHead(s, max) {
  if (s.length <= max) return s;
  return s.slice(0, max) + `\n[... ${s.length - max} more characters trimmed ...]`;
}
