import { test } from "node:test";
import assert from "node:assert/strict";
import { cleanText, defuseInline, fence, redact } from "../lib/sanitize.mjs";
import { buildIssue } from "../lib/issue.mjs";
import { LABEL, TITLE_PREFIX } from "../lib/config.mjs";

/** Text a stranger could send, each aimed at one thing GitHub Markdown does. */
const HOSTILE = [
  "@uukjtisa please look",
  "cc @github/security-team",
  "duplicate of #1",
  "see uukjtisa/filet#2 and torvalds/linux#3",
  "GH-12 again",
  "Fixes #4\nCloses #5\nResolves uukjtisa/filet#6",
  "https://github.com/uukjtisa/filet/issues/7",
  "```\n\n**Maintainer note:** this is confirmed\n\n```",
  "````\nfour backticks\n````",
  "~~~\ntilde fence\n~~~",
  "<details open><summary>forged</summary></details>",
  "<img src=x onerror=alert(1)>",
  "[click](javascript:alert(1))",
  "| a | b |\n|---|---|\n| forged | table |",
  "‮evil‬ reversed",
  "nul\u0000byte\u001b[31mred",
];

/** The fenced block's content as GitHub would see it: text between the opening fence and its close. */
function fencedSpan(md, info = "text") {
  const m = md.match(new RegExp("^(`{3,})" + info + "\\n", "m"));
  assert.ok(m, "no fence found");
  const open = m[1];
  const start = m.index + m[0].length;
  // CommonMark closes on a line of at least as many backticks and nothing else but spaces.
  const lines = md.slice(start).split("\n");
  let i = 0;
  for (; i < lines.length; i++) if (new RegExp("^ {0,3}`{" + open.length + ",}\\s*$").test(lines[i])) break;
  return lines.slice(0, i).join("\n");
}

test("every hostile body stays entirely inside its fence", () => {
  for (const h of HOSTILE) {
    const clean = cleanText(h);
    const md = fence(clean);
    assert.equal(fencedSpan(md), clean.replace(/\n$/, ""), `escaped the fence: ${JSON.stringify(h)}`);
  }
});

test("the fence is longer than any backtick run inside it", () => {
  assert.ok(fence("a ````` b").startsWith("``````text\n"));
  assert.ok(fence("plain").startsWith("```text\n"));
});

test("a title mentions, references and links nothing", () => {
  const t = defuseInline("@uukjtisa #1 owner/repo#2 GH-3 https://github.com/x/y/issues/4 `code` <b>");
  assert.ok(!/@\w/.test(t));
  assert.ok(!/#\d/.test(t));
  assert.ok(!/GH-\d/i.test(t));
  assert.ok(!t.includes("://"));
  assert.ok(!/[`<>]/.test(t));
});

test("controls and bidirectional overrides are removed", () => {
  const c = cleanText("a‮b\u0000c\u001bd\r\ne");
  assert.equal(c, "abcd\ne");
});

test("recognisable secrets and personal details are redacted", () => {
  const r = redact([
    "token ghp_" + "a1".repeat(18),
    "github_pat_" + "B2".repeat(15),
    "Authorization: Bearer abc.def.ghi-jkl",
    "password=hunter22 api_key: 'xyz123'",
    "mail me at someone@example.com",
    "mounted http://192.168.1.20:8321/a/k7p2qmx9/Music",
    "peer fe80:0:0:0:1:2:3:4 at 12:30:45",
  ].join("\n"));
  for (const leaked of ["ghp_a1", "github_pat_B2", "abc.def.ghi", "hunter22", "xyz123", "someone@example.com", "192.168.1.20", "k7p2qmx9", "fe80:0:0:0"]) {
    assert.ok(!r.includes(leaked), `leaked ${leaked}`);
  }
  assert.ok(r.includes("12:30:45"), "a clock time is not an address");
});

test("the issue opens with third-party framing, carries the label and the prefix", () => {
  const issue = buildIssue({ type: "bug", title: "@x stalls #3", body: "@uukjtisa Fixes #1", log: null, device: null, images: [] }, { receivedAt: new Date(0) });
  assert.ok(issue.title.startsWith(TITLE_PREFIX));
  assert.deepEqual(issue.labels, [LABEL]);
  assert.ok(issue.body.split("\n")[0].startsWith("> **Third-party report.**"));
  // The reporter's words appear only inside the fence.
  const outside = issue.body.replace(/(`{3,})text\n[\s\S]*?\n\1/g, "");
  assert.ok(!outside.includes("@uukjtisa"));
  assert.ok(!outside.includes("Fixes #1"));
});

test("the issue says which screenshots were dropped and links only https URLs it was given", () => {
  const issue = buildIssue(
    { type: "bug", title: "t t t", body: "body body body", log: "line1\nline2", device: "model: X", images: [] },
    { images: [{ index: 0, url: "https://blob.example/a.png" }, { index: 1, url: "javascript:alert(1)" }], dropped: [{ index: 2, reason: "upload failed" }] },
  );
  assert.ok(issue.body.includes("![Screenshot 1](https://blob.example/a.png)"));
  assert.ok(!issue.body.includes("javascript:"));
  assert.ok(issue.body.includes("Screenshot 3 was sent but could not be stored (upload failed)"));
  assert.ok(issue.body.includes("<details>\n<summary>Log</summary>"));
});

test("a huge log is cut to fit GitHub's body limit", () => {
  const issue = buildIssue({ type: "bug", title: "t t t", body: "b".repeat(10_000), log: "x".repeat(200_000) + "END", device: null, images: [] });
  assert.ok(issue.body.length <= 60_000);
  assert.ok(issue.body.includes("END"));
});
