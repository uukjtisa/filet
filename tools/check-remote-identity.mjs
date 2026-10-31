#!/usr/bin/env node
/**
 * Every field of a saved remote has decided what editing it does to that remote's index rows.
 *
 * SEARCH.md §13.7. Two edits to a saved share - its SMB share name or WebDAV base path, and its
 * protocol - make every stored row under it WRONG, and at the save site they look exactly like
 * renaming it. `RemoteIdentity.RULES` names every field of `NetConnection` with its effect.
 * Kotlin will not fail to compile when a field is added without a rule, so this does, alongside
 * `RemoteIdentityTest`, which checks the same thing by reflection.
 *
 * Also held: the two DROP fields stay DROP. Downgrading `share` or `protocol` to NONE is the
 * exact bug the rules exist to prevent, and a test matrix edited along with it would agree.
 *
 *   node tools/check-remote-identity.mjs              check
 *   node tools/check-remote-identity.mjs --selftest   prove each drift is caught
 *
 * Prints "REMOTE IDENTITY OK" / "REMOTE IDENTITY SELFTEST OK" only after every assertion passes.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const NET = join(ROOT, "core-vfs/src/main/java/dev/niccc2007/filet/vfs/provider/net");
const CONN = join(NET, "NetConnections.kt");
const IDENT = join(NET, "RemoteIdentity.kt");
const INDEX = join(ROOT, "core-index/src/main/java/dev/niccc2007/filet/index/SqliteIndex.kt");

/** The constructor properties of `data class NetConnection(...)`, in order. */
export function fields(src) {
  const start = src.indexOf("data class NetConnection(");
  if (start < 0) return [];
  let depth = 0, i = src.indexOf("(", start), end = -1;
  for (; i < src.length; i++) {
    if (src[i] === "(") depth++;
    else if (src[i] === ")") { depth--; if (depth === 0) { end = i; break; } }
  }
  const body = src.slice(src.indexOf("(", start) + 1, end)
    .replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  return [...body.matchAll(/\bval\s+(\w+)\s*:/g)].map((m) => m[1]);
}

/** field -> effect, from `Rule("name", Effect.X)`. */
export function rules(src) {
  return new Map([...src.matchAll(/Rule\("(\w+)",\s*Effect\.(\w+)\)/g)].map((m) => [m[1], m[2]]));
}

/** The body of `override suspend fun forgetVolumes`, or "" when it is missing. */
export function sweepBody(index) {
  const at = index.indexOf("override suspend fun forgetVolumes(");
  if (at < 0) return "";
  const next = index.indexOf("\n    override ", at + 10);
  return index.slice(at, next < 0 ? undefined : next);
}

export function problems(conn, ident, index) {
  const found = [];
  // The sweep trusts the cascade: volume -> node -> side tables, and the FTS tables through
  // their AFTER DELETE triggers. A sweep that grows its own DELETE FROM node has stopped
  // trusting it without saying why, and two ways of deleting one row is how one gets missed.
  const sweep = sweepBody(index);
  if (!sweep) found.push("SqliteIndex has no forgetVolumes override, so a deleted share's rows are never dropped");
  else {
    if (!/DELETE FROM volume WHERE id = \?/.test(sweep)) found.push("forgetVolumes does not delete the volume row the cascade starts from");
    if (/DELETE FROM node\b/.test(sweep)) found.push("forgetVolumes deletes from node itself - the cascade does that; say why before duplicating it");
  }
  const f = fields(conn);
  const r = rules(ident);
  if (f.length === 0) found.push("no fields found in data class NetConnection - has it moved?");
  if (r.size === 0) found.push("no Rule(...) entries found in RemoteIdentity.RULES");
  for (const name of f) {
    if (!r.has(name)) found.push(`NetConnection.${name} has no rule in RemoteIdentity.RULES - decide what editing it does to stored rows`);
  }
  for (const name of r.keys()) {
    if (!f.includes(name)) found.push(`RemoteIdentity.RULES names "${name}", which NetConnection no longer has`);
  }
  if (r.get("protocol") !== "DROP") found.push("a protocol change must DROP: the scheme in the volume root changes");
  if (!["DROP", "DROP_WHERE_SHARE_IS_A_PATH"].includes(r.get("share"))) {
    found.push("a share / WebDAV base path edit must drop rows: every stored path now names a different file");
  }
  if (!/fun meaningChanged\(old: NetConnection, new: NetConnection\): Boolean = RULES\.any/.test(ident)) {
    found.push("meaningChanged is not decided by RULES, so a rule can exist and be ignored");
  }
  if (!/onRowsLost\(RemoteIdentity\.volumeUuid\(gone\)\)/.test(conn)) {
    found.push("NetConnections.delete does not report the lost rows, so a deleted share leaks its index");
  }
  if (!/RemoteIdentity\.rowsLost\(old, connection\)/.test(conn)) {
    found.push("NetConnections.save does not ask RemoteIdentity, so a meaning-changing edit keeps wrong rows");
  }
  return found;
}

