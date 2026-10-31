import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHandler } from "../lib/handler.mjs";
import { memoryStore } from "../lib/stores.mjs";
import { b64, jpeg } from "./fixtures.mjs";

/**
 * The app and this service, held to one contract.
 *
 * `contract/app-request.json` is EXACTLY what the Filet app sends for a fixed draft - the app's
 * own unit test (ReportContractTest) builds that request and fails if it differs by a byte. This
 * test sends the same bytes through the real handler. Between the two, the request shape cannot
 * drift on either side without a red test.
 *
 * It exists because it did drift, three ways at once, with both suites green: the app sent `log`
 * as an object (refused: "The log must be text"), read success from a top-level `url` the server
 * never sends, and allowed titles, pictures and logs the server refuses.
 */

const env = { REPORT_GITHUB_TOKEN: "fake-token-for-tests-0123456789", REPORT_KEY_SALT: "salt-for-tests-only" };
const APP_REQUEST = readFileSync(new URL("./contract/app-request.json", import.meta.url), "utf8");

function github() {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push(JSON.parse(init.body));
    return Response.json({ number: 7, html_url: "https://github.com/uukjtisa/filet/issues/7" }, { status: 201 });
  };
  return { calls, fetchImpl };
}

const post = (body) =>
  new Request("http://x/api/report", { method: "POST", headers: { "content-type": "application/json" }, body });

test("the app's request is accepted and filed", async () => {
  const g = github();
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: g.fetchImpl, log: () => {} });
  const res = await h(post(APP_REQUEST), "10.9.0.1");
  const body = await res.json();
  assert.equal(res.status, 201, JSON.stringify(body));
  // The shape the app reads: ReportForm.outcomeOf takes issue.url.
  assert.equal(body.ok, true);
  assert.match(body.issue.url, /^https:\/\/github\.com\//);
  const issue = g.calls[0];
  assert.ok(issue.title.startsWith("[app] Filet closes when I open About"));
  assert.ok(issue.labels.includes("app-report"));
  assert.ok(issue.body.includes("HUAWEI NCO-LX1"), "device details reach the issue");
  assert.ok(issue.body.includes("temp 41C"), "the snapshot reaches the issue, with the log");
  assert.equal(issue.body.split("HUAWEI NCO-LX1").length - 1, 1, "device details appear once, not twice");
});

test("the app's retry of the same draft is the same issue, which the app reads as filed", async () => {
  const g = github();
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: g.fetchImpl, log: () => {} });
  const first = await (await h(post(APP_REQUEST), "10.9.0.2")).json();
  const res = await h(post(APP_REQUEST), "10.9.0.2");
  const again = await res.json();
  assert.equal(res.status, 200);
  assert.equal(again.issue.url, first.issue.url);
  assert.equal(g.calls.length, 1, "filed once");
});

test("a picture the app would send is accepted", async () => {
  const g = github();
  const h = createHandler({ env, store: memoryStore(), blob: null, fetchImpl: g.fetchImpl, log: () => {} });
  const withImage = JSON.parse(APP_REQUEST);
  withImage.submissionId = "sub-00002";
  withImage.images = [{ name: "picture-0.jpg", mime: "image/jpeg", data: b64(jpeg()) }];
  const res = await h(post(JSON.stringify(withImage)), "10.9.0.3");
  assert.equal(res.status, 201, JSON.stringify(await res.json()));
});
