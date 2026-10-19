/**
 * What an attachment actually is, from its bytes, and its metadata removed.
 *
 * The declared mime is attacker-controlled: it is a string in a JSON body. Only the magic number
 * and the container's own structure decide the type, and the declared type must agree with it.
 * PNG and JPEG only - every screenshot path on Android produces one of the two, and each format
 * accepted is one more parser of hostile bytes.
 */

const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

/** The real type of [buf], or null when it is neither a well-formed PNG nor JPEG frame. */
export function sniff(buf) {
  if (!buf || buf.length < 12) return null;
  if (PNG_SIG.every((b, i) => buf[i] === b)) {
    // The first chunk of a PNG is IHDR and the last is IEND. Checking both refuses a file that
    // merely starts with the signature - a polyglot, or a truncated upload.
    if (buf.toString("latin1", 12, 16) !== "IHDR") return null;
    if (buf.toString("latin1", buf.length - 8, buf.length - 4) !== "IEND") return null;
    return "image/png";
  }
  if (buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) {
    // End-of-image marker near the end. Some encoders pad after it, so the tail is searched.
    const tail = buf.subarray(Math.max(0, buf.length - 64));
    for (let i = tail.length - 2; i >= 0; i--) if (tail[i] === 0xff && tail[i + 1] === 0xd9) return "image/jpeg";
    return null;
  }
  return null;
}

/** PNG chunks that carry text, a timestamp or EXIF - the places a location or a name hides. */
const PNG_DROP = new Set(["tEXt", "zTXt", "iTXt", "eXIf", "tIME"]);

/**
 * The PNG with its metadata chunks removed.
 *
 * Each chunk carries its own CRC, so dropping whole chunks leaves every remaining one valid with
 * nothing recomputed. Throws on a chunk whose length runs past the end: that file is not a PNG.
 */
export function stripPng(buf) {
  const out = [buf.subarray(0, 8)];
  let i = 8;
  while (i < buf.length) {
    if (i + 12 > buf.length) throw new Error("truncated chunk");
    const len = buf.readUInt32BE(i);
    const type = buf.toString("latin1", i + 4, i + 8);
    const end = i + 12 + len;
    if (end > buf.length) throw new Error("chunk past end");
    if (!PNG_DROP.has(type)) out.push(buf.subarray(i, end));
    i = end;
    if (type === "IEND") break;
  }
  return Buffer.concat(out);
}

/**
 * The JPEG with EXIF/XMP (APP1), IPTC (APP13) and comments removed.
 *
 * EXIF is where a phone camera writes GPS coordinates, and a screenshot pipeline that passed
 * through a photo editor can carry them. Segments are walked up to the start of scan; everything
 * after it is entropy-coded data and is copied untouched. APP2 is kept, since it holds the
 * colour profile and dropping it shifts the colours.
 */
export function stripJpeg(buf) {
  const out = [buf.subarray(0, 2)];
  let i = 2;
  while (i < buf.length) {
    if (buf[i] !== 0xff) throw new Error("expected a marker");
    const m = buf[i + 1];
    if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) {
      out.push(buf.subarray(i, i + 2));
      i += 2;
      continue;
    }
    if (m === 0xd9) { out.push(buf.subarray(i)); break; }
    if (i + 4 > buf.length) throw new Error("truncated segment");
    const len = buf.readUInt16BE(i + 2);
    const end = i + 2 + len;
    if (len < 2 || end > buf.length) throw new Error("segment past end");
    if (m === 0xda) { out.push(buf.subarray(i)); break; }
    if (m !== 0xe1 && m !== 0xed && m !== 0xfe) out.push(buf.subarray(i, end));
    i = end;
  }
  return Buffer.concat(out);
}

export function stripMetadata(buf, type) {
  return type === "image/png" ? stripPng(buf) : stripJpeg(buf);
}
