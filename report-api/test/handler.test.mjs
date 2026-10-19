import { test } from "node:test";
import assert from "node:assert/strict";
import { createHandler } from "../lib/handler.mjs";
import { memoryStore } from "../lib/stores.mjs";
import { b64, png } from "./fixtures.mjs";

const TOKEN = "fake-token-for-tests-" + "0123456789";
const env = { REPORT_GITHUB_TOKEN: TOKEN, REPORT_KEY_SALT: "salt-for-tests-only" };

/** A fake GitHub that records what it was sent and answers with [status]. */
function github(status = 201) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init, issue: JSON.parse(init.body) });
    if (status === 201) return Response.json({ number: calls.length, html_url: `https://github.com/uukjtisa/filet/issues/${calls.length}` }, { status: 201 });
    return new Response(JSON.stringify({ message: `echo ${init.headers.authorization}` }), { status, headers: { "retry-after": "30" } });
  };
  return { calls, fetchImpl };
}

let seq = 0;
const report = (extra = {}) => ({ installId: "install-000001", type: "bug", title: "Copy stalls " + ++seq, body: `It stops at 99 percent, run ${seq}.`, ...extra });
const req = (body, headers = {}) =>
  new Request("http://x/api/report", { method: "POST", headers: { "content-type": "application/json", ...headers }, body: typeof body === "string" ? body : JSON.stringify(body) });

async function json(res) {
  const text = await res.text();
  assert.ok(!text.includes(TOKEN), "token in a response body");
  return JSON.parse(text);
}

test("a valid report is filed on the fixed repository with the token in one header", async () => {
  const g = github();
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: g.fetchImpl, log: () => {} });
  const res = await h(req(report()), "10.0.0.1");
  const body = await json(res);
  assert.equal(res.status, 201);
  assert.equal(body.issue.number, 1);
  assert.equal(g.calls[0].url, "https://api.github.com/repos/uukjtisa/filet/issues");
  assert.equal(g.calls[0].init.headers.authorization, `Bearer ${TOKEN}`);
  assert.ok(!g.calls[0].init.body.includes(TOKEN), "token in the issue");
});

test("a failed upload drops the screenshot, not the report", async () => {
  const g = github();
  const blob = { put: async () => { throw new Error("boom"); } };
  const h = createHandler({ env, store: memoryStore(), blob, fetchImpl: g.fetchImpl, log: () => {} });
  const res = await h(req(report({ images: [{ mime: "image/png", data: b64(png()) }] })), "10.0.0.2");
  const body = await json(res);
  assert.equal(res.status, 201);
  assert.deepEqual(body.images, { stored: 0, dropped: [{ index: 0, reason: "upload failed" }] });
  assert.ok(g.calls[0].issue.body.includes("could not be stored"));
});

test("the fourth report in an hour from one install is refused with a retry-after", async () => {
  const g = github();
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: g.fetchImpl, log: () => {} });
  for (let i = 0; i < 3; i++) assert.equal((await h(req(report({ installId: "same-install-1" })), `10.1.0.${i}`)).status, 201);
  const res = await h(req(report({ installId: "same-install-1" })), "10.1.0.9");
  const body = await json(res);
  assert.equal(res.status, 429);
  assert.equal(body.error.code, "rate_limited");
  assert.equal(body.error.scope, "install");
  assert.ok(Number(res.headers.get("retry-after")) > 0);
});

test("one IP is limited however many install ids it forges", async () => {
  const g = github();
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: g.fetchImpl, log: () => {} });
  for (let i = 0; i < 10; i++) assert.equal((await h(req(report({ installId: `forged-${i}-xxxx` })), "10.2.0.1")).status, 201);
  const body = await json(await h(req(report({ installId: "forged-99-xxxx" })), "10.2.0.1"));
  assert.equal(body.error.scope, "ip");
});

test("the same report twice is a duplicate", async () => {
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: github().fetchImpl, log: () => {} });
  const r = report();
  assert.equal((await h(req(r), "10.3.0.1")).status, 201);
  assert.equal((await json(await h(req(r), "10.3.0.2"))).error.code, "duplicate");
});

test("no store means no filing, not an unlimited pass", async () => {
  const g = github();
  const h = createHandler({ env, store: null, blob: null, fetchImpl: g.fetchImpl, log: () => {} });
  const res = await h(req(report()), "10.4.0.1");
  assert.equal(res.status, 503);
  assert.equal((await json(res)).error.code, "limiter_unavailable");
  assert.equal(g.calls.length, 0);
});

