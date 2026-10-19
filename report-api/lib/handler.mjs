import { createHash } from "node:crypto";
import * as C from "./config.mjs";
import { hashSubject, hit, ipSubject, keysFor } from "./limiter.mjs";
import { Refusal, validate, INSTALL_ID } from "./validate.mjs";
import { buildIssue } from "./issue.mjs";
import { createIssue } from "./github.mjs";
import { storeAll } from "./blob.mjs";

/**
 * The request pipeline, with every dependency passed in so the whole of it runs under a test.
 *
 * The order is cheapest-refusal-first, and each step is placed where it is for a reason:
 *
 *  1. method, content type, configuration, declared length - no I/O at all;
 *  2. the IP limit, before the body is read, so a flooding address costs one counter each time;
 *  3. the body, read with a hard cap so a lying Content-Length cannot make it allocate more;
 *  4. the install limit, which needs the parsed body;
 *  5. validation, then the duplicate check, then the GLOBAL limit - counted only for reports that
 *     would actually be filed, so garbage cannot use it up and lock everybody out;
 *  6. screenshots, then the issue.
 *
 * `application/json` is required, not merely preferred. A browser can POST `text/plain` from any
 * website without asking, but a JSON content type forces a CORS preflight this endpoint never
 * answers - so no web page can make its visitors file reports.
 */
export function createHandler({ env, store, blob, fetchImpl = fetch, now = () => Date.now(), log = defaultLog }) {
  const secrets = [env.REPORT_GITHUB_TOKEN, env.KV_REST_API_TOKEN, env.UPSTASH_REDIS_REST_TOKEN, env.BLOB_READ_WRITE_TOKEN, env.REPORT_KEY_SALT]
    .filter((s) => typeof s === "string" && s.length >= 8);
  const respond = responder(secrets);
  const api = env.VERCEL_ENV !== "production" && env.REPORT_GITHUB_API ? env.REPORT_GITHUB_API.replace(/\/+$/, "") : "https://api.github.com";

  return async function handle(request, ip) {
    try {
      return await pipeline(request, ip);
    } catch (e) {
      if (e instanceof Refusal) return refusal(respond, e);
      // Anything unexpected: the class name only. A message can carry a URL, a header or a value.
      log("error", { kind: e?.constructor?.name ?? "unknown" });
      return respond(500, err("internal", "Something went wrong on the server. Try again later."));
    }
  };

  async function pipeline(request, ip) {
    if (request.method !== "POST") {
      return respond(405, err("method_not_allowed", "Reports are sent with POST."), { allow: "POST" });
    }
    const type = (request.headers.get("content-type") || "").toLowerCase();
    if (!type.startsWith("application/json")) {
      return respond(415, err("unsupported_media_type", "Reports are sent as application/json."));
    }
    if (!env.REPORT_GITHUB_TOKEN || !env.REPORT_KEY_SALT) {
      log("misconfigured", { token: !!env.REPORT_GITHUB_TOKEN, salt: !!env.REPORT_KEY_SALT });
      return respond(503, err("not_configured", "Reporting is not set up on the server yet."));
    }
    if (!store) {
      // Refused, not waved through. Without a shared store there is no rate limit, and an
      // unlimited endpoint holding a write token is exactly what this service must not be.
      log("misconfigured", { store: false });
      return respond(503, err("limiter_unavailable", "Reporting is temporarily unavailable.", { retryAfter: 300 }), { "retry-after": "300" });
    }
    const declared = Number(request.headers.get("content-length"));
    if (Number.isFinite(declared) && declared > C.MAX_REQUEST_BYTES) throw tooLarge();

    const t = now();
    await limit("ip", ipSubject(ip), C.RULES.ip, t);

    const raw = await readCapped(request, C.MAX_REQUEST_BYTES);
    let input;
    try {
      input = JSON.parse(raw);
    } catch {
      throw new Refusal(400, "bad_json", "The report is not valid JSON.");
    }

    // A retry of a draft that was already filed is answered from the record of that filing,
    // BEFORE the install limit: a retry after a lost response must not spend the budget, and must
    // never file a second issue.
    const subKey = submissionKey(input);
    if (subKey) {
      const replay = await answerFromRecord(subKey);
      if (replay) return replay;
    }

    // Before validation, so a client sending a stream of invalid reports is still metered. A
    // malformed id is left to validation to refuse.
    if (typeof input?.installId === "string" && INSTALL_ID.test(input.installId)) {
      await limit("install", input.installId, C.RULES.install, t);
    }

    const report = validate(input);

    // Dedupe on the draft's submission id when the app sends one: a retry of the same draft is
    // the case that matters, and two people sending the same words are two reports. The body
    // hash is the fallback for a client that sends no id.
    const ttl = Math.ceil(C.DUPLICATE_WINDOW_MS / 1000);
    const dupKey = subKey ??
      "dup:" + createHash("sha256").update(`${report.title}\u0000${report.body}`).digest("hex").slice(0, 32);
    if (!(await guard(() => store.claim(dupKey, ttl, PENDING)))) {
      if (subKey) {
        // Lost a race with a concurrent retry of the same draft.
        const replay = await answerFromRecord(subKey);
        if (replay) return replay;
      }
      throw new Refusal(409, "duplicate", "This report was already received.");
    }

    try {
      await limit("global", "all", C.RULES.global, t);
    } catch (e) {
      await guard(() => store.release(dupKey)).catch(() => {});
      throw e;
    }

    const { stored, dropped } = await storeAll(blob, report.images);
    const issue = buildIssue(report, { images: stored, dropped, receivedAt: new Date(t) });
    const made = await createIssue({ fetchImpl, api, repo: C.REPO, token: env.REPORT_GITHUB_TOKEN, issue });

    if (!made.ok) {
      // The report was not filed, so it must be possible to send it again.
      await guard(() => store.release(dupKey)).catch(() => {});
      log("github_failed", { status: made.status });
      if (made.status === 401 || made.status === 404) {
        return respond(503, err("not_configured", "Reporting is not set up correctly on the server."));
      }
      if (made.status === 403 || made.status === 429) {
        const retryAfter = made.retryAfter || 120;
        return respond(503, err("upstream_busy", "GitHub is busy. Try again shortly.", { retryAfter }), { "retry-after": String(retryAfter) });
      }
      return respond(502, err("upstream_failed", "GitHub did not accept the report. Try again later.", { retryAfter: 60 }), { "retry-after": "60" });
    }

    log("filed", { number: made.number, images: stored.length, dropped: dropped.length });
    const result = { issue: { number: made.number, url: made.url }, images: { stored: stored.length, dropped } };
    // Recorded so a retry is answered with THIS issue. A failure to record is logged and
    // swallowed: the issue exists, and refusing the reporter now would invite a second one.
    await store.set(dupKey, JSON.stringify(result), ttl).catch(() => log("record_failed", {}));
    return respond(201, { ok: true, ...result });
  }

  function submissionKey(input) {
    const sub = input?.submissionId;
    const inst = input?.installId;
    if (typeof sub !== "string" || !INSTALL_ID.test(sub)) return null;
    if (typeof inst !== "string" || !INSTALL_ID.test(inst)) return null;
    // Scoped to the install, so one client cannot replay or block another's draft by guessing.
    return "sub:" + hashSubject(env.REPORT_KEY_SALT, "submission", `${inst}:${sub}`);
  }

  /** The stored outcome for [key] as a response, a 409 while it is still being filed, or null. */
  async function answerFromRecord(key) {
    const v = await guard(() => store.get(key));
    if (v == null) return null;
    if (v === PENDING) {
      return respond(409, err("in_progress", "This report is still being sent.", { retryAfter: 5 }), { "retry-after": "5" });
    }
    try {
      return respond(200, { ok: true, replayed: true, ...JSON.parse(v) });
    } catch {
      return null;
    }
  }

  async function limit(scope, value, rules, t) {
    const subject = hashSubject(env.REPORT_KEY_SALT, scope, value);
    const verdict = await guard(() => hit(store, keysFor(scope, subject, rules, t), t));
    if (verdict) {
      throw new Refusal(429, "rate_limited", messageFor(verdict.scope), { scope: verdict.scope, retryAfter: verdict.retryAfter });
    }
  }

  /** A store failure is a 503 with a retry, never an unlimited pass. */
  async function guard(fn) {
    try {
      return await fn();
    } catch (e) {
      if (e instanceof Refusal) throw e;
      log("store_failed", {});
      throw new Refusal(503, "limiter_unavailable", "Reporting is temporarily unavailable.", { retryAfter: 60 });
    }
  }
}

