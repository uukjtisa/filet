#!/usr/bin/env node
/**
 * The metadata writer, and the promises the support table makes on its behalf.
 *
 * A writer is a different proposition from a viewer: a viewer that misunderstands a format
 * shows a wrong answer, and a writer that misunderstands one destroys somebody's file. The
 * decision to ship one was made deliberately and the safety is what makes it defensible, so
 * this checks that the safety is still there rather than that the feature is still there.
 *
 * ## What changed, and why this file was rewritten rather than relaxed
 *
 * This check used to hold six extensions - mp4, m4a, mov, pdf, ogg, flac - at READ_ONLY, and it
 * did its job: the round that made them writable was stopped here and had to come back and say
 * why. The reasons are in `MetadataSupport`, one per format, and each is a specific piece of work
 * rather than a decision to be braver. So the rule is not deleted, it is tightened in the
 * direction that still matters:
 *
 *  - those formats must stay **PARTIAL**, never FULL. Each has a shape it refuses, and a format
 *    at FULL is one claiming there is no such file.
 *  - each must still **name the file it refuses**, in code, not only in prose.
 *  - EXIF must stay READ_ONLY. It is the one left where writing is not offered at all.
 *
 * ## The fault this round actually had
 *
 * Three rows of the table were fiction: MP3 advertising an attached picture, EXIF advertising
 * nine fields, WebP advertising a comment slot, with no code behind any of them. Every check in
 * this file passed throughout, because they all asked whether the table was consistent with
 * itself and none asked whether it was connected to anything. That is what the engine rules
 * below are for, and they are the most important thing here.
 *
 * The properties, each with a specific way of decaying:
 *
 *  1. **Nothing is written in place.** The rewrite is built in memory, re-parsed, and only
 *     then does it replace the original.
 *  2. **The result is verified before the original is touched.** A writer that trusts its own
 *     output overwrites a good file with a bad one and reports success.
 *  3. **Every format in the table says what it cannot do**, and names the code that does it.
 *  4. **No row claims more than its engine implements**, and no engine is unreachable.
 *  5. **The per-format traps stay closed** - the Ogg checksum variant, the conditional MP4
 *     offset shift, the PDF append, the zip's copy-don't-recompress.
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
  engines: "app/src/main/java/dev/niccc2007/filet/metadata/MetadataContainer.kt",
  png: "app/src/main/java/dev/niccc2007/filet/metadata/PngText.kt",
  containers: "app/src/main/java/dev/niccc2007/filet/metadata/ContainerComments.kt",
  vorbis: "app/src/main/java/dev/niccc2007/filet/metadata/VorbisComment.kt",
  mp4: "app/src/main/java/dev/niccc2007/filet/metadata/Mp4Tags.kt",
  pdf: "app/src/main/java/dev/niccc2007/filet/metadata/PdfInfo.kt",
  zip: "app/src/main/java/dev/niccc2007/filet/metadata/Zip.kt",
};

const TESTS = [
  "app/src/test/java/dev/niccc2007/filet/metadata/PngTextTest.kt",
  "app/src/test/java/dev/niccc2007/filet/metadata/ContainerCommentsTest.kt",
  "app/src/test/java/dev/niccc2007/filet/metadata/MetadataSupportTest.kt",
  "app/src/test/java/dev/niccc2007/filet/metadata/Id3Test.kt",
  "app/src/test/java/dev/niccc2007/filet/metadata/VorbisCommentTest.kt",
  "app/src/test/java/dev/niccc2007/filet/metadata/Mp4TagsTest.kt",
  "app/src/test/java/dev/niccc2007/filet/metadata/PdfInfoTest.kt",
  "app/src/test/java/dev/niccc2007/filet/metadata/ZipAndOoxmlTest.kt",
  "app/src/test/java/dev/niccc2007/filet/metadata/ExifAndCoverTest.kt",
];

/**
 * Formats whose containers carry internal offsets. Writable now, and each one has a shape it
 * refuses - so PARTIAL is the honest tier and FULL would be a claim that there is no such file.
 */
const MUST_STAY_PARTIAL = ["mp4", "pdf", "ogg", "m4a", "mov", "opus"];

/** The one where writing is still not offered: a wrong offset ruins the embedded thumbnail. */
const MUST_STAY_READ_ONLY = ["webp"];

