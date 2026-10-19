import * as C from "./config.mjs";
import { defuseInline, fence, keepHead } from "./sanitize.mjs";

/**
 * The issue as it is filed: title, body and labels from a validated report.
 *
 * Layout: one line of framing, then each reporter-supplied part fenced. The framing line comes
 * first and is the only prose in the body not inside a fence, so nobody reading the issue later
 * mistakes a reporter's words for the maintainer's - on a repository where most issues are the
 * maintainer's own notes, that confusion is the default without it.
 *
 * Screenshot links are built here from URLs this server received from its own storage, never from
 * request input, and are checked to be https before they are written.
 */
export function buildIssue(report, { images = [], dropped = [], receivedAt = new Date() } = {}) {
  const parts = [];
  parts.push(
    "> **Third-party report.** Filed anonymously from the Filet app through the reporting service. " +
      "Everything below this line was written by the reporter, not by the maintainer, and has not been verified.",
  );
  parts.push(`**Type:** ${C.TYPES[report.type]} · **Received:** ${receivedAt.toISOString().slice(0, 16).replace("T", " ")} UTC`);

  parts.push("### Description", fence(report.body));

  if (report.device) parts.push("### Device", fence(report.device));

  if (images.length || dropped.length) {
    const lines = ["### Screenshots"];
    images.forEach((im, i) => {
      if (/^https:\/\/[^\s()<>]+$/.test(im.url)) lines.push(`![Screenshot ${i + 1}](${im.url})`);
    });
    // Said in the issue, not only in the response: whoever triages it should know a picture was
    // meant to be there and was not, rather than wonder why "see screenshot" points at nothing.
    for (const d of dropped) lines.push(`_Screenshot ${d.index + 1} was sent but could not be stored (${d.reason})._`);
    parts.push(lines.join("\n\n"));
  }

  let body = parts.join("\n\n");
  if (report.log) {
    const room = C.ISSUE_BODY_MAX - body.length - 200;
    const log = room > 500 ? (report.log.length > room ? keepTailForIssue(report.log, room) : report.log) : null;
    if (log) body += `\n\n<details>\n<summary>Log</summary>\n\n${fence(log)}\n\n</details>`;
  }
  if (body.length > C.ISSUE_BODY_MAX) body = keepHead(body, C.ISSUE_BODY_MAX);

  return {
    title: C.TITLE_PREFIX + defuseInline(report.title),
    body,
    labels: [C.LABEL],
  };
}

function keepTailForIssue(log, room) {
  const cut = log.length - room;
  return `[... ${cut} earlier characters trimmed to fit the issue ...]\n` + log.slice(cut);
}
