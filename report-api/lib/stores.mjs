/**
 * Where rate-limit counts and duplicate markers live.
 *
 * Production: Upstash Redis over its REST API, which is what Vercel's Marketplace provisions and
 * which needs no client library - one `fetch` per request. The integration injects either the
 * `KV_REST_API_*` or the `UPSTASH_REDIS_REST_*` pair depending on its age; both are read.
 *
 * Local: an in-memory store, only when `REPORT_DEV_MEMORY_LIMITER=1` is set AND the deployment is
 * not production. It is there so the rejection paths can be proved with curl on a laptop. It is
 * not a rate limit on a serverless platform and is refused there outright.
 */

export function storeFromEnv(env, fetchImpl = fetch) {
  const url = env.KV_REST_API_URL || env.UPSTASH_REDIS_REST_URL;
  const token = env.KV_REST_API_TOKEN || env.UPSTASH_REDIS_REST_TOKEN;
  if (url && token) return upstashStore(url, token, fetchImpl);
  if (env.REPORT_DEV_MEMORY_LIMITER === "1" && env.VERCEL_ENV !== "production") return memoryStore();
  return null;
}

/**
 * Two commands per key in one round trip: create the counter with its expiry if absent, then
 * increment. `SET NX EX` before `INCR` rather than `INCR` then `EXPIRE`, so a crash between the
 * two can never leave a counter with no expiry that blocks a client forever.
 */
export function upstashStore(url, token, fetchImpl = fetch) {
  const base = url.replace(/\/+$/, "");
  async function pipeline(cmds) {
    const res = await fetchImpl(`${base}/pipeline`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify(cmds),
    });
    if (!res.ok) throw new Error(`store answered ${res.status}`);
    const out = await res.json();
    if (!Array.isArray(out) || out.some((r) => r && r.error)) throw new Error("store command failed");
    return out.map((r) => r.result);
  }
  return {
    kind: "upstash",
    async incr(items) {
      const cmds = items.flatMap((i) => [["SET", i.key, "0", "EX", String(i.ttl), "NX"], ["INCR", i.key]]);
      const results = await pipeline(cmds);
      return items.map((_, n) => Number(results[n * 2 + 1]));
    },
    /** True when [key] was absent and is now claimed. */
    async claim(key, ttl, value = "1") {
      const [r] = await pipeline([["SET", key, value, "EX", String(ttl), "NX"]]);
      return r === "OK";
    },
    async release(key) {
      await pipeline([["DEL", key]]);
    },
    async get(key) {
      const [r] = await pipeline([["GET", key]]);
      return r ?? null;
    },
    async set(key, value, ttl) {
      await pipeline([["SET", key, value, "EX", String(ttl)]]);
    },
  };
}

export function memoryStore(clock = () => Date.now()) {
  const m = new Map();
  const live = (k) => {
    const e = m.get(k);
    if (e && e.exp <= clock()) m.delete(k);
    return m.get(k);
  };
  return {
    kind: "memory",
    async incr(items) {
      return items.map((i) => {
        const e = live(i.key) || { n: 0, exp: clock() + i.ttl * 1000 };
        e.n += 1;
        m.set(i.key, e);
        return e.n;
      });
    },
    async claim(key, ttl, value = "1") {
      if (live(key)) return false;
      m.set(key, { n: 1, v: value, exp: clock() + ttl * 1000 });
      return true;
    },
    async release(key) {
      m.delete(key);
    },
    async get(key) {
      const e = live(key);
      return e && "v" in e ? e.v : e ? "1" : null;
    },
    async set(key, value, ttl) {
      m.set(key, { n: 1, v: value, exp: clock() + ttl * 1000 });
    },
  };
}
