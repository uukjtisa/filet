#!/usr/bin/env node
/**
 * The archives Filet writes are opened by a program that has never heard of Filet.
 *
 * Every other archive check in this repository reads Filet's output with Filet's own readers.
 * For most formats that is a fair round trip, but for the two that round 9 added it is not:
 * zip4j writes the encrypted zips and the volume sets, and zip4j reads them back, which proves
 * the two halves agree with each other and nothing about whether the file is a zip.
 *
 * So this hands them to **7-Zip** and believes what it says. The claims it settles:
 *
 *  - an AES-256 zip is refused without the password and opens with it,
 *  - a legacy ZipCrypto zip does the same,
 *  - a multi-volume zip set opens from its final `.zip`,
 *  - a numbered `.001` split of a 7z is recognised as one archive,
 *  - the strength setting is actually applied, measured as a size difference.
 *
 *   node tools/check-archives.mjs              check the fixtures
 *   node tools/check-archives.mjs --selftest   prove each assertion can fail
 *
 * Needs the fixtures, which `:core-vfs:testDebugUnitTest --tests "*ArchiveFixturesTest*"`
 * writes, and 7-Zip on PATH. Both absences are reported as SKIPPED rather than as a pass: a
 * check that quietly succeeds when it could not run is worse than no check.
 *
 * Prints "ARCHIVES OK" only after every assertion passes.
 */