test("method, content type, size and JSON are refused with structured reasons", async () => {
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: github().fetchImpl, log: () => {} });
  assert.equal((await json(await h(new Request("http://x/api/report"), "1.1.1.1"))).error.code, "method_not_allowed");
  assert.equal((await json(await h(req("{}", { "content-type": "text/plain" }), "1.1.1.1"))).error.code, "unsupported_media_type");
  assert.equal((await json(await h(req("x".repeat(4_400_000)), "1.1.1.2"))).error.code, "request_too_large");
  assert.equal((await json(await h(req("{nope"), "1.1.1.3"))).error.code, "bad_json");
});

test("GitHub's error body never reaches the client, and an echoed token is scrubbed anyway", async () => {
  for (const status of [401, 403, 500]) {
    const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: github(status).fetchImpl, log: () => {} });
    const res = await h(req(report()), "10.5.0.1");
    const text = await res.text();
    assert.ok(!text.includes(TOKEN));
    assert.ok(!text.includes("echo"));
    assert.ok([502, 503].includes(res.status));
  }
});

test("the responder scrubs a configured secret from any body", async () => {
  const { responder } = await import("../lib/handler.mjs");
  const res = responder([TOKEN])(500, { leaked: `x ${TOKEN} y` });
  assert.equal((await res.json()).leaked, "x <redacted> y");
});

test("logs carry no token", async () => {
  const lines = [];
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: github(401).fetchImpl, log: (e, f) => lines.push(JSON.stringify({ e, ...f })) });
  await h(req(report()), "10.6.0.1");
  assert.ok(lines.length > 0);
  for (const l of lines) assert.ok(!l.includes(TOKEN));
});

test("a retried draft is answered with the issue already filed, and files nothing new", async () => {
  const g = github();
  const store = memoryStore();
  const h = createHandler({ env, store, blob: null, fetchImpl: g.fetchImpl, log: () => {} });
  const draft = report({ installId: "retry-install-1", submissionId: "draft-0000001" });
  const first = await json(await h(req(draft), "10.7.0.1"));
  // Four retries: more than the hourly install limit, which a replay must not spend.
  for (let i = 0; i < 4; i++) {
    const res = await h(req(draft), "10.7.0.1");
    const body = await json(res);
    assert.equal(res.status, 200);
    assert.equal(body.replayed, true);
    assert.equal(body.issue.number, first.issue.number);
  }
  assert.equal(g.calls.length, 1);
});

test("two drafts with the same words are two reports when they carry different submission ids", async () => {
  const g = github();
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: g.fetchImpl, log: () => {} });
  const words = { title: "Same words here", body: "Exactly the same description text." };
  assert.equal((await h(req(report({ ...words, installId: "inst-aaaa-1", submissionId: "sub-aaaa-1" })), "10.8.1.1")).status, 201);
  assert.equal((await h(req(report({ ...words, installId: "inst-bbbb-1", submissionId: "sub-bbbb-1" })), "10.8.1.2")).status, 201);
  assert.equal(g.calls.length, 2);
});

test("a retry while the first attempt is still filing gets in_progress with a retry-after", async () => {
  let release;
  const gate = new Promise((r) => (release = r));
  const g = github();
  const slow = async (url, init) => { await gate; return g.fetchImpl(url, init); };
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: slow, log: () => {} });
  const draft = report({ installId: "slow-install-1", submissionId: "slow-draft-1" });
  const firstP = h(req(draft), "10.9.1.1");
  await new Promise((r) => setTimeout(r, 20));
  const res = await h(req(draft), "10.9.1.1");
  assert.equal(res.status, 409);
  assert.equal((await json(res)).error.code, "in_progress");
  assert.equal(res.headers.get("retry-after"), "5");
  release();
  assert.equal((await firstP).status, 201);
});

test("a failed filing frees the draft so the retry files it", async () => {
  let fail = true;
  const g = github();
  const flaky = async (url, init) => (fail ? new Response("{}", { status: 500 }) : g.fetchImpl(url, init));
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: flaky, log: () => {} });
  const draft = report({ installId: "flaky-install-1", submissionId: "flaky-draft-1" });
  assert.equal((await h(req(draft), "10.9.2.1")).status, 502);
  fail = false;
  assert.equal((await h(req(draft), "10.9.2.1")).status, 201);
});

test("a malformed submission id is refused, not silently ignored", async () => {
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: github().fetchImpl, log: () => {} });
  const body = await json(await h(req(report({ submissionId: "x" })), "10.9.3.1"));
  assert.equal(body.error.field, "submissionId");
});
