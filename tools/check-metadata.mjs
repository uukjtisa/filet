#!/usr/bin/env node
/**
 * The metadata writer, and the promises the support table makes on its behalf.
 *
 * A writer is a different proposition from a viewer: a viewer that misunderstands a format
 * shows a wrong answer, and a writer that misunderstands one destroys somebody's file. The
 * decision to ship one was made deliberately and the safety is what makes it defensible, so
 * this checks that the safety is still there rather than that the feature is still there.
 *
 * Four properties, each of which has a specific way of decaying:
 *
 *  1. **Nothing is written in place.** The rewrite is built in memory, re-parsed, and only
 *     then does it replace the original. Streaming into the file being read is the change
 *     somebody makes for memory reasons and it turns a half-understood format into a
 *     half-destroyed file.
 *  2. **The result is verified before the original is touched.** A writer that trusts its own
 *     output overwrites a good file with a bad one and reports success.
 *  3. **Every format in the table says what it cannot do.** A tier without a caveat is a claim
 *     nobody can check, and the caveat is the first thing to go when a format is moved up.
 *  4. **The dangerous formats stay read-only.** MP4, PDF and Ogg all carry internal offsets
 *     that every edit invalidates; a video that plays and then silently stops halfway is
 *     worse than one that will not open.
 *
 *   node tools/check-metadata.mjs              check
 *   node tools/check-metadata.mjs --selftest   prove it catches each of those decaying
 *
 * Prints "METADATA OK" only after every assertion passes.
 */
import { readFileSync, existsSync } from "node:fs";

const SRC = {
  support: "app/src/main/java/dev/niccc2007/filet/metadata/MetadataSupport.kt",
  store: "app/src/main/java/dev/niccc2007/filet/metadata/MetadataStore.kt",
  png: "app/src/main/java/dev/niccc2007/filet/metadata/PngText.kt",
  containers: "app/src/main/java/dev/niccc2007/filet/metadata/ContainerComments.kt",
};

const TESTS = [
  "app/src/test/java/dev/niccc2007/filet/metadata/PngTextTest.kt",
  "app/src/test/java/dev/niccc2007/filet/metadata/ContainerCommentsTest.kt",
  "app/src/test/java/dev/niccc2007/filet/metadata/MetadataSupportTest.kt",
];

/** Formats that must never become writable without somebody deciding to. */
const MUST_STAY_READ_ONLY = ["mp4", "pdf", "flac", "ogg", "m4a", "mov"];

/**
 * Parse the table into {name, tier, extensions, caveat}.
 *
 * Split on the entries rather than matching one with a regex. A `fields = listOf(` that wraps
 * across lines ends with the same `),` an entry does, so a non-greedy match for a whole entry
 * stops inside it and never reaches the caveat underneath - which reported a format that
 * documents itself perfectly well as undocumented.
 */
export function formats(src) {
  const out = [];
  const table = src.slice(src.indexOf("val FORMATS"));
  for (const body of table.split(/\n\s*Format\(/).slice(1)) {
    const name = (body.match(/name = "([^"]+)"/) || [])[1];
    if (!name) continue;
    const tier = (body.match(/tier = Tier\.([A-Z_]+)/) || [])[1];
    const exts = [...(body.match(/extensions = listOf\(([^)]*)\)/) || ["", ""])[1]
      .matchAll(/"([^"]+)"/g)].map((x) => x[1]);
    out.push({
      name,
      tier,
      extensions: exts,
      caveat: /caveat = /.test(body),
      custom: /custom = true/.test(body),
      binary: /binary = true/.test(body),
    });
  }
  return out;
}

