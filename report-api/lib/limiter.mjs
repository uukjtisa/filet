import { createHash } from "node:crypto";

/**
 * Rate limiting: the window arithmetic is pure, the counting is a store.
 *
 * The store is NOT a module-level object. A serverless function's memory belongs to one instance,
 * which is created and destroyed at the platform's convenience - a counter there resets exactly
 * when traffic spikes and new instances start, which is when it is needed. So the counts live in
 * Redis (see stores.mjs), and with no store configured the handler refuses to file anything
 * rather than run unlimited.
 */

/** The fixed window [now] falls in, and when it ends. */
export function windowOf(now, windowMs) {
  const start = Math.floor(now / windowMs) * windowMs;
  return { start, resetAt: start + windowMs };
}

/** Whole seconds until [resetAt], never less than 1 - a Retry-After of 0 invites a hot loop. */
export function retryAfterSeconds(now, resetAt) {
  return Math.max(1, Math.ceil((resetAt - now) / 1000));
}

/** The store keys for one subject under [rules] at [now], with their TTLs in seconds. */
export function keysFor(scope, subject, rules, now) {
  return rules.map((r) => {
    const w = windowOf(now, r.windowMs);
    return {
      rule: r,
      key: `rl:${r.name}:${subject}:${w.start}`,
      ttl: Math.ceil(r.windowMs / 1000) + 60,
      resetAt: w.resetAt,
      scope,
    };
  });
}

/**
 * Given the counts after incrementing, the first rule exceeded, or null.
 *
 * When several are exceeded the one that frees up LAST wins: telling a client to retry in ten
 * minutes when the daily limit still holds produces a retry that is refused again.
 */
export function evaluate(entries, counts, now) {
  let worst = null;
  entries.forEach((e, i) => {
    if (counts[i] > e.rule.limit) {
      if (!worst || e.resetAt > worst.resetAt) worst = e;
    }
  });
  if (!worst) return null;
  return { scope: worst.scope, rule: worst.rule.name, retryAfter: retryAfterSeconds(now, worst.resetAt) };
}

/**
 * The key an IP is counted under.
 *
 * An IPv6 client is counted by its /64. A single phone is routinely handed a whole /64 and can
 * rotate through it freely, so counting the full address would give every request a fresh
 * budget.
 */
export function ipSubject(ip) {
  const s = String(ip || "").trim().toLowerCase();
  if (!s) return "unknown";
  if (s.startsWith("::ffff:") && s.includes(".")) return s.slice(7);
  if (!s.includes(":")) return s;
  const full = expandV6(s);
  return full ? full.slice(0, 4).join(":") + "::/64" : s;
}

function expandV6(s) {
  const addr = s.split("%")[0];
  const halves = addr.split("::");
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const fill = halves.length === 2 ? 8 - head.length - tail.length : 0;
  if (fill < 0) return null;
  const parts = [...head, ...Array(fill).fill("0"), ...tail];
  if (parts.length !== 8 || !parts.every((p) => /^[0-9a-f]{1,4}$/.test(p))) return null;
  return parts.map((p) => p.padStart(4, "0"));
}

/**
 * A subject as it is stored: salted and hashed, so the store never holds a raw IP or install id.
 * Salted because the IPv4 space is small enough to hash exhaustively.
 */
export function hashSubject(salt, kind, value) {
  return createHash("sha256").update(`${salt}\u0000${kind}\u0000${value}`).digest("hex").slice(0, 32);
}

/** Count one request against [entries] in [store]; the verdict from [evaluate]. */
export async function hit(store, entries, now) {
  const counts = await store.incr(entries.map((e) => ({ key: e.key, ttl: e.ttl })));
  return evaluate(entries, counts, now);
}