/**
 * Parse the table into {name, tier, extensions, caveat, engine}.
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
    const exts = [...(body.match(/extensions = listOf\(([\s\S]*?)\),/) || ["", ""])[1]
      .matchAll(/"([^"]+)"/g)].map((x) => x[1]);
    out.push({
      name,
      tier,
      extensions: exts,
      caveat: /caveat = /.test(body),
      custom: /custom = true/.test(body),
      binary: /binary = true/.test(body),
      engine: (body.match(/engine = (\w+)/) || [])[1],
    });
  }
  return out;
}

/**
 * Each container object, and which of the four jobs it overrides.
 *
 * Read out of the source rather than inferred from the table, because the whole point is to
 * compare the two against each other.
 */
export function engines(src) {
  const out = [];
  const parts = src.split(/\nobject (\w+) : MetadataContainer \{/);
  for (let i = 1; i < parts.length; i += 2) {
    const name = parts[i];
    const body = parts[i + 1].split(/\n\}/)[0];
    out.push({
      name,
      writes: /override val writes = true/.test(body),
      pictures: /override val pictures = true/.test(body),
      hasPut: /override fun put\(/.test(body),
      hasVerify: /override fun verify\(/.test(body),
      hasCover: /override fun putCover\(/.test(body),
      hasRefusal: /override fun refusal\(/.test(body),
    });
  }
  return out;
}

export function problems(src) {
  const found = [];
  if (!src.support) return ["the metadata support table is missing entirely"];
  if (!src.store) return ["the metadata writer is missing entirely"];
  if (!src.engines) return ["the container registry is missing entirely"];

  const table = formats(src.support);
  if (table.length === 0) found.push("no formats could be read out of the support table");
  const engineList = engines(src.engines);
  const byName = Object.fromEntries(engineList.map((e) => [e.name, e]));

  // 3. every non-FULL format explains itself, and every row names its code
  for (const f of table) {
    if (!f.tier) {
      found.push(`${f.name} has no tier`);
    } else if (f.tier !== "FULL" && !f.caveat) {
      found.push(`${f.name} is ${f.tier} with no caveat saying what it cannot do`);
    }
    if (!f.engine) {
      found.push(`${f.name} names no container, so nothing is on the hook for its claims`);
      continue;
    }
    const e = byName[f.engine];
    if (!e) {
      found.push(`${f.name} names ${f.engine}, which is not a container in the registry`);
      continue;
    }
    // 4. no row claims more than its engine implements. The three fictions this round found
    // were all of exactly this shape.
    if (f.tier !== "READ_ONLY" && !(e.writes && e.hasPut)) {
      found.push(`${f.name} is ${f.tier} but ${f.engine} cannot write`);
    }
    if (f.tier === "READ_ONLY" && e.writes) {
      found.push(`${f.name} is READ_ONLY but ${f.engine} writes - one of the two is wrong`);
    }
    if (f.binary && !(e.pictures && e.hasCover)) {
      found.push(`${f.name} claims an attached picture but ${f.engine} has no picture path`);
    }
    if (f.tier !== "READ_ONLY" && !e.hasVerify) {
      found.push(`${f.engine} writes without checking its own output, so a bad rewrite replaces a good file`);
    }
  }

  // An engine nothing points at is a feature nobody can reach - the other half of the same fault.
  for (const e of engineList) {
    if (!table.some((f) => f.engine === e.name)) {
      found.push(`${e.name} is a container no format row points at, so it is unreachable`);
    }
  }

  // 4. the ones with internal offsets stay conditional, and say which file they refuse
  for (const ext of MUST_STAY_PARTIAL) {
    const claiming = table.filter((f) => f.extensions.includes(ext));
    if (claiming.length === 0) {
      found.push(`${ext} is no longer in the table at all, so nothing states its risk`);
      continue;
    }
    const best = claiming.filter((f) => f.tier !== "READ_ONLY")[0];
    if (!best) continue;
    if (best.tier !== "PARTIAL") {
      found.push(
        `${ext} is ${best.tier} as "${best.name}" - it carries internal offsets, it has a ` +
          `shape it refuses, and PARTIAL is the only honest tier for that`,
      );
    }
    const e = byName[best.engine];
    if (e && !e.hasRefusal) {
      found.push(
        `${best.name} is PARTIAL and ${best.engine} names no refusal, so the file it cannot ` +
          `handle is a promise made only in prose`,
      );
    }
  }
  for (const ext of MUST_STAY_READ_ONLY) {
    const writable = table.filter((f) => f.extensions.includes(ext) && f.tier !== "READ_ONLY");
    if (writable.length) {
      found.push(`${ext} became writable as "${writable[0].name}" - that must be a deliberate decision`);
    }
  }
  if (!table.some((f) => f.name.includes("EXIF") && f.tier === "READ_ONLY")) {
    found.push("EXIF is no longer read-only, and a wrong offset there ruins the embedded thumbnail");
  }

  // 1. never in place
  if (!/openWrite\(path, append = false\)/.test(src.store)) {
    found.push("the writer does not replace the file wholesale, so a partial write is possible");
  }
  if (/openWrite[\s\S]{0,200}openRead/.test(src.store)) {
    found.push("the writer opens the file for writing while still reading it");
  }

  // 2. verified before the original is touched, in the one routine every write goes through
  const checkCall = src.store.indexOf("runCatching { check(built) }");
  const writeCall = src.store.indexOf("vfs.openWrite(path");
  if (checkCall < 0) {
    found.push("nothing verifies the rebuilt file, so a bad rewrite replaces a good file");
  } else if (writeCall < 0 || checkCall > writeCall) {
    found.push("the file is written before the rebuild is verified");
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

  // 5. the per-format traps, each of which produces a file that looks written and is not.

  // Ogg's checksum is a plain CRC-32 with no reflection, no initial value and no final
  // inversion. Reaching for the familiar one gives a page every player rejects silently.
  if (!/0x04c11db7/.test(src.vorbis)) {
    found.push("the Ogg page checksum no longer uses the polynomial Ogg specifies");
  }
  if (!/var r = 0\s*\n\s*for \(i in from until from \+ len\)/.test(src.vorbis)) {
    found.push("the Ogg checksum no longer starts from zero, so every page it writes is rejected");
  }
  if (!/writeU32le\(page, 22, 0\)/.test(src.vorbis)) {
    found.push("the Ogg checksum field is not zeroed before the checksum is computed over the page");
  }
  // STREAMINFO has to be the first metadata block or the file does not decode at all.
  if (!/sortedBy \{ if \(it\.type == STREAMINFO\) 0 else 1 \}/.test(src.vorbis)) {
    found.push("the FLAC rebuild no longer forces STREAMINFO to the front");
  }

  // The MP4 offset shift must be conditional on where moov used to be. Applying it to every
  // entry breaks any file whose media comes first, which is most files recorded on a phone.
  if (!/if \(v >= moovAt\)/.test(src.mp4)) {
    found.push("MP4 chunk offsets are shifted unconditionally, which breaks every file with its media first");
  }
  if (!/"stco", "co64"/.test(src.mp4)) {
    found.push("the MP4 writer no longer corrects both the 32-bit and the 64-bit offset tables");
  }
  if (!/fun isFragmented/.test(src.mp4)) {
    found.push("the MP4 writer no longer detects a fragmented file, whose offsets live outside moov");
  }

  // A PDF is appended to, never rewritten. That is what keeps a signature valid.
  if (!/return original \+ sb\.toString\(\)/.test(src.pdf)) {
    found.push("the PDF writer no longer appends to the original, so it can no longer keep a signature valid");
  }
  if (!/\/Prev \$\{shape\.startxref\}/.test(src.pdf)) {
    found.push("the appended PDF trailer does not chain to the previous cross-reference");
  }
  if (!/%010d 00000 n /.test(src.pdf)) {
    found.push("a PDF cross-reference entry is no longer exactly twenty bytes wide");
  }

  // The zip rewriter copies entries still compressed. Re-compressing them all rewrites a
  // document that somebody may have under version control.
  const deflateCalls = (src.zip.match(/deflate\(content\)/g) || []).length;
  if (deflateCalls !== 1) {
    found.push("the zip rewriter compresses more than the one entry being replaced");
  }
  if (!/FLAG_ENCRYPTED/.test(src.zip) || !/UNKNOWN_32/.test(src.zip)) {
    found.push("the zip rewriter no longer refuses encrypted or zip64 archives");
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
    ["mp4 promoted to FULL", () => {
      const c = { ...good };
      c.support = c.support.replace(
        /name = "MP4 \/ M4A",([\s\S]{0,200}?)tier = Tier\.PARTIAL/,
        'name = "MP4 / M4A",$1tier = Tier.FULL',
      );
      if (c.support === good.support) throw new Error("mutation did not apply: mp4 tier");
      return c;
    }],
    ["webp quietly becoming writable", () => {
      const c = { ...good };
      c.support = c.support.replace(
        /name = "WebP EXIF",([\s\S]{0,200}?)tier = Tier\.READ_ONLY/,
        'name = "WebP EXIF",$1tier = Tier.PARTIAL',
      );
      if (c.support === good.support) throw new Error("mutation did not apply: webp tier");
      return c;
    }],
    ["a row claiming a picture its engine cannot carry", () =>
      swap("engines", "object Id3Container : MetadataContainer {\n    override val writes = true\n    override val pictures = true",
        "object Id3Container : MetadataContainer {\n    override val writes = true")],
    ["a row claiming a tier its engine cannot write", () =>
      swap("engines", "object PdfContainer : MetadataContainer {\n    override val writes = true",
        "object PdfContainer : MetadataContainer {")],
    ["an engine left unreachable by the table", () =>
      swap("support", "engine = FlacContainer,", "engine = PngContainer,")],
    ["a partial format whose refusal is only prose", () =>
      swap("engines", "override fun refusal(bytes: ByteArray) = PdfInfo.refusal(bytes)", "")],
    ["a container writing without checking its output", () =>
      swap("engines", "object OoxmlContainer : MetadataContainer {\n    override val writes = true\n    override fun matches(ext: String, bytes: ByteArray) = Ooxml.isOoxml(bytes)\n    override fun read(bytes: ByteArray) = Ooxml.read(bytes)\n    override fun put(bytes: ByteArray, label: String, value: String) = Ooxml.put(bytes, label, value)\n    override fun verify(built: ByteArray, label: String, value: String) =\n        verifyByRead(this::read, built, label, value)",
        "object OoxmlContainer : MetadataContainer {\n    override val writes = true\n    override fun matches(ext: String, bytes: ByteArray) = Ooxml.isOoxml(bytes)\n    override fun read(bytes: ByteArray) = Ooxml.read(bytes)\n    override fun put(bytes: ByteArray, label: String, value: String) = Ooxml.put(bytes, label, value)")],
    ["a caveat dropped from a partial format", () =>
      swap("support", 'caveat = "ID3v2.3 and 2.4 only', 'unused = "ID3v2.3 and 2.4 only')],
    ["verification removed from the one write path", () =>
      swap("store", "runCatching { check(built) }", "runCatching { true }")],
    ["the size ceiling removed", () => swap("store", "maxRewriteBytes", "noCeiling")],
    ["PNG keyword validation removed", () => swap("png", "fun isValidKeyword", "fun unusedKeywordCheck")],
    ["the PNG CRC computed over the wrong bytes", () =>
      swap("png", "crc.update(typeBytes)\n        crc.update(data)", "crc.update(data)")],
    ["the zip EOCD searched forwards", () =>
      swap("containers", "while (i >= lowest)", "while (i <= lowest)")],
    ["the Ogg checksum given the familiar initial value", () =>
      swap("vorbis", "var r = 0\n        for (i in from until from + len)", "var r = -1\n        for (i in from until from + len)")],
    ["the Ogg checksum field left in place while it is computed", () =>
      swap("vorbis", "writeU32le(page, 22, 0)\n        page[26]", "page[26]")],
    ["STREAMINFO no longer forced to the front of a FLAC", () =>
      swap("vorbis", "sortedBy { if (it.type == STREAMINFO) 0 else 1 }", "sortedBy { 0 }")],
    ["MP4 offsets shifted unconditionally", () =>
      swap("mp4", "if (v >= moovAt)", "if (true)")],
    ["the 64-bit MP4 offset table forgotten", () =>
      swap("mp4", '"stco", "co64"', '"stco"')],
    ["the PDF writer rewriting instead of appending", () =>
      swap("pdf", "return original + sb.toString()", "return sb.toString()")],
    ["the PDF update not chaining to the previous table", () =>
      swap("pdf", "/Prev ${shape.startxref}", "/Prev 0")],
    ["the zip rewriter re-compressing every entry", () =>
      swap("zip", "deflate(content)", "deflate(content) /* and */ ; deflate(content)")],
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
const pictures = table.filter((f) => f.binary).length;
console.log(
  `METADATA OK  (${table.length} formats: ` +
    Object.entries(byTier).map(([t, n]) => `${n} ${t.toLowerCase()}`).join(", ") +
    `; ${pictures} carry cover art; every row names its engine, nothing written in place, ` +
    `every rewrite verified before it replaces anything)`,
);
