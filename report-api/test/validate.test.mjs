import { test } from "node:test";
import assert from "node:assert/strict";
import { validate, Refusal } from "../lib/validate.mjs";
import { sniff, stripJpeg, stripPng } from "../lib/sniff.mjs";
import { MAX_IMAGE_BYTES, MAX_IMAGES, LOG_MAX } from "../lib/config.mjs";
import { b64, jpeg, png } from "./fixtures.mjs";

const ok = { installId: "abcd1234efgh", type: "bug", title: "Copy stalls", body: "Copying a big folder stops at 99%." };

function refused(input, code) {
  try {
    validate(input);
  } catch (e) {
    assert.ok(e instanceof Refusal, String(e));
    assert.equal(e.code, code);
    return e;
  }
  assert.fail(`expected ${code}`);
}

test("a minimal report validates", () => {
  const r = validate(ok);
  assert.equal(r.title, "Copy stalls");
  assert.deepEqual(r.images, []);
});

test("each required field is checked, and the field is named", () => {
  assert.equal(refused({ ...ok, installId: "x" }, "invalid_field").field, "installId");
  assert.equal(refused({ ...ok, type: "rant" }, "invalid_field").field, "type");
  assert.equal(refused({ ...ok, title: "a" }, "invalid_field").field, "title");
  assert.equal(refused({ ...ok, title: "x".repeat(121) }, "invalid_field").field, "title");
  assert.equal(refused({ ...ok, body: "short" }, "invalid_field").field, "body");
  assert.equal(refused({ ...ok, body: "x".repeat(10_001) }, "invalid_field").field, "body");
  assert.equal(refused({ ...ok, body: "!!!!!!!!!!!!!!!!" }, "invalid_field").field, "body");
  refused([], "bad_json");
  refused(null, "bad_json");
});

test("a long log is trimmed from the front, keeping the end", () => {
  const r = validate({ ...ok, log: "a".repeat(LOG_MAX) + "THE CRASH" });
  assert.ok(r.log.endsWith("THE CRASH"));
  assert.ok(r.log.length <= LOG_MAX + 80);
});

test("device accepts a flat object and refuses nesting", () => {
  assert.equal(validate({ ...ok, device: { model: "Pixel 8", android: 15 } }).device, "model: Pixel 8\nandroid: 15");
  refused({ ...ok, device: { a: { b: 1 } } }, "invalid_field");
});

test("image count is capped", () => {
  const imgs = Array.from({ length: MAX_IMAGES + 1 }, () => ({ mime: "image/png", data: b64(png()) }));
  assert.equal(refused({ ...ok, images: imgs }, "too_many_images").status, 413);
});

test("image size is capped before decoding", () => {
  const big = Buffer.concat([png(), Buffer.alloc(MAX_IMAGE_BYTES)]);
  assert.equal(refused({ ...ok, images: [{ mime: "image/png", data: b64(big) }] }, "image_too_large").status, 413);
});

test("the declared type must match the bytes", () => {
  refused({ ...ok, images: [{ mime: "image/png", data: b64(jpeg()) }] }, "image_type");
  refused({ ...ok, images: [{ mime: "image/gif", data: b64(png()) }] }, "image_type");
  const html = Buffer.from("<html><script>alert(1)</script></html>");
  refused({ ...ok, images: [{ mime: "image/png", data: b64(html) }] }, "image_type");
  refused({ ...ok, images: [{ mime: "image/png", data: "not base64!!" }] }, "invalid_field");
});

test("magic numbers: real images pass, a bare signature does not", () => {
  assert.equal(sniff(png()), "image/png");
  assert.equal(sniff(jpeg()), "image/jpeg");
  const sigOnly = Buffer.concat([png().subarray(0, 8), Buffer.from("<?php echo 1; ?>")]);
  assert.equal(sniff(sigOnly), null);
  // Signature and a trailing IEND, but no IHDR: a polyglot built to pass a check of both ends.
  const noHeader = Buffer.concat([png().subarray(0, 8), Buffer.from("<script>x</script>"), png().subarray(-12)]);
  assert.equal(sniff(noHeader), null);
  assert.equal(sniff(Buffer.from([0xff, 0xd8, 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0])), null);
  assert.equal(sniff(Buffer.alloc(3)), null);
});

test("metadata is stripped and the images stay well formed", () => {
  const p = stripPng(png());
  assert.ok(!p.toString("latin1").includes("Somebody Private"));
  assert.equal(sniff(p), "image/png");
  const j = stripJpeg(jpeg());
  assert.ok(!j.toString("latin1").includes("GPS"));
  assert.equal(sniff(j), "image/jpeg");
  assert.ok(j.toString("latin1").includes("JFIF"));
});

test("a validated image arrives stripped", () => {
  const r = validate({ ...ok, images: [{ mime: "image/jpeg", data: "data:image/jpeg;base64," + b64(jpeg()) }] });
  assert.equal(r.images[0].mime, "image/jpeg");
  assert.ok(!r.images[0].bytes.toString("latin1").includes("GPS"));
});