const PENDING = "pending";

function messageFor(scope) {
  if (scope === "install") return "You have sent several reports recently. Try again later.";
  if (scope === "ip") return "Too many reports from this network recently. Try again later.";
  return "The report inbox is full for today. Try again tomorrow.";
}

const tooLarge = () =>
  new Refusal(413, "request_too_large", `The report is larger than ${(C.MAX_REQUEST_BYTES / 1_000_000).toFixed(1)} MB.`, { limit: C.MAX_REQUEST_BYTES });

/** The body as text, refusing as soon as it passes [max] rather than after buffering all of it. */
async function readCapped(request, max) {
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel().catch(() => {});
      throw tooLarge();
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString("utf8");
}

/** The error envelope every refusal uses. */
export function err(code, message, extra = {}) {
  return { ok: false, error: { code, message, ...extra } };
}

function refusal(respond, e) {
  const extra = {};
  for (const k of ["field", "index", "limit", "scope", "retryAfter"]) if (e[k] !== undefined) extra[k] = e[k];
  const headers = e.retryAfter ? { "retry-after": String(e.retryAfter) } : {};
  return respond(e.status, err(e.code, e.message, extra), headers);
}

/**
 * Every response goes through here, and here is the last place a secret could leak.
 *
 * No code path puts a secret into a response; this is the second line, for the bug nobody has
 * written yet. Any configured secret found in an outgoing body is replaced before it is sent.
 */
export function responder(secrets) {
  return (status, obj, headers = {}) => {
    let text = JSON.stringify(obj);
    for (const s of secrets) text = text.split(s).join("<redacted>");
    return new Response(text, {
      status,
      headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
    });
  };
}

/** One JSON line per event: an event name and counts or codes. Never a header, body or value. */
function defaultLog(event, fields) {
  console.log(JSON.stringify({ event, ...fields }));
}