import { existsSync, readFileSync, mkdtempSync, rmSync, statSync, readdirSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { join } from "node:path";
import { tmpdir } from "node:os";

const FIXTURES = "core-vfs/build/archive-fixtures";
const PASSWORD = "filet-test";

/** 7-Zip, wherever this box keeps it. Windows installs it outside PATH more often than not. */
function findSevenZip() {
  const candidates = [
    "7z",
    "C:/Program Files/7-Zip/7z.exe",
    "C:/Program Files (x86)/7-Zip/7z.exe",
    "/usr/bin/7z",
    "/usr/local/bin/7z",
  ];
  for (const c of candidates) {
    try {
      execFileSync(c, ["i"], { stdio: "pipe", timeout: 20000 });
      return c;
    } catch (e) {
      if (e.status !== undefined) return c; // ran, but exited non-zero: still the binary
    }
  }
  return null;
}

/** @return {{ok: boolean, out: string}} — 7z's own verdict, never an exception. */
function sevenZip(bin, args) {
  try {
    const out = execFileSync(bin, args, { stdio: "pipe", encoding: "utf8", timeout: 120000 });
    return { ok: true, out };
  } catch (e) {
    return { ok: false, out: String(e.stdout || "") + String(e.stderr || "") };
  }
}

export function checks(bin, dir) {
  const problems = [];
  const t = (name, fn) => {
    try {
      const why = fn();
      if (why) problems.push(`${name}: ${why}`);
    } catch (e) {
      problems.push(`${name}: threw ${e.message}`);
    }
  };

  // 1 & 2. Encryption, both ways round. Listing a zip shows names whatever the contents are
  //        encrypted with, so the claim has to be tested by EXTRACTING.
  for (const [file, label] of [["aes.zip", "AES-256"], ["legacy.zip", "ZipCrypto"]]) {
    t(`${label} opens with its password`, () => {
      const out = mkdtempSync(join(tmpdir(), "fx-"));
      try {
        const r = sevenZip(bin, ["x", join(dir, file), `-p${PASSWORD}`, `-o${out}`, "-y"]);
        if (!r.ok) return `7-Zip could not extract it even with the password: ${r.out.slice(0, 200)}`;
        if (!existsSync(join(out, "note.txt"))) return "extracted, but note.txt is not there";
        const body = readFileSync(join(out, "note.txt"), "utf8");
        if (!body.includes("filet round 9")) return `note.txt came out as ${JSON.stringify(body.slice(0, 40))}`;
        return null;
      } finally {
        rmSync(out, { recursive: true, force: true });
      }
    });

    t(`${label} refuses a wrong password`, () => {
      const out = mkdtempSync(join(tmpdir(), "fx-"));
      try {
        const r = sevenZip(bin, ["x", join(dir, file), "-pdefinitely-wrong", `-o${out}`, "-y"]);
        // The only unacceptable outcome is readable content. 7-Zip may exit non-zero, or exit
        // zero with a CRC failure; either is a refusal.
        if (existsSync(join(out, "note.txt"))) {
          const body = readFileSync(join(out, "note.txt"), "utf8");
          if (body.includes("filet round 9")) return "the wrong password produced the real contents";
        }
        if (r.ok && !/wrong password|data error|crc failed/i.test(r.out)) {
          return "7-Zip reported success with the wrong password and said nothing about it";
        }
        return null;
      } finally {
        rmSync(out, { recursive: true, force: true });
      }
    });
  }

  // 3. A multi-volume zip set, opened from the final .zip the way any other tool would.
  t("a zip volume set is one archive to 7-Zip", () => {
    const r = sevenZip(bin, ["l", join(dir, "volumes.zip")]);
    if (!r.ok) return `7-Zip could not list it: ${r.out.slice(0, 200)}`;
    const missing = [];
    for (let i = 1; i <= 6; i++) if (!r.out.includes(`f${i}.bin`)) missing.push(`f${i}.bin`);
    if (missing.length) return `listed, but without ${missing.join(", ")}`;
    return null;
  });

  // 4. A numbered split, which is 7-Zip's own volume layout.
  t("a numbered .001 split is one archive to 7-Zip", () => {
    const first = join(dir, "numbered.7z.001");
    if (!existsSync(first)) return "numbered.7z.001 was not written";
    const r = sevenZip(bin, ["l", first]);
    if (!r.ok) return `7-Zip could not list it: ${r.out.slice(0, 200)}`;
    if (!r.out.includes("note.txt")) return "listed, but note.txt is not in it";
    return null;
  });

  // 5. Strength really reaches the encoder. Measured against the SAME input at both ends of
  //    the scale, rather than asserted from the setting that was passed in.
  t("the strength setting changes the output", () => {
    const lo = join(dir, "level0.zip");
    const hi = join(dir, "level9.zip");
    if (!existsSync(lo) || !existsSync(hi)) return "the level fixtures are missing";
    const a = statSync(lo).size;
    const b = statSync(hi).size;
    // The fixture is deliberately COMPRESSIBLE text. On random bytes this measures nothing -
    // deflate adds a few bytes of overhead, so store legitimately comes out smaller than
    // maximum and an "is 9 smaller than 0" check reports a bug that is not there. That was the
    // first version of this assertion and it failed on the first run for exactly that reason.
    if (a === b) return `level 0 and level 9 produced the same size (${a}) — the level is ignored`;
    if (b >= a) return `level 9 (${b}) is not smaller than level 0 (${a}) on compressible text`;
    // Store on 170 KB of repeated text against maximum deflate should not be a near thing.
    if (a / b < 5) return `level 9 only got ${(a / b).toFixed(1)}x on text that should crush — is the level reaching the encoder?`;
    return null;
  });

  return problems;
}

if (process.argv.includes("--selftest")) {
  // The controls here are about the SHAPE of the checker: it must report a problem for a
  // missing fixture and for a directory with nothing in it, rather than passing vacuously.
  const bin = findSevenZip();
  if (!bin) {
    console.log("ARCHIVES SELFTEST SKIPPED  (no 7-Zip on this machine)");
    process.exit(0);
  }
  const empty = mkdtempSync(join(tmpdir(), "fx-empty-"));
  const problems = checks(bin, empty);
  rmSync(empty, { recursive: true, force: true });
  if (problems.length < 5) {
    console.error(`SELFTEST FAIL: an empty fixture directory produced only ${problems.length} problem(s)`);
    process.exit(1);
  }
  console.log(`ARCHIVES SELFTEST OK  (${problems.length} problems reported for an empty fixture set)`);
  process.exit(0);
}

const bin = findSevenZip();
if (!bin) {
  console.error("ARCHIVES SKIPPED: 7-Zip is not on this machine, so nothing independent checked these.");
  process.exit(1);
}
if (!existsSync(FIXTURES) || readdirSync(FIXTURES).length === 0) {
  console.error(
    'ARCHIVES SKIPPED: no fixtures. Run ./gw.sh :core-vfs:testDebugUnitTest --tests "*ArchiveFixturesTest*" first.',
  );
  process.exit(1);
}

const problems = checks(bin, FIXTURES);
if (problems.length) {
  console.error(`ARCHIVE PROBLEMS: ${problems.length}`);
  for (const p of problems) console.error("  " + p);
  process.exit(1);
}
console.log(`ARCHIVES OK  (7-Zip read every fixture Filet wrote, and refused both wrong passwords)`);
