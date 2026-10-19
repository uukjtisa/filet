import * as C from "./config.mjs";
import { cleanLine, cleanText, keepTail, redact } from "./sanitize.mjs";
import { sniff, stripMetadata } from "./sniff.mjs";

/**
 * The request body, checked and normalised, or the first reason it is refused.
 *
 * Every refusal carries a machine-readable `code` the app switches on, the `field` it concerns
 * where there is one, and an HTTP status. The message is for the form to show as-is.
 */

export class Refusal extends Error {
  constructor(status, code, message, extra = {}) {
    super(message);
    this.status = status;
    this.code = code;
    Object.assign(this, extra);
  }
}

const bad = (field, message) => new Refusal(400, "invalid_field", message, { field });

/** An install id: what the app mints once. Format-checked only - it is a hint, never identity. */
export const INSTALL_ID = /^[A-Za-z0-9_-]{8,64}$/;

export function validate(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new Refusal(400, "bad_json", "The report must be a JSON object.");
  }

  const installId = input.installId;
  if (typeof installId !== "string" || !INSTALL_ID.test(installId)) {
    throw bad("installId", "The app did not send a valid install id.");
  }

  // One id per draft, kept across retries by the app. Optional, so an older client still files.
  let submissionId = null;
  if (input.submissionId != null) {
    if (typeof input.submissionId !== "string" || !INSTALL_ID.test(input.submissionId)) {
      throw bad("submissionId", "The app sent an invalid submission id.");
    }
    submissionId = input.submissionId;
  }

  const type = input.type;
  if (typeof type !== "string" || !Object.hasOwn(C.TYPES, type)) {
    throw bad("type", `Type must be one of: ${Object.keys(C.TYPES).join(", ")}.`);
  }

  if (typeof input.title !== "string") throw bad("title", "A title is required.");
  const title = cleanLine(input.title);
  if (title.length < C.TITLE_MIN) throw bad("title", `The title needs at least ${C.TITLE_MIN} characters.`);
  if (title.length > C.TITLE_MAX) throw bad("title", `The title can be at most ${C.TITLE_MAX} characters.`);

  if (typeof input.body !== "string") throw bad("body", "A description is required.");
  const body = redact(cleanText(input.body).trim());
  if (body.length < C.BODY_MIN) throw bad("body", `The description needs at least ${C.BODY_MIN} characters.`);
  if (body.length > C.BODY_MAX) throw bad("body", `The description can be at most ${C.BODY_MAX} characters.`);
  // Something a person typed, not a run of punctuation or whitespace from a fuzzer.
  if (!/\p{L}/u.test(body)) throw bad("body", "The description has no words in it.");

  let log = null;
  if (input.log != null) {
    if (typeof input.log !== "string") throw bad("log", "The log must be text.");
    // Trimmed rather than refused: a long log is the normal case, and the end is what matters.
    log = keepTail(redact(cleanText(input.log)), C.LOG_MAX);
    if (!log.trim()) log = null;
  }

  const device = input.device == null ? null : deviceText(input.device);
  const images = input.images == null ? [] : decodeImages(input.images);

  return { installId, submissionId, type, title, body, log, device, images };
}

/** Device details as `key: value` lines, from either a string or a flat object. */
function deviceText(d) {
  let text;
  if (typeof d === "string") text = cleanText(d);
  else if (typeof d === "object" && !Array.isArray(d)) {
    const entries = Object.entries(d);
    if (entries.length > C.DEVICE_MAX_FIELDS) throw bad("device", `At most ${C.DEVICE_MAX_FIELDS} device fields.`);
    text = entries
      .map(([k, v]) => {
        if (v !== null && typeof v === "object") throw bad("device", "Device fields must be plain values.");
        return `${cleanLine(k)}: ${cleanLine(String(v))}`;
      })
      .join("\n");
  } else throw bad("device", "Device details must be text or an object.");
  text = redact(text).trim();
  if (text.length > C.DEVICE_MAX) throw bad("device", `Device details can be at most ${C.DEVICE_MAX} characters.`);
  return text || null;
}

const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/;
const ALLOWED = new Set(["image/png", "image/jpeg"]);

/**
 * Screenshots, decoded, type-checked from their bytes and stripped of metadata.
 *
 * Sizes are checked on the base64 length BEFORE decoding, so an oversized image costs a string
 * comparison rather than a megabyte allocation.
 */
export function decodeImages(list) {
  if (!Array.isArray(list)) throw bad("images", "Images must be a list.");
  if (list.length > C.MAX_IMAGES) {
    throw new Refusal(413, "too_many_images", `At most ${C.MAX_IMAGES} screenshots per report.`, { field: "images", limit: C.MAX_IMAGES });
  }
  return list.map((img, index) => {
    const field = `images[${index}]`;
    if (!img || typeof img !== "object") throw bad(field, "Each image must be an object.");
    const declared = String(img.mime ?? "").toLowerCase();
    if (!ALLOWED.has(declared)) {
      throw new Refusal(415, "image_type", "Screenshots must be PNG or JPEG.", { field, index });
    }
    let data = typeof img.data === "string" ? img.data : "";
    data = data.replace(/^data:[^,]*,/, "").replace(/\s+/g, "");
    if (!data || !BASE64.test(data) || data.length % 4 === 1) throw bad(field, "The image data is not valid base64.");
    if (Math.floor((data.length * 3) / 4) > C.MAX_IMAGE_BYTES + 2) {
      throw new Refusal(413, "image_too_large", `Each screenshot can be at most ${C.MAX_IMAGE_BYTES / 1_000_000} MB.`, { field, index, limit: C.MAX_IMAGE_BYTES });
    }
    const buf = Buffer.from(data, "base64");
    if (buf.length > C.MAX_IMAGE_BYTES) {
      throw new Refusal(413, "image_too_large", `Each screenshot can be at most ${C.MAX_IMAGE_BYTES / 1_000_000} MB.`, { field, index, limit: C.MAX_IMAGE_BYTES });
    }
    const actual = sniff(buf);
    if (actual !== declared) {
      throw new Refusal(415, "image_type", actual ? `Image ${index + 1} is a ${actual}, not the ${declared} it was declared as.` : `Image ${index + 1} is not a PNG or JPEG.`, { field, index });
    }
    let clean;
    try {
      clean = stripMetadata(buf, actual);
    } catch {
      throw new Refusal(415, "image_corrupt", `Image ${index + 1} could not be read.`, { field, index });
    }
    return { mime: actual, bytes: clean };
  });
}