const conn = readFileSync(CONN, "utf8");
const ident = readFileSync(IDENT, "utf8");
const index = readFileSync(INDEX, "utf8");

if (process.argv.includes("--selftest")) {
  if (problems(conn, ident, index).length) {
    console.error("REMOTE IDENTITY SELFTEST cannot run: the tree does not pass its own check");
    for (const p of problems(conn, ident, index)) console.error("  " + p);
    process.exit(1);
  }
  const swap = (text, from, to) => {
    const out = text.replace(from, to);
    if (out === text) throw new Error(`mutation did not apply: ${from}`);
    return out;
  };
  const CASES = [
    ["a field added with no rule", () => [swap(conn, "    val indexed: Boolean = false,", "    val indexed: Boolean = false,\n    val region: String = \"\","), ident]],
    ["a rule for a field that is gone", () => [conn, swap(ident, 'Rule("indexed", Effect.NONE)', 'Rule("indexed", Effect.NONE) { it.indexed },\n        Rule("ghost", Effect.NONE)')]],
    ["the share edit downgraded to harmless", () => [conn, swap(ident, 'Rule("share", Effect.DROP_WHERE_SHARE_IS_A_PATH)', 'Rule("share", Effect.NONE)')]],
    ["the protocol change downgraded to harmless", () => [conn, swap(ident, 'Rule("protocol", Effect.DROP)', 'Rule("protocol", Effect.NONE)')]],
    ["delete back to leaking", () => [swap(conn, "onRowsLost(RemoteIdentity.volumeUuid(gone))", "Unit"), ident]],
    ["save no longer asking", () => [swap(conn, "RemoteIdentity.rowsLost(old, connection)", "false"), ident]],
    ["the sweep removed from the index", () => [conn, ident, swap(index, "override suspend fun forgetVolumes(", "suspend fun unusedSweep(")]],
    // Mutated inside forgetVolumes only: the same DELETE appears elsewhere in the index.
    ["the sweep deleting nodes by hand", () => {
      const body = sweepBody(index);
      const bad = swap(body, "DELETE FROM volume WHERE id = ?", "DELETE FROM node WHERE vol_id = ?\", arrayOf<Any>(id))\n                db.execSQL(\"DELETE FROM volume WHERE id = ?");
      return [conn, ident, index.replace(body, bad)];
    }],
  ];
  let bad = 0;
  for (const [name, mutate] of CASES) {
    const [c, i, x = index] = mutate();
    if (problems(c, i, x).length === 0) { console.error(`SELFTEST FAILED: ${name} - not caught`); bad++; }
  }
  if (bad) process.exit(1);
  console.log(`REMOTE IDENTITY SELFTEST OK  (${CASES.length} negative controls, 1 positive)`);
  process.exit(0);
}

const found = problems(conn, ident, index);
if (found.length) {
  console.error("A saved remote's index rows can outlive what they mean:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
console.log(`REMOTE IDENTITY OK  (${fields(conn).length} fields, each with a rule; share and protocol edits drop rows; delete sweeps)`);
