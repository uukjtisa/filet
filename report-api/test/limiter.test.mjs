import { test } from "node:test";
import assert from "node:assert/strict";
import { evaluate, hashSubject, hit, ipSubject, keysFor, retryAfterSeconds, windowOf } from "../lib/limiter.mjs";
import { memoryStore, storeFromEnv } from "../lib/stores.mjs";
import { RULES } from "../lib/config.mjs";

const HOUR = 3_600_000;

test("a window starts on its boundary and ends one length later", () => {
  assert.deepEqual(windowOf(HOUR * 5 + 123, HOUR), { start: HOUR * 5, resetAt: HOUR * 6 });
  assert.deepEqual(windowOf(HOUR * 5, HOUR), { start: HOUR * 5, resetAt: HOUR * 6 });
});

test("retry-after rounds up and is never zero", () => {
  assert.equal(retryAfterSeconds(1000, 1001), 1);
  assert.equal(retryAfterSeconds(1000, 1000), 1);
  assert.equal(retryAfterSeconds(0, 59_001), 60);
});

test("keys change when the window does, and only then", () => {
  const a = keysFor("ip", "x", RULES.ip, HOUR * 2 + 10);
  const b = keysFor("ip", "x", RULES.ip, HOUR * 2 + 20);
  const c = keysFor("ip", "x", RULES.ip, HOUR * 3 + 10);
  assert.equal(a[0].key, b[0].key);
  assert.notEqual(a[0].key, c[0].key);
  assert.ok(a[0].ttl >= 3600);
});

test("evaluate passes at the limit and refuses one over it", () => {
  const e = keysFor("install", "x", RULES.install, 0);
  assert.equal(evaluate(e, [3, 3], 0), null);
  assert.equal(evaluate(e, [4, 4], 0).rule, "install-hour");
});

test("when two rules are exceeded the later reset wins", () => {
  const e = keysFor("install", "x", RULES.install, 0);
  const v = evaluate(e, [4, 11], 0);
  assert.equal(v.rule, "install-day");
  assert.equal(v.retryAfter, 86_400);
});

test("a fourth report in an hour from one install is refused, a new hour is allowed", async () => {
  const s = memoryStore(() => t);
  let t = HOUR * 10;
  for (let i = 0; i < 3; i++) assert.equal(await hit(s, keysFor("install", "a", RULES.install, t), t), null);
  const v = await hit(s, keysFor("install", "a", RULES.install, t), t);
  assert.equal(v.scope, "install");
  assert.equal(v.retryAfter, 3600);
  t += HOUR;
  assert.equal(await hit(s, keysFor("install", "a", RULES.install, t), t), null);
});

test("IPv6 is counted per /64 and IPv4-mapped addresses as IPv4", () => {
  assert.equal(ipSubject("2001:db8:1:2:aaaa::1"), ipSubject("2001:db8:1:2:ffff:1:2:3"));
  assert.notEqual(ipSubject("2001:db8:1:2::1"), ipSubject("2001:db8:1:3::1"));
  assert.equal(ipSubject("::ffff:10.0.0.7"), "10.0.0.7");
  assert.equal(ipSubject("192.168.1.4"), "192.168.1.4");
  assert.equal(ipSubject(""), "unknown");
});

test("subjects are hashed with the salt and never stored raw", () => {
  const h = hashSubject("salt-one", "ip", "10.0.0.7");
  assert.ok(!h.includes("10.0.0.7"));
  assert.notEqual(h, hashSubject("salt-two", "ip", "10.0.0.7"));
  assert.notEqual(h, hashSubject("salt-one", "install", "10.0.0.7"));
});

test("no store is configured unless Redis is, and memory is refused in production", () => {
  assert.equal(storeFromEnv({}), null);
  assert.equal(storeFromEnv({ REPORT_DEV_MEMORY_LIMITER: "1", VERCEL_ENV: "production" }), null);
  assert.equal(storeFromEnv({ REPORT_DEV_MEMORY_LIMITER: "1" }).kind, "memory");
  assert.equal(storeFromEnv({ KV_REST_API_URL: "https://x", KV_REST_API_TOKEN: "t" }).kind, "upstash");
});