export function problems(src) {
  const found = [];
  if (!src.support) return ["the metadata support table is missing entirely"];
  if (!src.store) return ["the metadata writer is missing entirely"];

  const table = formats(src.support);
  if (table.length === 0) found.push("no formats could be read out of the support table");

  // 3. every non-FULL format explains itself
  for (const f of table) {
    if (!f.tier) {
      found.push(`${f.name} has no tier`);
    } else if (f.tier !== "FULL" && !f.caveat) {
      found.push(`${f.name} is ${f.tier} with no caveat saying what it cannot do`);
    }
  }

  // 4. the dangerous ones stay read-only
  for (const ext of MUST_STAY_READ_ONLY) {
    const claiming = table.filter((f) => f.extensions.includes(ext));
    if (claiming.length === 0) {
      found.push(`${ext} is no longer in the table at all, so nothing states its risk`);
      continue;
    }
    const writable = claiming.filter((f) => f.tier !== "READ_ONLY");
    if (writable.length) {
      found.push(
        `${ext} is writable as "${writable[0].name}" - it carries internal offsets that ` +
          `every edit invalidates, and moving it must be a deliberate decision`,
      );
    }
  }

  // 1. never in place
  if (!/openWrite\(path, append = false\)/.test(src.store)) {
    found.push("the writer does not replace the file wholesale, so a partial write is possible");
  }
  if (/openWrite[\s\S]{0,200}openRead/.test(src.store)) {
    found.push("the writer opens the file for writing while still reading it");
  }

  // 2. verified before the original is touched
  if (!/private fun verify\(/.test(src.store)) {
    found.push("nothing verifies the rebuilt file, so a bad rewrite replaces a good file");
  } else {
    const verifyCall = src.store.indexOf("val verified = verify(");
    const writeCall = src.store.indexOf("vfs.openWrite(path");
    if (verifyCall < 0 || writeCall < 0 || verifyCall > writeCall) {
      found.push("the file is written before the rebuild is verified");
    }
  }
  if (!/maxRewriteBytes/.test(src.store)) {
    found.push("there is no size ceiling, so a large file is held twice in memory");
  }

  // The writers themselves must refuse rather than guess.
  if (!/fun isValidKeyword/.test(src.png)) {
    found.push("PNG keywords are not validated, so an invalid one writes a chunk readers skip");
  }
  if (!/CRC32/.test(src.png)) {
    found.push("PNG chunks carry no CRC, so strict decoders reject the whole file");
  }
  // The CRC covers the type and the data and NOT the length. Including the length is the
  // classic mistake and produces a file that looks written and opens in nothing.
  if (!/crc\.update\(typeBytes\)\s*\n\s*crc\.update\(data\)/.test(src.png)) {
    found.push("the PNG CRC is not computed over exactly the type and the data");
  }
  if (!/findEocd/.test(src.containers)) {
    found.push("the zip writer does not locate the end-of-central-directory record");
  }
  // Searched backwards, because the signature occurs inside compressed data too.
  if (!/while \(i >= lowest\)/.test(src.containers)) {
    found.push("the zip writer does not search backwards for the EOCD, so it can find a false one");
  }

  // The claim that exactly one format takes a custom key and a binary payload.
  const both = table.filter((f) => f.custom && f.binary && f.tier === "FULL");
  if (both.length !== 1 || both[0].name !== "PNG") {
    found.push(
      `exactly one format should claim custom keys and binary payloads at FULL; ` +
        `found ${both.map((f) => f.name).join(", ") || "none"}`,
    );
  }

  for (const t of TESTS) {
    if (!existsSync(t)) found.push(`${t} is missing - the writer has no test behind it`);
  }

  return found;
}

function read() {
  const out = {};
  for (const [k, p] of Object.entries(SRC)) out[k] = existsSync(p) ? readFileSync(p, "utf8") : "";
  return out;
}

if (process.argv.includes("--selftest")) {
  const good = read();
  const base = problems(good);
  if (base.length) {
    console.error("SELFTEST cannot run: the tree does not pass its own check");
    for (const p of base) console.error("  " + p);
    process.exit(1);
  }
  const swap = (key, from, to) => {
    const c = { ...good };
    c[key] = c[key].split(from).join(to);
    if (c[key] === good[key]) throw new Error(`mutation did not apply: ${from}`);
    return c;
  };

  const CASES = [
    ["mp4 quietly becoming writable", () =>
      swap("support", 'extensions = listOf("mp4", "m4v", "m4a", "mov"),\n            tier = Tier.READ_ONLY',
        'extensions = listOf("mp4", "m4v", "m4a", "mov"),\n            tier = Tier.FULL')],
    ["a caveat dropped from a partial format", () =>
      swap("support", 'caveat = "Extended (VP8X) files only', 'unused = "Extended (VP8X) files only')],
    ["verification removed", () => swap("store", "private fun verify(", "private fun unusedCheck(")],
    ["the write happening before the check", () => {
      const c = { ...good };
      c.store = c.store.replace(
        "        val verified = verify(built, key, value)",
        "        val verified = true; val unusedLater = verify(built, key, value)",
      );
      return c;
    }],
    ["the size ceiling removed", () => swap("store", "maxRewriteBytes", "noCeiling")],
    ["PNG keyword validation removed", () => swap("png", "fun isValidKeyword", "fun unusedKeywordCheck")],
    ["the PNG CRC computed over the wrong bytes", () =>
      swap("png", "crc.update(typeBytes)\n        crc.update(data)", "crc.update(data)")],
    ["the zip EOCD searched forwards", () =>
      swap("containers", "while (i >= lowest)", "while (i <= lowest)")],
    ["a second format claiming custom keys and binary", () =>
      swap("support", 'name = "GIF",\n            extensions = listOf("gif"),\n            tier = Tier.FULL,\n            fields = listOf("Comment"),\n            custom = false,\n            binary = false,',
        'name = "GIF",\n            extensions = listOf("gif"),\n            tier = Tier.FULL,\n            fields = listOf("Comment"),\n            custom = true,\n            binary = true,')],
  ];

  let bad = 0;
  for (const [name, mutate] of CASES) {
    if (problems(mutate()).length === 0) {
      console.error(`SELFTEST FAILED: ${name} — not caught`);
      bad++;
    }
  }
  if (bad) process.exit(1);
  console.log(`METADATA SELFTEST OK  (${CASES.length} negative controls, 1 positive)`);
  process.exit(0);
}

const src = read();
const found = problems(src);
if (found.length) {
  console.error("The metadata writer does not hold up:\n");
  for (const p of found) console.error("  " + p);
  process.exit(1);
}
const table = formats(src.support);
const byTier = {};
for (const f of table) byTier[f.tier] = (byTier[f.tier] || 0) + 1;
console.log(
  `METADATA OK  (${table.length} formats: ` +
    Object.entries(byTier).map(([t, n]) => `${n} ${t.toLowerCase()}`).join(", ") +
    `; nothing written in place, every rewrite verified before it replaces anything)`,
);
